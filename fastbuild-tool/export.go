// export.go implements -export: copying the compiled binary into the sketch's own
// folder after a successful build (or a cache-hit skip), similar to the Arduino IDE's
// "Export Compiled Binary". Unlike arduino-cli's own --export-binaries flag (which
// copies unconditionally, silently overwriting whatever's there), this asks - or
// auto-renames - when a file of the same name already exists, so a previous export
// never disappears without you noticing.
package main

import (
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
)

// exportConflictDecision controls what happens when the destination file already
// exists. Mirrors staleIndexDecision in deps.go - same three-way shape, same
// headless-safe fallback via isInteractiveStdin().
type exportConflictDecision int

const (
	// exportConflictAsk prompts on stdin: overwrite, or auto-rename instead. The
	// default. Falls back to exportConflictRename (never silently overwrites) when
	// stdin isn't an interactive terminal - see isInteractiveStdin() in deps.go.
	exportConflictAsk exportConflictDecision = iota
	// exportConflictOverwrite replaces the existing file without asking.
	exportConflictOverwrite
	// exportConflictRename picks a free name (appending _1, _2, ...) without asking,
	// leaving the existing file untouched.
	exportConflictRename
)

// exportBinary copies outputFile into the same directory as the sketch, applying
// cfg's conflict policy if a file of that name already exists there. Returns the
// final destination path actually written.
func exportBinary(cfg *config, outputFile string) (string, error) {
	if outputFile == "" {
		return "", fmt.Errorf("export requested but no build output is available")
	}

	sketchDir := filepath.Dir(cfg.sketch)
	destName := filepath.Base(outputFile)
	dest := filepath.Join(sketchDir, destName)

	if _, err := os.Stat(dest); err == nil {
		// Destination already exists - resolve per policy before copying.
		switch cfg.exportConflict {
		case exportConflictOverwrite:
			fmt.Println("Export: overwriting existing", dest)
		case exportConflictRename:
			dest = nextAvailableName(dest)
			fmt.Println("Export: existing file kept, writing to", dest)
		default: // exportConflictAsk
			if !isInteractiveStdin() {
				dest = nextAvailableName(dest)
				fmt.Println("Export: destination exists and stdin isn't interactive - auto-renaming to", dest, "(pass -export-conflict=overwrite to replace it instead)")
				break
			}
			question := fmt.Sprintf("Export destination already exists: %s\nOverwrite it? [y/N]: ", dest)
			if promptYesNo(question) {
				fmt.Println("Export: overwriting", dest)
			} else {
				dest = nextAvailableName(dest)
				fmt.Println("Export: keeping existing file, writing to", dest)
			}
		}
	}

	if err := copyFile(outputFile, dest); err != nil {
		return "", err
	}
	return dest, nil
}

// nextAvailableName finds a free filename by inserting _1, _2, ... before the
// extension until one doesn't exist yet, e.g. "sketch.ino.bin" -> "sketch.ino_2.bin"
// if "sketch.ino.bin" and "sketch.ino_1.bin" are both already taken.
func nextAvailableName(path string) string {
	dir := filepath.Dir(path)
	base := filepath.Base(path)
	ext := filepath.Ext(base)
	stem := strings.TrimSuffix(base, ext)

	for i := 1; ; i++ {
		candidate := filepath.Join(dir, fmt.Sprintf("%s_%d%s", stem, i, ext))
		if _, err := os.Stat(candidate); os.IsNotExist(err) {
			return candidate
		}
	}
}

// copyFile does a straightforward streamed copy - no os.Rename shortcut, since src
// and dest may be on different volumes (the persistent cache root and the sketch
// folder are independent, user-configurable paths).
func copyFile(src, dest string) error {
	in, err := os.Open(src)
	if err != nil {
		return fmt.Errorf("opening %s: %w", src, err)
	}
	defer in.Close()

	out, err := os.Create(dest)
	if err != nil {
		return fmt.Errorf("creating %s: %w", dest, err)
	}
	defer out.Close()

	if _, err := io.Copy(out, in); err != nil {
		return fmt.Errorf("copying to %s: %w", dest, err)
	}
	return out.Close()
}
