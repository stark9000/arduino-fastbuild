package main

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func setupPlatformDir(t *testing.T, dataDir, pkg, arch, version string) string {
	t.Helper()
	dir := filepath.Join(dataDir, "packages", pkg, "hardware", arch, version)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "boards.txt"), []byte("placeholder"), 0o644); err != nil {
		t.Fatal(err)
	}
	return dir
}

func TestComputeInstalledPlatformsSignatureStableWhenNothingChanges(t *testing.T) {
	dataDir := t.TempDir()
	setupPlatformDir(t, dataDir, "esp8266", "esp8266", "3.0.2")

	sig1, err := computeInstalledPlatformsSignature(dataDir)
	if err != nil {
		t.Fatal(err)
	}
	sig2, err := computeInstalledPlatformsSignature(dataDir)
	if err != nil {
		t.Fatal(err)
	}
	if sig1 != sig2 {
		t.Fatalf("expected identical signature with nothing changed, got %s vs %s", sig1, sig2)
	}
}

func TestComputeInstalledPlatformsSignatureChangesWhenBoardsTxtEdited(t *testing.T) {
	dataDir := t.TempDir()
	dir := setupPlatformDir(t, dataDir, "esp8266", "esp8266", "3.0.2")

	sig1, err := computeInstalledPlatformsSignature(dataDir)
	if err != nil {
		t.Fatal(err)
	}

	// Editing boards.txt in place (same version folder, content changed) must
	// invalidate the signature - this is exactly the case the mtime check exists for,
	// since the version folder name itself doesn't change.
	time.Sleep(1100 * time.Millisecond) // ensure a distinct mtime at 1-second resolution filesystems
	if err := os.WriteFile(filepath.Join(dir, "boards.txt"), []byte("edited content"), 0o644); err != nil {
		t.Fatal(err)
	}

	sig2, err := computeInstalledPlatformsSignature(dataDir)
	if err != nil {
		t.Fatal(err)
	}
	if sig1 == sig2 {
		t.Fatal("expected signature to change after editing boards.txt, but it didn't")
	}
}

func TestComputeInstalledPlatformsSignatureChangesWhenPlatformAdded(t *testing.T) {
	dataDir := t.TempDir()
	setupPlatformDir(t, dataDir, "esp8266", "esp8266", "3.0.2")

	sig1, err := computeInstalledPlatformsSignature(dataDir)
	if err != nil {
		t.Fatal(err)
	}

	setupPlatformDir(t, dataDir, "arduino", "avr", "1.8.6")

	sig2, err := computeInstalledPlatformsSignature(dataDir)
	if err != nil {
		t.Fatal(err)
	}
	if sig1 == sig2 {
		t.Fatal("expected signature to change after installing an additional platform")
	}
}

func TestComputeInstalledPlatformsSignatureNoPlatformsErrors(t *testing.T) {
	dataDir := t.TempDir()
	_, err := computeInstalledPlatformsSignature(dataDir)
	if err == nil {
		t.Fatal("expected an error when no platforms are installed at all")
	}
}

func TestWizardCacheSaveLoadRoundTrip(t *testing.T) {
	cachePath := filepath.Join(t.TempDir(), "board-wizard-cache.json")
	sig := "abc123"

	original := &wizardCacheData{
		Signature:   sig,
		GeneratedAt: time.Now(),
		Platforms:   []installedPlatform{{ID: "esp8266:esp8266", Name: "ESP8266 Boards"}},
		Boards:      []boardEntry{{FQBN: "esp8266:esp8266:nodemcuv2", Name: "NodeMCU 1.0"}},
	}
	saveWizardCache(cachePath, original)

	loaded, ok := loadWizardCache(cachePath, sig)
	if !ok {
		t.Fatal("expected a cache hit with the matching signature")
	}
	if len(loaded.Platforms) != 1 || loaded.Platforms[0].ID != "esp8266:esp8266" {
		t.Fatalf("platforms didn't survive the round trip: %+v", loaded.Platforms)
	}
	if len(loaded.Boards) != 1 || loaded.Boards[0].FQBN != "esp8266:esp8266:nodemcuv2" {
		t.Fatalf("boards didn't survive the round trip: %+v", loaded.Boards)
	}
}

func TestWizardCacheMismatchedSignatureIsCacheMiss(t *testing.T) {
	cachePath := filepath.Join(t.TempDir(), "board-wizard-cache.json")
	saveWizardCache(cachePath, &wizardCacheData{Signature: "old-signature", GeneratedAt: time.Now()})

	_, ok := loadWizardCache(cachePath, "new-signature")
	if ok {
		t.Fatal("expected a cache miss when the signature doesn't match")
	}
}

func TestWizardCacheMissingFileIsCacheMiss(t *testing.T) {
	cachePath := filepath.Join(t.TempDir(), "does-not-exist.json")
	_, ok := loadWizardCache(cachePath, "any-signature")
	if ok {
		t.Fatal("expected a cache miss when the file doesn't exist")
	}
}
