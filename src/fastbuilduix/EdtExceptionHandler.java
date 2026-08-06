package fastbuilduix;

import java.awt.IllegalComponentStateException;

/**
 * Installed via the "sun.awt.exception.handler" system property in
 * Main.main(). It's an old, semi-private mechanism, but it's still honored
 * by EventDispatchThread on Java 8: EventDispatchThread instantiates this
 * class with a no-arg constructor and calls handle(Throwable) for anything
 * that escapes normal Swing event processing, instead of just dumping a
 * stack trace to stderr and moving on.
 *
 * In particular this quietly absorbs the IllegalComponentStateException
 * ("component must be showing on the screen to determine its location")
 * that Windows' input-method support can throw from WInputMethod /
 * InputMethodContext.getTextLocation. That happens when a text field loses
 * "showing" state - e.g. switching JTabbedPane tabs, or a dialog closing -
 * while an input-method query for that field's on-screen location is still
 * in flight. It's a known benign JDK/Windows race, not a bug in this app:
 * nothing is corrupted, no build/config state is lost, it's just noisy.
 * Swallowing *only* this specific exception here stops it from looking like
 * a crash; anything else still gets printed so a real bug doesn't go silent.
 */
public class EdtExceptionHandler {

    public EdtExceptionHandler() {
    }

    public void handle(Throwable t) {
        if (t instanceof IllegalComponentStateException) {
            return; // benign Windows IME race - see class comment above
        }
        t.printStackTrace();
    }
}
