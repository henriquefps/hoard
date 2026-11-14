package auth

import (
	"strings"
	"testing"
)

func TestKeyRoundTrip(t *testing.T) {
	key, id, hash := NewKey()
	if !strings.HasPrefix(key, KeyPrefix) || len(key) != len(KeyPrefix)+16+1+43 {
		t.Fatalf("unexpected key shape %d", len(key))
	}
	gotID, gotHash, err := ParseKey(key)
	if err != nil || gotID != id || !Equal(gotHash, hash) {
		t.Fatalf("parse: %v", err)
	}
	other, _, _ := NewKey()
	if other == key {
		t.Fatal("keys must be random")
	}
}

func TestParseKeyRejectsMalformed(t *testing.T) {
	key, _, _ := NewKey()
	for _, bad := range []string{"", "rvk1_", key + "x", key[:len(key)-1], strings.Replace(key, "rvk1", "rvk2", 1),
		strings.ToUpper(key[:21]) + key[21:], "Bearer " + key, key[:5] + "../../../" + key[14:]} {
		if _, _, err := ParseKey(bad); err == nil {
			t.Fatalf("%q must be rejected", bad)
		}
	}
}

func TestEqualIsLengthSafe(t *testing.T) {
	if Equal([]byte("abc"), []byte("abcd")) || Equal(nil, []byte{}) == false || !Equal([]byte{1, 2}, []byte{1, 2}) {
		t.Fatal("Equal")
	}
}

func TestPassword(t *testing.T) {
	h := HashPassword("correct horse battery")
	if !strings.HasPrefix(h, "$argon2id$v=19$m=65536,t=3,p=2$") {
		t.Fatal(h)
	}
	if !CheckPassword("correct horse battery", h) || CheckPassword("correct horse batterY", h) || CheckPassword("", h) {
		t.Fatal("check")
	}
	if a, b := HashPassword("x"), HashPassword("x"); a == b {
		t.Fatal("salted hashes must differ")
	}
	for _, bad := range []string{"", "$argon2i$v=19$m=65536,t=3,p=2$AAAA$AAAA", "$argon2id$v=19$m=999999999,t=3,p=2$AAAA$AAAA"} {
		if CheckPassword("x", bad) {
			t.Fatal(bad)
		}
	}
}

func TestSetupCode(t *testing.T) {
	c := SetupCode()
	if len(c) != 9 || c[4] != '-' {
		t.Fatal(c)
	}
	if !SameCode(c, c) || !SameCode(strings.ReplaceAll(c, "-", ""), c) || !SameCode(c[:4]+" "+c[5:], c) || SameCode("0000-0000x", c) {
		t.Fatal("SameCode")
	}
}

func TestTokens(t *testing.T) {
	tok, h := NewToken()
	if len(tok) != 43 || !Equal(HashToken(tok), h) {
		t.Fatal("token")
	}
}
