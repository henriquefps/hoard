package server

import (
	"archive/zip"
	"bytes"
	"net/http"
	"net/netip"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/henriquefps/hoard/server/internal/previews"
)

// hoard-server 0.8.1 (ADR 0018): full resolution for 100 % zoom, named downloads, the gallery behind tailscale serve,
// and culling written from the gallery reaching other clients.

// TestWebFullResolution: a JPEG is its own full-resolution layer; a lone RAW and a HEIF get a JPEG the server makes on
// demand (202 while it is made), in its own lane, never in the backlog; videos have none.
func TestWebFullResolution(t *testing.T) {
	hs, gen, _, ids := previewsHarness(t, "image", "proxy")
	c := webSignIn(t, hs)
	get := func(id string) *httpResult {
		return result(hs.do("GET", "/web/items/"+id+"/full", nil, webLocal(), withCookie(c)))
	}
	// The backlog makes thumbnails and proxies, never full-resolution stills.
	if err := hs.srv.prev.ScanNow(t.Context()); err != nil {
		t.Fatal(err)
	}
	work(t, hs, previews.JobImage)
	if n := work(t, hs, previews.JobFull); n != 0 {
		t.Fatalf("backlog made %d full stills", n)
	}
	// A JPEG photo: the file itself, inline, named.
	w := get(ids["PHOTO.JPG"])
	if w.code != 200 || w.h.Get("X-Hoard-Full") != "original" || w.h.Get("Content-Type") != "image/jpeg" ||
		!strings.HasPrefix(w.h.Get("Content-Disposition"), "inline;") {
		t.Fatalf("photo: %d %v", w.code, w.h)
	}
	// A pair: its camera JPEG.
	if w := get(ids["PAIR.ARW"]); w.code != 200 || w.h.Get("X-Hoard-Full") != "original" || !strings.Contains(w.h.Get("Content-Disposition"), "PAIR.JPG") {
		t.Fatalf("pair: %d %v", w.code, w.h)
	}
	// A lone RAW: pending, made in the full lane at the waiting priority, then served (and immutable with ?v=).
	w = get(ids["RAWONLY.ARW"])
	if w.code != 202 || w.h.Get("Retry-After") == "" || w.h.Get("X-Hoard-Full") != "raw" {
		t.Fatalf("raw pending: %d %v", w.code, w.h)
	}
	if n := work(t, hs, previews.JobFull); n != 1 {
		t.Fatalf("full jobs run: %d", n)
	}
	w = get(ids["RAWONLY.ARW"])
	if w.code != 200 || w.h.Get("Content-Type") != "image/jpeg" || !strings.HasPrefix(w.body, "full full.jpg") || w.h.Get("X-Hoard-Full") != "raw" {
		t.Fatalf("raw full: %d %v %q", w.code, w.h, w.body)
	}
	last := gen.jobs()[len(gen.jobs())-1]
	if last.Job != previews.JobFull || last.Source.Role != "raw" || last.Source.LongEdge != 6000 {
		t.Fatalf("job %+v", last)
	}
	// A HEIF (the Live Photo's still): converted.
	if w := get(ids["LIVE.HEIC"]); w.code != 202 || w.h.Get("X-Hoard-Full") != "converted" {
		t.Fatalf("heif: %d %v", w.code, w.h)
	}
	// A video: none.
	if w := get(ids["CLIP.MP4"]); w.code != 404 {
		t.Fatalf("video: %d", w.code)
	}
	// Signed out: refused.
	expect(t, hs.do("GET", "/web/items/"+ids["PHOTO.JPG"]+"/full", nil, webLocal()), 401, "unauthorized")
}

// TestWebFullCacheBound: past the bound, the least recently served full stills go (and are made again when asked).
func TestWebFullCacheBound(t *testing.T) {
	hs, _, _, ids := previewsHarness(t)
	c := webSignIn(t, hs)
	m := hs.srv.prev
	for _, id := range []string{ids["RAWONLY.ARW"], ids["LIVE.HEIC"]} {
		expect(t, hs.do("GET", "/web/items/"+id+"/full", nil, webLocal(), withCookie(c)), 202, "")
		work(t, hs, previews.JobFull)
		expect(t, hs.do("GET", "/web/items/"+id+"/full", nil, webLocal(), withCookie(c)), 200, "")
	}
	rows, err := hs.st.PreviewsByAge(t.Context(), previews.JobFull)
	if err != nil || len(rows) != 2 || rows[0].ItemID != ids["RAWONLY.ARW"] {
		t.Fatalf("rows %+v %v", rows, err)
	}
	// Serve the RAW's again: it becomes the most recent.
	later := time.Now().Add(time.Second)
	hs.st.Now = func() time.Time { return later }
	expect(t, hs.do("GET", "/web/items/"+ids["RAWONLY.ARW"]+"/full", nil, webLocal(), withCookie(c)), 200, "")
	rows, _ = hs.st.PreviewsByAge(t.Context(), previews.JobFull)
	if rows[0].ItemID != ids["LIVE.HEIC"] {
		t.Fatalf("touch: %+v", rows)
	}
	m.SetFullCacheBytes(rows[1].Bytes)
	m.TrimFull(t.Context())
	rows, _ = hs.st.PreviewsByAge(t.Context(), previews.JobFull)
	if len(rows) != 1 || rows[0].ItemID != ids["RAWONLY.ARW"] {
		t.Fatalf("after trim: %+v", rows)
	}
	expect(t, hs.do("GET", "/web/items/"+ids["LIVE.HEIC"]+"/full", nil, webLocal(), withCookie(c)), 202, "")
}

type httpResult struct {
	code int
	h    http.Header
	body string
}

func result(w interface {
	Result() *http.Response
}) *httpResult {
	r := w.Result()
	var b bytes.Buffer
	_, _ = b.ReadFrom(r.Body)
	return &httpResult{r.StatusCode, r.Header, b.String()}
}

func TestContentDisposition(t *testing.T) {
	for in, want := range map[string]string{
		"DSC01000.ARW":        `attachment; filename="DSC01000.ARW"; filename*=UTF-8''DSC01000.ARW`,
		"Férias 2024 (1).jpg": `attachment; filename="F_rias 2024 (1).jpg"; filename*=UTF-8''F%C3%A9rias%202024%20%281%29.jpg`,
		`a"b\c.zip`:           `attachment; filename="a_b_c.zip"; filename*=UTF-8''a%22b_c.zip`,
		"":                    "attachment",
	} {
		if got := contentDisposition("attachment", in); got != want {
			t.Errorf("%q: %s, want %s", in, got, want)
		}
	}
}

// TestWebDownloadNames: single files and zips are named after what they hold, in the header and at the end of the URL.
func TestWebDownloadNames(t *testing.T) {
	hs, _, folderID := itemsHarness(t)
	c := webSignIn(t, hs)
	a := library(t, hs, c, "")
	var pair libRow
	byDay := map[string][]string{}
	for _, r := range a.Rows {
		if r.K == "pair" && pair.ID == "" {
			pair = r
		}
		if r.Fo == folderID && r.D != "" {
			byDay[r.D] = append(byDay[r.D], r.ID)
		}
	}
	// One file, through the URL that ends in its name.
	m := decode(t, hs.do("GET", "/web/items/"+pair.ID, nil, webLocal(), withCookie(c)))
	var fp, name string
	for _, f := range m["item"].(map[string]any)["files"].([]any) {
		ff := f.(map[string]any)
		if ff["role"] == "raw" {
			fp, name = ff["fingerprint"].(string), ff["name"].(string)
		}
	}
	w := hs.do("GET", "/web/items/"+pair.ID+"/files/"+fp+"/"+url.PathEscape(name)+"?download=1", nil, webLocal(), withCookie(c))
	if w.Code != 200 || w.Header().Get("Content-Disposition") != `attachment; filename="`+name+`"; filename*=UTF-8''`+name {
		t.Fatalf("file: %d %v", w.Code, w.Header())
	}
	// One pair: "<stem>.zip" with the RAW and the JPEG under their own names.
	zipOf := func(ids ...string) (string, []string) {
		t.Helper()
		w := hs.do("POST", "/web/downloads", map[string]any{"items": ids}, webWrite(), withCookie(c))
		expect(t, w, 200, "")
		m := decode(t, w)
		u, n := m["url"].(string), m["name"].(string)
		if !strings.HasSuffix(u, "/"+url.PathEscape(n)) {
			t.Fatalf("url %s for %s", u, n)
		}
		w = hs.do("GET", u, nil, webLocal(), withCookie(c))
		if w.Code != 200 || !strings.Contains(w.Header().Get("Content-Disposition"), "filename*=UTF-8''"+strings.ReplaceAll(url.PathEscape(n), "+", "%2B")) {
			t.Fatalf("zip %d %v", w.Code, w.Header())
		}
		zr, err := zip.NewReader(bytes.NewReader(w.Body.Bytes()), int64(w.Body.Len()))
		if err != nil {
			t.Fatal(err)
		}
		var names []string
		for _, f := range zr.File {
			names = append(names, f.Name)
		}
		return n, names
	}
	stem := strings.TrimSuffix(pair.N, ".ARW")
	n, names := zipOf(pair.ID)
	if n != stem+".zip" || len(names) != 2 || names[0] != stem+".ARW" || names[1] != stem+".JPG" {
		t.Fatalf("pair zip %s %v", n, names)
	}
	// Several from one directory: "<directory>-<n> photos.zip"; from several days: the first and last days.
	for dir, ids := range byDay {
		if len(ids) < 2 {
			continue
		}
		if n, _ := zipOf(ids[:2]...); n != dir+"-2 photos.zip" {
			t.Fatalf("dir zip %q for %s", n, dir)
		}
		break
	}
	var mixed []string
	for _, ids := range byDay {
		mixed = append(mixed, ids[0])
	}
	if n, _ := zipOf(mixed...); !strings.HasPrefix(n, "2024-05-0") || !strings.Contains(n, "–2024-05-0") {
		t.Fatalf("mixed zip %q", n)
	}
}

// TestWebBehindTailscaleServe: with TRUST_TAILSCALE_SERVE=1 the gallery and the setup page answer what tailscale
// serve proxies (from loopback or a configured peer, one tailnet address in X-Forwarded-For, no Funnel, no other
// proxy's header), set Secure cookies over its HTTPS and check Origin against X-Forwarded-Host. Off, nothing changes.
func TestWebBehindTailscaleServe(t *testing.T) {
	ts := func(r *http.Request) {
		r.RemoteAddr = "127.0.0.1:41000"
		r.Host = "127.0.0.1:3071"
		r.Header.Set("X-Forwarded-For", "100.101.102.103")
		r.Header.Set("X-Forwarded-Proto", "https")
		r.Header.Set("X-Forwarded-Host", "cachyos-fps.tail1234.ts.net")
		r.Header.Set("Tailscale-User-Login", "owner@example.com")
	}
	with := func(opts ...reqOpt) reqOpt {
		return func(r *http.Request) {
			for _, o := range opts {
				o(r)
			}
		}
	}
	// Off (the default): 404 like any proxied request.
	hs, _, _ := itemsHarness(t)
	hs.cookie = nil
	expect(t, hs.do("GET", "/web/session", nil, ts), 404, "not_found")

	hs = newHarnessWith(t, true, func(c *Config) { c.TrustTailscaleServe = true })
	hs.pair("Tablet") // sets the admin password
	hs.cookie = nil
	expect(t, hs.do("GET", "/web/session", nil, ts), 200, "")
	expect(t, hs.do("GET", "/admin/api/state", nil, ts), 200, "")
	// Refused: a Funnel request, another proxy's header, a public or several addresses, a peer that is not loopback.
	for i, bad := range []reqOpt{
		with(ts, header("Tailscale-Funnel-Request", "?1")),
		with(ts, header("Cf-Connecting-Ip", "100.101.102.103")),
		with(ts, header("Forwarded", "for=100.101.102.103")),
		with(ts, header("X-Forwarded-For", "203.0.113.7")),
		with(ts, header("X-Forwarded-For", "100.101.102.103, 100.64.0.1")),
		with(ts, func(r *http.Request) { r.Header.Add("X-Forwarded-For", "100.64.0.1") }),
		with(ts, remote("172.18.0.1:5000")),
		with(ts, remote("192.168.1.20:5000")),
	} {
		if w := hs.do("GET", "/web/session", nil, bad); w.Code != 404 {
			t.Errorf("case %d: %d", i, w.Code)
		}
	}
	// IPv6 tailnet addresses are tailnet too.
	expect(t, hs.do("GET", "/web/session", nil, with(ts, header("X-Forwarded-For", "fd7a:115c:a1e0::1234"))), 200, "")
	// Sign-in: Secure cookie over tailscale serve's HTTPS; Origin is the ts.net name, not the Host it connected to.
	login := func(origin string) *http.Response {
		return hs.do("POST", "/web/session", map[string]any{"password": "a long admin password"}, ts, header(webHeader, "1"), header("Origin", origin)).Result()
	}
	if r := login("https://127.0.0.1:3071"); r.StatusCode != 403 {
		t.Fatalf("wrong origin: %d", r.StatusCode)
	}
	r := login("https://cachyos-fps.tail1234.ts.net")
	if r.StatusCode != 200 {
		t.Fatalf("login %d", r.StatusCode)
	}
	var cookie *http.Cookie
	for _, c := range r.Cookies() {
		if c.Name == webCookieName {
			cookie = c
		}
	}
	if cookie == nil || !cookie.Secure || !cookie.HttpOnly {
		t.Fatalf("cookie %+v", cookie)
	}
	m := decode(t, hs.do("GET", "/web/session", nil, ts, withCookie(cookie)))
	if m["signedIn"] != true {
		t.Fatalf("session %v", m)
	}
	// Plain HTTP from the LAN still works, with a cookie that is not Secure.
	c := webSignIn(t, hs)
	if c.Secure {
		t.Fatal("Secure cookie over plain HTTP")
	}
	// A configured peer (a container's bridge gateway) is trusted like loopback.
	hs = newHarnessWith(t, true, func(c *Config) {
		c.TrustTailscaleServe = true
		c.TailscaleServePeers = mustAddrs("172.18.0.1")
	})
	expect(t, hs.do("GET", "/web/session", nil, with(ts, remote("172.18.0.1:5000"))), 200, "")
	expect(t, hs.do("GET", "/web/session", nil, with(ts, remote("172.18.0.3:5000"))), 404, "not_found")
}

func TestDefaultGateway(t *testing.T) {
	table := "Iface\tDestination\tGateway \tFlags\tRefCnt\tUse\tMetric\tMask\t\tMTU\tWindow\tIRTT\n" +
		"eth0\t00000000\t010012AC\t0003\t0\t0\t0\t00000000\t0\t0\t0\n" +
		"eth0\t000012AC\t00000000\t0001\t0\t0\t0\t0000FFFF\t0\t0\t0\n"
	if a, ok := defaultGateway(table); !ok || a.String() != "172.18.0.1" {
		t.Fatalf("%v %v", a, ok)
	}
	if _, ok := defaultGateway("Iface\tDestination\tGateway\n"); ok {
		t.Fatal("no default route")
	}
}

// TestWebCullingReachesOtherClients (ADR 0016): a pick and a rating set in the gallery are field writes by the
// session's writer id, appear in the items feed with a new revision for a paired device, and never touch Reveal's
// photo records (the bridge runs one way: records → culling).
func TestWebCullingReachesOtherClients(t *testing.T) {
	hs, key, _ := itemsHarness(t)
	c := webSignIn(t, hs)
	writer := decode(t, hs.do("GET", "/web/session", nil, webLocal(), withCookie(c)))["writer"].(string)
	feed := decode(t, hs.do("GET", "/v1/items/changes?include=culling", nil, bearer(key)))
	cursor := feed["cursor"].(string)
	records := decode(t, hs.do("GET", "/v1/changes", nil, bearer(key)))
	a := library(t, hs, c, "")
	id := a.Rows[0].ID
	w := hs.do("POST", "/web/culling", map[string]any{"changes": []map[string]any{
		{"itemId": id, "field": "flag", "value": "pick", "baseRev": 0, "modifiedAt": 1762851000000},
		{"itemId": id, "field": "rating", "value": 5, "baseRev": 0, "modifiedAt": 1762851000000},
	}}, webWrite(), withCookie(c))
	expect(t, w, 200, "")
	for _, r := range decode(t, w)["results"].([]any) {
		if r.(map[string]any)["status"] != "applied" {
			t.Fatalf("push %v", r)
		}
	}
	feed = decode(t, hs.do("GET", "/v1/items/changes?include=culling&cursor="+url.QueryEscape(cursor), nil, bearer(key)))
	cs := feed["culling"].([]any)
	if len(cs) != 1 {
		t.Fatalf("feed %v", feed)
	}
	rec := cs[0].(map[string]any)
	flag, rating := rec["flag"].(map[string]any), rec["rating"].(map[string]any)
	if rec["itemId"] != id || flag["value"] != "pick" || flag["deviceId"] != writer || rating["value"] != float64(5) ||
		rating["deviceId"] != writer || flag["rev"].(float64) <= 0 || rec["rev"].(float64) <= 0 || feed["cursor"] == cursor {
		t.Fatalf("record %v", rec)
	}
	// Reveal's record feed is unchanged.
	after := decode(t, hs.do("GET", "/v1/changes", nil, bearer(key)))
	if after["cursor"] != records["cursor"] {
		t.Fatalf("records feed moved: %v → %v", records["cursor"], after["cursor"])
	}
}

func mustAddrs(s ...string) []netip.Addr {
	out := make([]netip.Addr, len(s))
	for i, a := range s {
		out[i] = netip.MustParseAddr(a)
	}
	return out
}
