package main

import (
	"strings"
	"testing"
)

func minimalValidConfig() string {
	return "arduinoCLI=/bin/true\n" +
		"sketch=/tmp/sketch.ino\n" +
		"fqbn=esp8266:esp8266:nodemcu\n"
}

func TestParseConfigMinimalValid(t *testing.T) {
	cfg, err := parseConfig(strings.NewReader(minimalValidConfig()))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.arduinoCLI != "/bin/true" || cfg.sketch != "/tmp/sketch.ino" || cfg.fqbn != "esp8266:esp8266:nodemcu" {
		t.Fatalf("required fields not parsed correctly: %+v", cfg)
	}
	// Defaults that should hold with nothing else specified.
	if cfg.depsMode != "regex" {
		t.Errorf("expected default depsMode=regex, got %q", cfg.depsMode)
	}
	if !cfg.hashLibraryHeaders || !cfg.hashToolchain {
		t.Errorf("expected hashLibraryHeaders/hashToolchain to default true")
	}
}

func TestParseConfigDepsModeGccIsPermanentAliasForDepfile(t *testing.T) {
	cfg, err := parseConfig(strings.NewReader(minimalValidConfig() + "depsMode=gcc\n"))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.depsMode != "depfile" {
		t.Fatalf("expected depsMode=gcc to normalize to \"depfile\", got %q", cfg.depsMode)
	}
}

func TestParseConfigDepsModeDepfileDirect(t *testing.T) {
	cfg, err := parseConfig(strings.NewReader(minimalValidConfig() + "depsMode=depfile\n"))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.depsMode != "depfile" {
		t.Fatalf("expected depsMode=depfile, got %q", cfg.depsMode)
	}
}

func TestParseConfigDepsModeInvalidValueErrors(t *testing.T) {
	_, err := parseConfig(strings.NewReader(minimalValidConfig() + "depsMode=bogus\n"))
	if err == nil {
		t.Fatal("expected an error for an invalid depsMode value")
	}
	if !strings.Contains(err.Error(), "depfile") || !strings.Contains(err.Error(), "regex") {
		t.Errorf("expected error to mention valid options, got: %v", err)
	}
}

func TestParseConfigExportConflictTypoErrorsInsteadOfSilentlyDefaulting(t *testing.T) {
	_, err := parseConfig(strings.NewReader(minimalValidConfig() + "exportConflict=overwrit\n"))
	if err == nil {
		t.Fatal("expected an error for a typo'd exportConflict value, not a silent fallback to 'ask'")
	}
}

func TestParseConfigExportConflictValidValues(t *testing.T) {
	cases := []struct {
		raw      string
		expected exportConflictDecision
	}{
		{"ask", exportConflictAsk},
		{"overwrite", exportConflictOverwrite},
		{"rename", exportConflictRename},
		{"", exportConflictAsk},
	}
	for _, c := range cases {
		cfg, err := parseConfig(strings.NewReader(minimalValidConfig() + "exportConflict=" + c.raw + "\n"))
		if err != nil {
			t.Fatalf("exportConflict=%q should be valid, got error: %v", c.raw, err)
		}
		if cfg.exportConflict != c.expected {
			t.Errorf("exportConflict=%q: expected %v, got %v", c.raw, c.expected, cfg.exportConflict)
		}
	}
}

func TestParseConfigMissingRequiredFieldErrors(t *testing.T) {
	_, err := parseConfig(strings.NewReader("arduinoCLI=/bin/true\nsketch=/tmp/x.ino\n")) // no fqbn
	if err == nil {
		t.Fatal("expected an error when a required field (fqbn) is missing")
	}
}

func TestParseConfigPlatformVersionPassthrough(t *testing.T) {
	cfg, err := parseConfig(strings.NewReader(minimalValidConfig() + "platformVersion=3.0.2\n"))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.platformVersion != "3.0.2" {
		t.Fatalf("expected platformVersion=3.0.2, got %q", cfg.platformVersion)
	}
}

func TestParseConfigDepsIndexMaxAgeHoursZeroDisablesCheck(t *testing.T) {
	cfg, err := parseConfig(strings.NewReader(minimalValidConfig() + "depsIndexMaxAgeHours=0\n"))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.depsIndexMaxAge != 0 {
		t.Fatalf("expected depsIndexMaxAge to be 0 (disabled), got %v", cfg.depsIndexMaxAge)
	}
}
