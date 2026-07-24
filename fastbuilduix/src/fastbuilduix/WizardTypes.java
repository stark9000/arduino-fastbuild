package fastbuilduix;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data model for the Board Wizard, mirroring the Go types in
 * board_wizard.go / board_wizard_cache.go so the JSON shape (and the cache
 * file itself) stays compatible with fastbuild's own -configure-board.
 *
 * Package-private on purpose (like their Go counterparts, which are
 * unexported structs) - nothing outside the wizard machinery needs these.
 */
final class WizardTypes {
    private WizardTypes() {
    }
}

/** e.g. id="esp8266:esp8266", name="ESP8266 Boards (3.0.2)". */
class InstalledPlatform {
    final String id;
    final String name;

    InstalledPlatform(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String toString() {
        return name + "  (" + id + ")";
    }
}

/** e.g. name="NodeMCU 1.0", fqbn="esp8266:esp8266:nodemcu". */
class BoardEntry {
    final String name;
    final String fqbn;

    BoardEntry(String name, String fqbn) {
        this.name = name;
        this.fqbn = fqbn;
    }

    public String toString() {
        return name;
    }
}

/** One selectable value within a board config option, e.g. "80MHz" / "160MHz" for CPU speed. */
class BoardConfigValue {
    final String value;
    final String valueLabel;
    final boolean selected;

    BoardConfigValue(String value, String valueLabel, boolean selected) {
        this.value = value;
        this.valueLabel = valueLabel;
        this.selected = selected;
    }

    public String toString() {
        return valueLabel;
    }
}

/** One board menu option (e.g. "CPU Frequency"), with its selectable values. */
class BoardConfigOption {
    final String option;
    final String optionLabel;
    final List<BoardConfigValue> values;

    BoardConfigOption(String option, String optionLabel, List<BoardConfigValue> values) {
        this.option = option;
        this.optionLabel = optionLabel;
        this.values = values;
    }
}

/**
 * What's persisted to board-wizard-cache.json - same shape/field names
 * (camelCase, matching the Go struct's `json:` tags) as fastbuild's own
 * cache file, and written to the same path, so the Java UI and the Go CLI's
 * -configure-board can share one cache transparently.
 */
class WizardCacheData {
    String signature = "";
    long generatedAtEpochMs = System.currentTimeMillis();
    List<InstalledPlatform> platforms = new ArrayList<InstalledPlatform>();
    List<BoardEntry> boards = new ArrayList<BoardEntry>();
    /** Keyed by base FQBN. An empty (non-null) list means "looked up, has no options". */
    Map<String, List<BoardConfigOption>> boardOptions = new LinkedHashMap<String, List<BoardConfigOption>>();
    /**
     * True once every board in `boards` has an entry in boardOptions. The
     * Java UI only ever fetches options lazily (per selected board), so this
     * is normally false unless every board happens to already be cached -
     * same meaning as in the Go cache, so a later Go-side bulk prefetch
     * still knows what's left to do.
     */
    boolean complete = false;
}
