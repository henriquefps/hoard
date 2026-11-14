// Package auth holds reveal-sync's secrets: device API keys, pairing tokens, admin passwords and sessions.
package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"regexp"
	"strings"

	"golang.org/x/crypto/argon2"
)

// KeyPrefix starts every device API key.
const KeyPrefix = "rvk1_"

var keyPattern = regexp.MustCompile(`^rvk1_([0-9a-f]{16})_([A-Za-z0-9_-]{43})$`)

// ErrMalformedKey is returned for anything that is not a well-formed key.
var ErrMalformedKey = errors.New("malformed key")

// Random returns n random bytes.
func Random(n int) []byte {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic(err) // crypto/rand never fails on supported platforms
	}
	return b
}

// NewKey makes a device key: rvk1_<keyId: 64 random bits, hex>_<secret: 256 random bits, base64url>.
// It returns the full key (shown once), the key id and SHA-256 of the secret (stored).
func NewKey() (key, keyID string, secretHash []byte) {
	keyID = hex.EncodeToString(Random(8))
	secret := base64.RawURLEncoding.EncodeToString(Random(32))
	h := sha256.Sum256([]byte(secret))
	return KeyPrefix + keyID + "_" + secret, keyID, h[:]
}

// ParseKey splits a key into its id and the SHA-256 of its secret.
func ParseKey(key string) (keyID string, secretHash []byte, err error) {
	m := keyPattern.FindStringSubmatch(key)
	if m == nil {
		return "", nil, ErrMalformedKey
	}
	h := sha256.Sum256([]byte(m[2]))
	return m[1], h[:], nil
}

// Equal compares two hashes in constant time (the inputs are fixed-length digests).
func Equal(a, b []byte) bool { return len(a) == len(b) && subtle.ConstantTimeCompare(a, b) == 1 }

// NewToken returns a random 256-bit token (base64url) and its SHA-256: pairing tokens and admin sessions.
func NewToken() (token string, hash []byte) {
	token = base64.RawURLEncoding.EncodeToString(Random(32))
	return token, HashToken(token)
}

// HashToken is SHA-256 of a token.
func HashToken(token string) []byte {
	h := sha256.Sum256([]byte(token))
	return h[:]
}

// Argon2id parameters (ADR 0017 "Admin").
const (
	argonTime    = 3
	argonMemory  = 64 * 1024
	argonThreads = 2
	argonKeyLen  = 32
	saltLen      = 16
)

// HashPassword returns a PHC-format argon2id hash.
func HashPassword(password string) string {
	salt := Random(saltLen)
	key := argon2.IDKey([]byte(password), salt, argonTime, argonMemory, argonThreads, argonKeyLen)
	return fmt.Sprintf("$argon2id$v=%d$m=%d,t=%d,p=%d$%s$%s", argon2.Version, argonMemory, argonTime, argonThreads,
		base64.RawStdEncoding.EncodeToString(salt), base64.RawStdEncoding.EncodeToString(key))
}

// CheckPassword verifies a password against a PHC argon2id hash, in constant time for the comparison.
func CheckPassword(password, encoded string) bool {
	parts := strings.Split(encoded, "$")
	if len(parts) != 6 || parts[1] != "argon2id" {
		return false
	}
	var version int
	if _, err := fmt.Sscanf(parts[2], "v=%d", &version); err != nil || version != argon2.Version {
		return false
	}
	var memory, iterations uint32
	var threads uint8
	if _, err := fmt.Sscanf(parts[3], "m=%d,t=%d,p=%d", &memory, &iterations, &threads); err != nil {
		return false
	}
	if memory > 1<<21 || iterations > 64 || threads == 0 { // refuse absurd stored parameters
		return false
	}
	salt, err := base64.RawStdEncoding.DecodeString(parts[4])
	if err != nil {
		return false
	}
	want, err := base64.RawStdEncoding.DecodeString(parts[5])
	if err != nil || len(want) == 0 {
		return false
	}
	got := argon2.IDKey([]byte(password), salt, iterations, memory, threads, uint32(len(want))) //nolint:gosec // len(want) is a decoded hash: tiny
	return subtle.ConstantTimeCompare(got, want) == 1
}

// SetupCode is the first run's one-time code: 8 digits shown as "1234-5678".
func SetupCode() string {
	b := Random(8)
	digits := make([]byte, 8)
	for i := range digits {
		digits[i] = '0' + b[i]%10
	}
	return string(digits[:4]) + "-" + string(digits[4:])
}

// SameCode compares setup codes in constant time, ignoring a dash and spaces in the input.
func SameCode(input, code string) bool {
	clean := strings.NewReplacer("-", "", " ", "").Replace(input)
	want := strings.ReplaceAll(code, "-", "")
	a := sha256.Sum256([]byte(clean))
	b := sha256.Sum256([]byte(want))
	return subtle.ConstantTimeCompare(a[:], b[:]) == 1
}
