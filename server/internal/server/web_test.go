package server

import (
	"archive/zip"
	"bytes"
	"compress/gzip"
	"encoding/json"
	"github.com/henriquefps/hoard/server/internal/webui"
	"io"
	"net/http"
	"sort"
	"strings"
	"testing"
)

// webLocal is a direct request from the LAN; webWrite adds the gallery's CSRF header.
func webLocal() reqOpt { return remote("192.168.1.20:5000") }
func webWrite() reqOpt {
	return func(r *http.Request) { r.Header.Set(webHeader, "1"); r.RemoteAddr = "192.168.1.20:5000" }
}

// webSignIn signs the harness's browser in to the gallery (the admin password is set by itemsHarness's pairing).
func webSignIn(t *testing.T, hs *harness) *http.Cookie {
	t.Helper()
	saved := hs.cookie
	hs.cookie = nil
	defer func() { hs.cookie = saved }()
	w := hs.do("POST", "/web/session", map[string]any{"password": "a long admin password"}, webWrite())
	expect(t, w, 200, "")
	for _, c := range w.Result().Cookies() {
		if c.Name == webCookieName {
			if !c.HttpOnly || c.SameSite != http.SameSiteStrictMode || c.Path != "/web/" {
				t.Fatalf("cookie %+v", c)
			}
			return c
		}
	}
	t.Fatal("no gallery cookie")
	return nil
}

func withCookie(c *http.Cookie) reqOpt { return func(r *http.Request) { r.AddCookie(c) } }

type libAnswer struct {
	Cursor  string            `json:"cursor"`
	Full    bool              `json:"full"`
	Rows    []libRow          `json:"rows"`
	Removed []string          `json:"removed"`
	Folders map[string]string `json:"folders"`
	Total   int               `json:"total"`
}

func library(t *testing.T, hs *harness, c *http.Cookie, cursor string) libAnswer {
	t.Helper()
	w := hs.do("GET", "/web/library?cursor="+cursor, nil, webLocal(), withCookie(c), header("Accept-Encoding", "gzip"))
	expect(t, w, 200, "")
	body := w.Body.Bytes()
	if w.Header().Get("Content-Encoding") == "gzip" {
		zr, err := gzip.NewReader(bytes.NewReader(body))
		if err != nil {
			t.Fatal(err)
		}
		if body, err = io.ReadAll(zr); err != nil {
			t.Fatal(err)
		}
	}
	var a libAnswer
	if err := json.Unmarshal(body, &a); err != nil {
		t.Fatal(err)
	}
	return a
}

func TestWebGateAndSession(t *testing.T) {
	hs, _, _ := itemsHarness(t)
	hs.cookie = nil
	// Only direct LAN/Tailscale requests: a public address, or a proxy header, gets 404 everywhere.
	for _, opt := range []reqOpt{remote("203.0.113.9:1"), func(r *http.Request) { r.RemoteAddr = "10.0.0.2:1"; r.Header.Set("Cf-Connecting-Ip", "1.2.3.4") }} {
		for _, p := range []string{"/web/session", "/web/library", "/folders", "/assets/x.js"} {
			expect(t, hs.do("GET", p, nil, opt), 404, "not_found")
		}
	}
	// "/" from the LAN is the gallery (the app, or a short page when the binary has no web build); through a proxy it
	// is not. Client-side routes get the same page.
	w := hs.do("GET", "/", nil, remote("100.100.1.2:4000"))
	if w.Code != 200 || !strings.Contains(w.Body.String(), "Hoard") || w.Header().Get("Content-Security-Policy") == "" {
		t.Fatalf("/: %d %q", w.Code, w.Body.String())
	}
	if w2 := hs.do("GET", "/folders/a/b", nil, webLocal()); w2.Code != 200 || w2.Body.String() != w.Body.String() {
		t.Fatalf("/folders/a/b: %d", w2.Code)
	}
	if _, built := webui.Files(); !built && !strings.Contains(w.Body.String(), "web gallery") {
		t.Fatalf("/: %d %q", w.Code, w.Body.String())
	}
	expect(t, hs.do("GET", "/", nil, remote("10.0.0.2:1"), header("X-Forwarded-For", "1.2.3.4")), 404, "not_found")

	// Signed out: the session says so and the API refuses.
	m := decode(t, hs.do("GET", "/web/session", nil, webLocal()))
	if m["signedIn"] != false || m["setup"] != false {
		t.Fatalf("session %v", m)
	}
	expect(t, hs.do("GET", "/web/library", nil, webLocal()), 401, "unauthorized")
	// Sign-in needs the CSRF header and a same-host Origin; a wrong password is refused.
	expect(t, hs.do("POST", "/web/session", map[string]any{"password": "a long admin password"}, webLocal()), 403, "forbidden")
	expect(t, hs.do("POST", "/web/session", map[string]any{"password": "a long admin password"}, webWrite(), header("Origin", "http://evil.test")), 403, "forbidden")
	expect(t, hs.do("POST", "/web/session", map[string]any{"password": "wrong password!"}, webWrite()), 401, "unauthorized")
	c := webSignIn(t, hs)
	m = decode(t, hs.do("GET", "/web/session", nil, webLocal(), withCookie(c)))
	if m["signedIn"] != true || !strings.HasPrefix(m["writer"].(string), "web:") || len(m["writer"].(string)) != 20 {
		t.Fatalf("session %v", m)
	}
	// The gallery cookie never opens the setup page.
	w = hs.do("GET", "/admin/api/status", nil, adminHeaders(), func(r *http.Request) {
		r.AddCookie(&http.Cookie{Name: sessionCookieName, Value: c.Value})
	})
	expect(t, w, 401, "unauthorized")
	// Changing the admin password ends gallery sessions.
	if err := hs.st.SetAdminPassword(t.Context(), "x"); err != nil {
		t.Fatal(err)
	}
	expect(t, hs.do("GET", "/web/library", nil, webLocal(), withCookie(c)), 401, "unauthorized")
}

func TestWebLibraryCullingAndFolders(t *testing.T) {
	hs, _, folderID := itemsHarness(t)
	c := webSignIn(t, hs)
	a := library(t, hs, c, "")
	if !a.Full || a.Total != 13 || len(a.Rows) != 13 || a.Folders[folderID] != "nas" {
		t.Fatalf("library: full %v total %d rows %d folders %v", a.Full, a.Total, len(a.Rows), a.Folders)
	}
	// Newest first; a pair is one row with its RAW as the main file; the camera reads as one name.
	if !sort.SliceIsSorted(a.Rows, func(i, j int) bool { return a.Rows[i].T > a.Rows[j].T }) {
		t.Fatal("not newest first")
	}
	var pair, plain libRow
	kinds := map[string]int{}
	for _, r := range a.Rows {
		kinds[r.K]++
		if r.K == "pair" && pair.ID == "" {
			pair = r
		}
		if r.K == "photo" && plain.ID == "" {
			plain = r
		}
	}
	if kinds["pair"] != 3 || kinds["motion"] != 1 || kinds["photo"] != 9 {
		t.Fatalf("kinds %v", kinds)
	}
	if !pair.Raw || !strings.HasSuffix(pair.N, ".ARW") || pair.Cam != "SONY ILCE-7M4" || pair.Fo != folderID || !strings.HasPrefix(pair.D, "2024-05-0") ||
		pair.W != 600 || pair.H != 400 {
		t.Fatalf("pair row %+v", pair)
	}
	// Nothing changed: an empty delta with the same cursor.
	d := library(t, hs, c, a.Cursor)
	if d.Full || len(d.Rows) != 0 || d.Cursor != a.Cursor {
		t.Fatalf("delta %+v", d)
	}
	// A pick from the gallery is a culling write by the session's writer and comes back as a changed row.
	w := hs.do("POST", "/web/culling", map[string]any{"changes": []map[string]any{
		{"itemId": plain.ID, "field": "flag", "value": "pick", "baseRev": 0, "modifiedAt": 1762851000000},
		{"itemId": plain.ID, "field": "rating", "value": 4, "baseRev": 0, "modifiedAt": 1762851000000},
	}}, webWrite(), withCookie(c))
	expect(t, w, 200, "")
	rec, err := hs.st.Culling(t.Context(), plain.ID)
	if err != nil || !strings.HasPrefix(rec.Fields["flag"].DeviceID, "web:") {
		t.Fatalf("culling %+v %v", rec, err)
	}
	expect(t, hs.do("POST", "/web/culling", map[string]any{"changes": []map[string]any{}}, webLocal(), withCookie(c)), 403, "forbidden")
	d = library(t, hs, c, a.Cursor)
	if len(d.Rows) != 1 || d.Rows[0].ID != plain.ID || d.Rows[0].X != "pick" || d.Rows[0].R != 4 || d.Rows[0].Fields == "" {
		t.Fatalf("delta after pick %+v", d.Rows)
	}
	// A cursor of another server start (or garbage) gets the whole index again.
	if full := library(t, hs, c, "zz:3"); !full.Full || len(full.Rows) != 13 {
		t.Fatal("foreign cursor not answered in full")
	}

	// Folders: the NAS folder with its directories and counts.
	m := decode(t, hs.do("GET", "/web/folders", nil, webLocal(), withCookie(c)))
	fs := m["folders"].([]any)
	f := fs[0].(map[string]any)
	if len(fs) != 1 || f["id"] != folderID || f["items"].(float64) != 13 || len(f["dirs"].([]any)) != 4 {
		t.Fatalf("folders %v", m)
	}

	// One item with its culling; exposure (none in these files); a file inline and as an attachment.
	m = decode(t, hs.do("GET", "/web/items/"+pair.ID, nil, webLocal(), withCookie(c)))
	item := m["item"].(map[string]any)
	if item["kind"] != "pair" || m["culling"] != nil || m["edited"] != false {
		t.Fatalf("item %v", m)
	}
	m = decode(t, hs.do("GET", "/web/items/"+pair.ID+"/exif", nil, webLocal(), withCookie(c)))
	if _, ok := m["exposure"].(map[string]any); !ok {
		t.Fatalf("exif %v", m)
	}
	var jpgFP, jpgName string
	for _, f := range item["files"].([]any) {
		ff := f.(map[string]any)
		if ff["role"] == "rendered" {
			jpgFP, jpgName = ff["fingerprint"].(string), ff["name"].(string)
		}
	}
	w = hs.do("GET", "/web/items/"+pair.ID+"/files/"+jpgFP+"?download=1", nil, webLocal(), withCookie(c))
	if w.Code != 200 || w.Header().Get("Content-Type") != "image/jpeg" || !strings.Contains(w.Header().Get("Content-Disposition"), "attachment") ||
		!strings.Contains(w.Header().Get("Content-Disposition"), jpgName) {
		t.Fatalf("file: %d %v", w.Code, w.Header())
	}
	expect(t, hs.do("GET", "/web/items/"+plain.ID+"/files/"+jpgFP, nil, webLocal(), withCookie(c)), 404, "not_found")

	// Zip: the pair's two files and the plain photo, names unique, through a link of this session only.
	w = hs.do("POST", "/web/downloads", map[string]any{"items": []string{pair.ID, plain.ID, pair.ID}}, webWrite(), withCookie(c))
	expect(t, w, 200, "")
	m = decode(t, w)
	if m["items"].(float64) != 2 || m["files"].(float64) != 3 {
		t.Fatalf("download %v", m)
	}
	url := m["url"].(string)
	other := webSignIn(t, hs)
	expect(t, hs.do("GET", url, nil, webLocal(), withCookie(other)), 404, "not_found")
	w = hs.do("GET", url, nil, webLocal(), withCookie(c))
	if w.Code != 200 || w.Header().Get("Content-Type") != "application/zip" {
		t.Fatalf("zip %d", w.Code)
	}
	zr, err := zip.NewReader(bytes.NewReader(w.Body.Bytes()), int64(w.Body.Len()))
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	for _, f := range zr.File {
		names = append(names, f.Name)
		if f.Method != zip.Store {
			t.Fatal("compressed entry")
		}
	}
	if len(names) != 3 {
		t.Fatalf("zip names %v", names)
	}
	expect(t, hs.do("POST", "/web/downloads", map[string]any{"items": []string{"nope"}}, webWrite(), withCookie(c)), 422, "invalid_request")
}

func TestZipNamesUnique(t *testing.T) {
	z := zipNames{}
	got := []string{z.unique("A.JPG"), z.unique("a.jpg"), z.unique("A.JPG"), z.unique("../x"), z.unique("")}
	want := []string{"A.JPG", "a (2).jpg", "A (3).JPG", "x", "file"}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("%v", got)
		}
	}
}

func TestCameraName(t *testing.T) {
	for _, c := range [][3]string{{"SONY", "ILCE-7M4", "SONY ILCE-7M4"}, {"Canon", "Canon EOS R5", "Canon EOS R5"},
		{"NIKON CORPORATION", "NIKON Z 6", "NIKON Z 6"}, {"", "Pixel 8", "Pixel 8"}, {"Apple", "", "Apple"}} {
		if got := cameraName(c[0], c[1]); got != c[2] {
			t.Fatalf("%q %q → %q", c[0], c[1], got)
		}
	}
}
