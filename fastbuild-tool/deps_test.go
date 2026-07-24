package main

import (
	"crypto/sha256"
	"encoding/hex"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestCompareVersions(t *testing.T) {
	cases := []struct {
		a, b string
		want int // -1, 0, or 1 (sign only is checked)
	}{
		{"3.9.0", "3.10.0", -1}, // the actual bug this fixes: lexical sort gets this backwards
		{"3.10.0", "3.9.0", 1},
		{"3.0.2", "3.0.2", 0},
		{"1.8.6", "1.8.13", -1},
		{"2.0.0", "1.9.9", 1},
		{"3.0", "3.0.0", 0}, // shorter segment list, missing trailing zero segments
		{"3.0.0-rc1", "3.0.0-rc1", 0},
	}
	for _, c := range cases {
		got := compareVersions(c.a, c.b)
		gotSign := sign(got)
		wantSign := sign(c.want)
		if gotSign != wantSign {
			t.Errorf("compareVersions(%q, %q) = %d (sign %d), want sign %d", c.a, c.b, got, gotSign, wantSign)
		}
	}
}

func sign(n int) int {
	switch {
	case n < 0:
		return -1
	case n > 0:
		return 1
	default:
		return 0
	}
}

func TestResolvePlatformDirAutoPicksHighestBySemverNotLexically(t *testing.T) {
	dataDir := t.TempDir()
	base := filepath.Join(dataDir, "packages", "esp8266", "hardware", "esp8266")
	for _, v := range []string{"3.2.0", "3.9.0", "3.10.0"} {
		if err := os.MkdirAll(filepath.Join(base, v), 0o755); err != nil {
			t.Fatal(err)
		}
	}

	got, err := resolvePlatformDir(dataDir, "esp8266:esp8266:nodemcu", "")
	if err != nil {
		t.Fatal(err)
	}
	want := filepath.Join(base, "3.10.0")
	if got != want {
		t.Fatalf("expected auto-selection to pick the true highest version %q, got %q (lexical sort would have wrongly picked 3.9.0)", want, got)
	}
}

func TestResolvePlatformDirPinnedVersion(t *testing.T) {
	dataDir := t.TempDir()
	base := filepath.Join(dataDir, "packages", "esp8266", "hardware", "esp8266")
	for _, v := range []string{"3.2.0", "3.9.0", "3.10.0"} {
		if err := os.MkdirAll(filepath.Join(base, v), 0o755); err != nil {
			t.Fatal(err)
		}
	}

	got, err := resolvePlatformDir(dataDir, "esp8266:esp8266:nodemcu", "3.2.0")
	if err != nil {
		t.Fatal(err)
	}
	want := filepath.Join(base, "3.2.0")
	if got != want {
		t.Fatalf("expected pinned version to be used exactly, got %q want %q", got, want)
	}
}

func TestResolvePlatformDirPinnedVersionNotInstalledErrorsClearly(t *testing.T) {
	dataDir := t.TempDir()
	base := filepath.Join(dataDir, "packages", "esp8266", "hardware", "esp8266")
	if err := os.MkdirAll(filepath.Join(base, "3.2.0"), 0o755); err != nil {
		t.Fatal(err)
	}

	_, err := resolvePlatformDir(dataDir, "esp8266:esp8266:nodemcu", "9.9.9")
	if err == nil {
		t.Fatal("expected an error for a pinned version that isn't installed")
	}
	if !strings.Contains(err.Error(), "9.9.9") || !strings.Contains(err.Error(), "3.2.0") {
		t.Fatalf("expected error to mention both the missing pin and what IS installed, got: %v", err)
	}
}

func TestResolvePlatformDirNoPlatformsInstalled(t *testing.T) {
	dataDir := t.TempDir()
	_, err := resolvePlatformDir(dataDir, "esp8266:esp8266:nodemcu", "")
	if err == nil {
		t.Fatal("expected an error when no platform is installed at all")
	}
}

func TestHashFileListDeterministicRegardlessOfInputOrder(t *testing.T) {
	dir := t.TempDir()
	var paths []string
	for i, name := range []string{"c.h", "a.h", "b.h"} {
		p := filepath.Join(dir, name)
		if err := os.WriteFile(p, []byte(name+" content"), 0o644); err != nil {
			t.Fatal(err)
		}
		paths = append(paths, p)
		_ = i
	}

	h1 := sha256.New()
	count1, err := hashFileList(h1, paths)
	if err != nil {
		t.Fatal(err)
	}

	// Same paths, different input order - hashFileList sorts internally, so the
	// resulting hash must be identical either way.
	reversed := []string{paths[2], paths[0], paths[1]}
	h2 := sha256.New()
	count2, err := hashFileList(h2, reversed)
	if err != nil {
		t.Fatal(err)
	}

	if count1 != count2 || count1 != 3 {
		t.Fatalf("expected both counts to be 3, got %d and %d", count1, count2)
	}
	sum1 := hex.EncodeToString(h1.Sum(nil))
	sum2 := hex.EncodeToString(h2.Sum(nil))
	if sum1 != sum2 {
		t.Fatalf("expected identical hash regardless of input order, got %s vs %s", sum1, sum2)
	}
}

func TestHashFileListSkipsUnreadablePathsWithoutFailing(t *testing.T) {
	dir := t.TempDir()
	realPath := filepath.Join(dir, "real.h")
	if err := os.WriteFile(realPath, []byte("content"), 0o644); err != nil {
		t.Fatal(err)
	}
	missingPath := filepath.Join(dir, "does-not-exist.h")

	h := sha256.New()
	count, err := hashFileList(h, []string{realPath, missingPath})
	if err != nil {
		t.Fatal(err)
	}
	if count != 1 {
		t.Fatalf("expected count of 1 (only the readable file), got %d", count)
	}
}

func TestPlatformCacheKeyDistinguishesVersions(t *testing.T) {
	// Two different resolved versions of the same platform must get different cache
	// keys - otherwise an arduino-cli core upgrade, or switching -platform-version,
	// would silently keep reusing another version's (potentially wrong) header index.
	// See platformCacheKey's doc comment for the full story.
	a := platformCacheKey("esp8266:esp8266:nodemcu", "3.0.2")
	b := platformCacheKey("esp8266:esp8266:nodemcu", "3.1.0")
	if a == b {
		t.Fatalf("expected different cache keys for different versions, both got %q", a)
	}

	// Same fqbn+version must still be stable/deterministic across calls.
	again := platformCacheKey("esp8266:esp8266:nodemcu", "3.0.2")
	if a != again {
		t.Fatalf("expected platformCacheKey to be deterministic, got %q then %q", a, again)
	}

	// Different platforms must still get different keys too (the original guarantee,
	// from before version was added).
	c := platformCacheKey("esp32:esp32:esp32", "3.0.2")
	if a == c {
		t.Fatalf("expected different cache keys for different platforms, both got %q", a)
	}
}
