package fastbuilduix;

/**
 * Settings that apply across every project, not just one fastbuild ".config"
 * file - currently the Arduino CLI executable and its arduino-cli.yaml. These
 * used to be entered separately on the Project tab and again on the Board
 * Wizard tab; now there's exactly one place to set them (the App Settings
 * tab), and everywhere else just displays/uses that value.
 *
 * Persisted as a small JSON file via AppSettingsStore - see
 * AppSettingsStore.defaultFile() for where.
 */
public class AppSettings {
    public String arduinoCliPath = "";
    public String arduinoCliYamlPath = "";
    /** Path to fastbuild(.exe) itself - needed to actually launch a build. */
    public String fastbuildExePath = "";
    /** Default cache root folder used to auto-fill new sketches that don't have their own yet. */
    public String defaultCacheRoot = "";
}
