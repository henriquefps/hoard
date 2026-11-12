package server

import (
	"strings"
	"testing"
)

func TestAdminOnlyFromLocalNetwork(t *testing.T) {
	hs := newHarness(t, true)
	for _, addr := range []string{"127.0.0.1:1", "192.168.1.20:1", "10.0.0.5:1", "100.101.102.103:1", "[fd7a:115c:a1e0::1]:1", "[::1]:1"} {
		w := hs.do("GET", "/admin/api/state", nil, remote(addr))
		expect(t, w, 200, "")
	}
	for _, addr := range []string{"203.0.113.5:1", "8.8.8.8:1", "[2001:db8::1]:1"} {
		expect(t, hs.do("GET", "/admin/api/state", nil, remote(addr)), 404, "not_found")
		expect(t, hs.do("GET", "/admin/", nil, remote(addr)), 404, "not_found")
	}
	// Through a tunnel connector on the LAN: refused by its headers.
	for _, h := range []string{"Cf-Connecting-Ip", "X-Forwarded-For", "X-Real-Ip", "Forwarded"} {
		expect(t, hs.do("GET", "/admin/api/state", nil, remote("172.18.0.3:1"), header(h, "198.51.100.7")), 404, "not_found")
	}
	// The page itself, with its security headers.
	w := hs.do("GET", "/admin/", nil, remote("127.0.0.1:1"))
	if w.Code != 200 || !strings.Contains(w.Header().Get("Content-Security-Policy"), "frame-ancestors 'none'") ||
		!strings.Contains(w.Body.String(), "assets/app.js") {
		t.Fatalf("page %d %v", w.Code, w.Header())
	}
	if w := hs.do("GET", "/admin/assets/app.js", nil, remote("127.0.0.1:1")); w.Code != 200 {
		t.Fatalf("asset %d", w.Code)
	}
}

func TestAdminSetupLoginAndCSRF(t *testing.T) {
	hs := newHarness(t, true)
	if hs.srv.setupCode == "" || !strings.Contains(hs.logs.String(), hs.srv.setupCode) {
		t.Fatal("the first run prints a setup code")
	}
	expect(t, hs.do("POST", "/admin/api/setup", map[string]any{"code": hs.srv.setupCode, "password": "a long admin password"}, remote("127.0.0.1:1")), 403, "forbidden")
	expect(t, hs.do("POST", "/admin/api/setup", map[string]any{"code": hs.srv.setupCode, "password": "a long admin password"},
		adminHeaders(), header("Origin", "https://evil.example")), 403, "forbidden")
	expect(t, hs.do("POST", "/admin/api/setup", map[string]any{"code": "0000-0000", "password": "a long admin password"}, adminHeaders()), 401, "unauthorized")
	expect(t, hs.do("POST", "/admin/api/setup", map[string]any{"code": hs.srv.setupCode, "password": "short"}, adminHeaders()), 422, "invalid_request")
	expect(t, hs.do("GET", "/admin/api/devices", nil, adminHeaders()), 401, "unauthorized")
	hs.signIn()
	w := hs.do("POST", "/admin/api/setup", map[string]any{"code": "x", "password": "another long password"}, adminHeaders())
	expect(t, w, 409, "conflict")
	expect(t, hs.do("GET", "/admin/api/devices", nil, adminHeaders()), 200, "")
	if c := hs.cookie; !c.HttpOnly || c.SameSite != 3 || c.Path != "/admin" {
		t.Fatalf("cookie %+v", c)
	}
	// Mutations need the header even with a session.
	expect(t, hs.do("POST", "/admin/api/pairings", map[string]any{"url": "https://x.test"}, remote("127.0.0.1:1")), 403, "forbidden")
	// Logout ends the session; login brings it back; a wrong password does not.
	expect(t, hs.do("POST", "/admin/api/logout", nil, adminHeaders()), 200, "")
	expect(t, hs.do("GET", "/admin/api/devices", nil, adminHeaders()), 401, "unauthorized")
	saved := hs.cookie
	hs.cookie = nil
	expect(t, hs.do("POST", "/admin/api/login", map[string]any{"password": "not the password"}, adminHeaders()), 401, "unauthorized")
	w = hs.do("POST", "/admin/api/login", map[string]any{"password": "a long admin password"}, adminHeaders())
	expect(t, w, 200, "")
	hs.cookie = w.Result().Cookies()[0]
	expect(t, hs.do("GET", "/admin/api/devices", nil, adminHeaders()), 200, "")
	hs.cookie = saved
	expect(t, hs.do("GET", "/admin/api/devices", nil, adminHeaders()), 401, "unauthorized")
}

func TestAdminViaProxySetting(t *testing.T) {
	hs := newHarness(t, true)
	hs.signIn()
	tunnel := []reqOpt{remote("172.18.0.3:1"), header("Cf-Connecting-Ip", "198.51.100.7"), header("X-Reveal-Admin", "1")}
	expect(t, hs.do("GET", "/admin/api/status", nil, tunnel...), 404, "")
	expect(t, hs.do("PUT", "/admin/api/settings", map[string]any{"adminViaProxy": true}, adminHeaders()), 200, "")
	expect(t, hs.do("GET", "/admin/api/status", nil, tunnel...), 200, "")
	// It can only be turned off (or on) from the LAN, not through the tunnel.
	expect(t, hs.do("PUT", "/admin/api/settings", map[string]any{"adminViaProxy": false}, tunnel...), 403, "forbidden")
	expect(t, hs.do("PUT", "/admin/api/settings", map[string]any{"adminViaProxy": false}, adminHeaders()), 200, "")
	expect(t, hs.do("GET", "/admin/api/status", nil, tunnel...), 404, "")
}

func TestStorageChoiceAndLock(t *testing.T) {
	hs := newHarness(t, false)
	hs.signIn()
	w := hs.do("GET", "/admin/api/storage", nil, adminHeaders())
	expect(t, w, 200, "")
	m := decode(t, w)
	if m["available"] != false || len(m["candidates"].([]any)) != 2 {
		t.Fatalf("storage %v", m)
	}
	expect(t, hs.do("PUT", "/admin/api/storage", map[string]any{"path": hs.backup}, adminHeaders()), 200, "")
	key, _ := hs.pair("Phone")
	data := []byte("a preview")
	w = hs.do("POST", "/v1/uploads", map[string]any{"sha256": sum(data), "size": len(data), "kind": "preview"}, bearer(key))
	id := decode(t, w)["uploadId"].(string)
	expect(t, hs.do("PATCH", "/v1/uploads/"+id, data, bearer(key), header("Upload-Offset", "0"), header("Content-Type", "application/offset+octet-stream")), 204, "")
	// Blobs exist now: the backup root is fixed.
	expect(t, hs.do("PUT", "/admin/api/storage", map[string]any{"path": hs.nas}, adminHeaders()), 409, "conflict")
	expect(t, hs.do("PUT", "/admin/api/storage", map[string]any{"path": hs.backup}, adminHeaders()), 200, "")
	// The backup folder cannot be indexed as a NAS folder.
	expect(t, hs.do("POST", "/admin/api/nas", map[string]any{"path": hs.backup}, adminHeaders()), 422, "invalid_request")
}
