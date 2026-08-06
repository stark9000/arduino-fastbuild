package fastbuilduix;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs arduino-cli as a subprocess and captures its stdout as text - the
 * Java-side equivalent of runArduinoCLIText / fetchBoardConfigOptions's
 * exec.Command calls in board_wizard.go. Always called from a background
 * thread (SwingWorker), never the EDT, since these calls (core list, board
 * listall, board details) can take a real, noticeable moment.
 */
final class ArduinoCliRunner {

    private ArduinoCliRunner() {
    }

    static class CliException extends Exception {
        CliException(String message) {
            super(message);
        }

        CliException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Runs arduino-cli with the given args, prefixed with "--config-file
     * <configFile>" if configFile is non-blank (same convention as every
     * other arduino-cli call fastbuild makes). Returns stdout as text.
     * Throws CliException (with stderr folded into the message where
     * available) on a non-zero exit or a launch failure.
     */
    static String run(String arduinoCliPath, String configFile, String... args) throws CliException {
        List<String> command = new ArrayList<String>();
        command.add(arduinoCliPath);
        if (configFile != null && !configFile.trim().isEmpty()) {
            command.add("--config-file");
            command.add(configFile);
        }
        for (String arg : args) {
            command.add(arg);
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new CliException("could not start " + arduinoCliPath + ": " + e.getMessage(), e);
        }

        String stdout;
        String stderr;
        try {
            stdout = readAll(process.getInputStream());
            stderr = readAll(process.getErrorStream());
        } catch (IOException e) {
            throw new CliException("error reading arduino-cli output: " + e.getMessage(), e);
        }

        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CliException("interrupted while waiting for arduino-cli", e);
        }

        if (exitCode != 0) {
            String detail = stderr.trim().isEmpty() ? stdout.trim() : stderr.trim();
            throw new CliException("arduino-cli exited with code " + exitCode
                    + (detail.isEmpty() ? "" : ": " + detail));
        }
        return stdout;
    }

    private static String readAll(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        try {
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        } finally {
            reader.close();
        }
        return sb.toString();
    }
}
