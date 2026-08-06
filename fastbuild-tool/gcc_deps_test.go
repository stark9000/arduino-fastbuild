package main

import (
	"os"
	"path/filepath"
	"sort"
	"testing"
)

func TestParseGccDepFileHandlesLineContinuationsAndEscapedSpaces(t *testing.T) {
	dir := t.TempDir()
	depFile := filepath.Join(dir, "sketch.ino.cpp.d")
	content := "sketch/sketch.ino.cpp.o: /a/sketch.ino \\\n" +
		" /a/FastLED.h \\\n" +
		" /a/pixeltypes.h \\\n" +
		` C:\Users\saliya\Documents\Arduino\libraries\Some\ Lib\SomeLib.h` + " \\\n" +
		" /a/SPI.h\n"
	if err := os.WriteFile(depFile, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}

	deps, err := parseGccDepFile(depFile)
	if err != nil {
		t.Fatal(err)
	}
	if len(deps) != 5 {
		t.Fatalf("expected 5 dependencies (source + 4 headers), got %d: %v", len(deps), deps)
	}

	foundEscapedSpacePath := false
	for _, d := range deps {
		if d == `C:\Users\saliya\Documents\Arduino\libraries\Some Lib\SomeLib.h` {
			foundEscapedSpacePath = true
		}
	}
	if !foundEscapedSpacePath {
		t.Fatalf("expected the escaped-space Windows path to be correctly unescaped, got: %v", deps)
	}
}

func TestParseGccDepFileNoPrerequisites(t *testing.T) {
	dir := t.TempDir()
	depFile := filepath.Join(dir, "empty.d")
	if err := os.WriteFile(depFile, []byte("target.o:\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	deps, err := parseGccDepFile(depFile)
	if err != nil {
		t.Fatal(err)
	}
	if len(deps) != 0 {
		t.Fatalf("expected zero dependencies for a target with none listed, got %v", deps)
	}
}

func TestFindSketchDepFilesHarvestsEveryLocalTranslationUnit(t *testing.T) {
	buildPath := t.TempDir()
	sketchDir := filepath.Join(buildPath, "sketch")
	if err := os.MkdirAll(sketchDir, 0o755); err != nil {
		t.Fatal(err)
	}

	// Simulates a sketch with main.ino + a hand-written foo.cpp - each its own
	// translation unit with its own separate .d file. foo_only_header.h is reachable
	// ONLY through foo.cpp's own #include graph, never through the .ino at all - this
	// is exactly the gap that harvesting only the .ino's own .d file would miss.
	writeFile(t, filepath.Join(sketchDir, "sketch.ino.cpp.d"), "sketch/sketch.ino.cpp.o: sketch.ino\n")
	writeFile(t, filepath.Join(sketchDir, "foo.cpp.d"), "sketch/foo.cpp.o: foo.cpp foo_only_header.h\n")

	depFiles, err := findSketchDepFiles(buildPath)
	if err != nil {
		t.Fatal(err)
	}
	if len(depFiles) != 2 {
		t.Fatalf("expected 2 .d files (one per translation unit), got %d: %v", len(depFiles), depFiles)
	}

	// Union every dependency across both files, the same way harvestGccDeps does.
	seen := map[string]bool{}
	var all []string
	for _, df := range depFiles {
		deps, err := parseGccDepFile(df)
		if err != nil {
			t.Fatal(err)
		}
		for _, d := range deps {
			if !seen[d] {
				seen[d] = true
				all = append(all, d)
			}
		}
	}
	sort.Strings(all)

	foundFooHeader := false
	for _, d := range all {
		if d == "foo_only_header.h" {
			foundFooHeader = true
		}
	}
	if !foundFooHeader {
		t.Fatalf("expected foo_only_header.h (reachable only via foo.cpp, not the .ino) to be present in the union, got: %v", all)
	}
}

func TestFindSketchDepFilesNoDepFilesYet(t *testing.T) {
	buildPath := t.TempDir()
	// sketch/ subdirectory doesn't even exist yet - the bootstrap case (no compile
	// has happened yet under this build path).
	depFiles, err := findSketchDepFiles(buildPath)
	if err != nil {
		t.Fatal(err)
	}
	if len(depFiles) != 0 {
		t.Fatalf("expected zero .d files when the sketch build dir doesn't exist, got %v", depFiles)
	}
}

func writeFile(t *testing.T, path, content string) {
	t.Helper()
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}
