package fastbuilduix;

import com.fazecast.jSerialComm.SerialPort;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Manages one open serial connection for the Upload tab's monitor: opens the
 * port, runs a background reader thread that hands received text back to the
 * EDT via the given Listener, and supports writing outgoing text. One
 * instance per open connection - call close() when done, then discard it.
 */
final class SerialMonitorSession {

    interface Listener {
        /** Always delivered on the EDT. */
        void onDataReceived(String text);

        /** Always delivered on the EDT - the port closed, expectedly or not. */
        void onClosed(String reason);
    }

    private final SerialPort port;
    private final Listener listener;
    private volatile boolean closing = false;
    private Thread readerThread;

    private SerialMonitorSession(SerialPort port, Listener listener) {
        this.port = port;
        this.listener = listener;
    }

    static SerialMonitorSession open(String portName, int baudRate, Listener listener) throws IOException {
        SerialPort port = SerialPort.getCommPort(portName);
        port.setBaudRate(baudRate);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 0, 0);
        if (!port.openPort()) {
            throw new IOException("Could not open " + portName + " - it may already be in use by another program.");
        }
        SerialMonitorSession session = new SerialMonitorSession(port, listener);
        session.startReaderThread();
        return session;
    }

    private void startReaderThread() {
        readerThread = new Thread(new Runnable() {
            public void run() {
                InputStream in = port.getInputStream();
                byte[] buf = new byte[1024];
                try {
                    while (!closing) {
                        int n = in.read(buf);
                        if (closing) {
                            break;
                        }
                        if (n > 0) {
                            final String text = new String(buf, 0, n, StandardCharsets.UTF_8);
                            javax.swing.SwingUtilities.invokeLater(new Runnable() {
                                public void run() {
                                    listener.onDataReceived(text);
                                }
                            });
                        } else if (n < 0) {
                            break;
                        }
                    }
                } catch (IOException e) {
                    if (!closing) {
                        final String message = e.getMessage();
                        javax.swing.SwingUtilities.invokeLater(new Runnable() {
                            public void run() {
                                listener.onClosed("Read error: " + message);
                            }
                        });
                    }
                }
            }
        }, "serial-monitor-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /** Call from the EDT. */
    void send(String text) throws IOException {
        OutputStream out = port.getOutputStream();
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Call from the EDT. Safe to call more than once. */
    void close() {
        closing = true;
        port.closePort();
    }
}
