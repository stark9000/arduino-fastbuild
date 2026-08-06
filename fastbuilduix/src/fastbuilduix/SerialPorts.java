package fastbuilduix;

import com.fazecast.jSerialComm.SerialPort;

import java.util.ArrayList;
import java.util.List;

/**
 * Thin wrapper around jSerialComm (the only serial library actually wired
 * into the project's classpath - see nbproject/project.properties) for
 * listing available ports. Kept in one small file so jSerialComm's API is
 * only ever referenced here, not scattered through SettingsFrame.
 */
final class SerialPorts {

    private SerialPorts() {
    }

    /** System port names only, e.g. "COM3" - what gets stored in BuildSettings.port. */
    static List<String> listPortNames() {
        List<String> names = new ArrayList<String>();
        for (SerialPort port : SerialPort.getCommPorts()) {
            names.add(port.getSystemPortName());
        }
        return names;
    }

    /** "COM3 - USB-SERIAL CH340" style descriptions, same order as listPortNames(), for a nicer combo box display. */
    static List<String> listPortDescriptions() {
        List<String> descriptions = new ArrayList<String>();
        for (SerialPort port : SerialPort.getCommPorts()) {
            String descriptive = port.getDescriptivePortName();
            if (descriptive == null || descriptive.trim().isEmpty() || descriptive.equals(port.getSystemPortName())) {
                descriptions.add(port.getSystemPortName());
            } else {
                descriptions.add(port.getSystemPortName() + " - " + descriptive);
            }
        }
        return descriptions;
    }
}
