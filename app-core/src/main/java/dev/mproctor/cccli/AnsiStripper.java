package dev.mproctor.cccli;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strips ANSI/VT100 escape sequences from PTY output for display in NSTextView.
 *
 * NSTextView has no terminal emulation — escape codes appear as literal
 * characters. This class strips them so the text content is readable.
 *
 * Claude CLI uses ESC[NC (cursor-forward N) for word spacing in its TUI
 * prompts. These are converted to N spaces before stripping so words remain
 * separated. All other escape sequences are removed.
 *
 * Used in NSTextView mode only. WKWebView/xterm.js handles ANSI natively.
 */
public final class AnsiStripper {

    /**
     * ESC[NC or ESC[C — cursor forward N columns (default 1).
     * Claude CLI uses this for spacing between words in TUI prompts.
     * Captured separately so we can replace with spaces rather than strip.
     */
    private static final Pattern CURSOR_FORWARD = Pattern.compile(
            "\u001B\\[([0-9]*)C"
    );

    /**
     * Matches everything else:
     *   OSC sequences    ESC ] <text> BEL          e.g. ESC]0;window title BEL
     *   CSI sequences    ESC [ <params> <letter>   e.g. ESC[1;32m, ESC[2K, ESC[?25l
     *   Other escapes    ESC <single non-[ char>   e.g. ESC= ESC> ESC7 ESC8 ESC M
     *   Bare CR          \r not followed by \n     e.g. spinner overwrite
     *
     * OSC must be checked before Other since Other also matches ESC.
     */
    private static final Pattern ANSI = Pattern.compile(
            "\u001B\\][^\u0007]*\u0007"         // OSC: ESC ] text BEL
            + "|\u001B\\[[0-9;:?]*[A-Za-z]"    // CSI: ESC [ params letter
            + "|\u001B[A-Za-z0-9=>?]"           // Other: ESC + single VT100 char
            + "|\r(?!\n)"                        // Bare CR not followed by LF
    );

    /**
     * Strips ANSI escape sequences and normalises CRLF to LF.
     * Cursor-forward sequences (ESC[NC) are converted to N spaces.
     * Returns the plain-text content suitable for NSTextView display.
     */
    public static String strip(String text) {
        if (text == null) return "";
        // 1. Normalise CRLF → LF
        String s = text.replace("\r\n", "\n");
        // 2. Convert cursor-forward to spaces (preserves word spacing)
        s = replaceCursorForward(s);
        // 3. Strip all remaining ANSI sequences
        return ANSI.matcher(s).replaceAll("");
    }

    private static String replaceCursorForward(String s) {
        Matcher m = CURSOR_FORWARD.matcher(s);
        if (!m.find()) return s;
        StringBuilder sb = new StringBuilder();
        m.reset();
        while (m.find()) {
            String countStr = m.group(1);
            int count = countStr.isEmpty() ? 1 : Integer.parseInt(countStr);
            m.appendReplacement(sb, " ".repeat(count));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private AnsiStripper() {}
}
