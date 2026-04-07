package dev.mproctor.cccli;

import java.util.function.Consumer;

/**
 * State machine that routes NSTextField keystrokes to the PTY during slash command entry.
 *
 * States:
 *   NORMAL          — user types in NSTextField; text is submitted in full on Enter.
 *   SLASH_PASSTHROUGH — user typed "/"; every subsequent keystroke is forwarded
 *                      directly to the PTY so Claude Code's TUI handles display.
 *
 * Constructed with three side-effect functions so it is testable with plain JUnit.
 * Thread-safety: all methods are called on the AppKit main thread (ObjC upcalls).
 */
public class InputRouter {

    public enum Mode { NORMAL, SLASH_PASSTHROUGH }

    private final Consumer<String>  writeToPty;
    private final Consumer<Boolean> setSlashMode;
    private final Consumer<String>  setInputText;

    private Mode    mode             = Mode.NORMAL;
    private int     bufferCount      = 0;     // net chars still in PTY buffer since entering slash mode (0 = slash alone)

    public InputRouter(Consumer<String>  writeToPty,
                       Consumer<Boolean> setSlashMode,
                       Consumer<String>  setInputText) {
        this.writeToPty   = writeToPty;
        this.setSlashMode = setSlashMode;
        this.setInputText = setInputText;
    }

    /**
     * Called when NSTextField content changes (controlTextDidChange:).
     * Entry point: text exactly "/" triggers slash passthrough.
     * Ignored when already in SLASH_PASSTHROUGH (our own setInputText fires this).
     */
    public void onTextChanged(String text) {
        if (mode == Mode.SLASH_PASSTHROUGH) return;
        if ("/".equals(text)) {
            mode             = Mode.SLASH_PASSTHROUGH;
            bufferCount      = 0;
            writeToPty.accept("/");
            setSlashMode.accept(true);
            setInputText.accept("");
        }
    }

    /**
     * Called for each key intercepted by the NSEvent monitor in slash mode.
     * In NORMAL mode this is a no-op.
     */
    public void onKeyPressed(String chars) {
        if (mode != Mode.SLASH_PASSTHROUGH) return;

        switch (chars) {
            case " ", "\u001B" -> exitSlash(true);   // dismiss Claude Code's slash menu
            case "\r"          -> exitSlashEnter();
            case "\u007F"      -> handleBackspace();
            default            -> {
                writeToPty.accept(chars);
                bufferCount++;
            }
        }
    }

    public Mode getMode() { return mode; }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void exitSlash(boolean sendEscape) {
        if (sendEscape) writeToPty.accept("\u001B");
        mode        = Mode.NORMAL;
        bufferCount = 0;
        setSlashMode.accept(false);
        setInputText.accept("");
    }

    private void exitSlashEnter() {
        writeToPty.accept("\r");
        mode        = Mode.NORMAL;
        bufferCount = 0;
        setSlashMode.accept(false);
        // No setInputText — field is already empty
    }

    private void handleBackspace() {
        if (bufferCount > 0) {
            writeToPty.accept("\u007F");
            bufferCount--;
        } else {
            exitSlash(true);
        }
    }
}
