package server

import (
	"os"
	"regexp"
	"strings"
	"testing"
)

// TestOpenAPIListsEveryRoute keeps api/openapi.yaml and the router in step: every /v1 route (method and path) is
// documented, and every documented path exists.
func TestOpenAPIListsEveryRoute(t *testing.T) {
	spec, err := os.ReadFile("../../api/openapi.yaml")
	if err != nil {
		t.Fatal(err)
	}
	text := string(spec)
	src, err := os.ReadFile("api.go")
	if err != nil {
		t.Fatal(err)
	}
	routes := regexp.MustCompile(`mux\.HandleFunc\("(\w+) (/v1/[^"]*)"`).FindAllStringSubmatch(string(src), -1)
	if len(routes) < 10 {
		t.Fatalf("found %d routes", len(routes))
	}
	documented := map[string]bool{}
	for _, m := range regexp.MustCompile(`(?m)^  (/v1/\S+):$`).FindAllStringSubmatch(text, -1) {
		documented[m[1]] = true
	}
	routed := map[string]bool{}
	for _, r := range routes {
		path := strings.Replace(r[2], "{id}", "{uploadId}", 1)
		routed[path] = true
		if !documented[path] {
			t.Errorf("%s %s is not in openapi.yaml", r[1], path)
			continue
		}
		block := text[strings.Index(text, "  "+path+":\n"):]
		if next := regexp.MustCompile(`(?m)^  /v1/`).FindAllStringIndex(block, 2); len(next) == 2 {
			block = block[:next[1][0]]
		}
		if !strings.Contains(block, "\n    "+strings.ToLower(r[1])+":") {
			t.Errorf("%s %s: method missing in openapi.yaml", r[1], path)
		}
	}
	for p := range documented {
		if !routed[p] {
			t.Errorf("openapi.yaml documents %s, which the server does not route", p)
		}
	}
}
