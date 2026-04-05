package dev.mproctor.cccli;

import java.util.regex.Pattern;

/**
 * Strips ANSI/VT100 escape sequences from PTY output for display in NSTextView.
 *
 * NSTextView has no terminal emulation — escape codes appear as literal
 * characters. This class strips them so the text content is readable.
 *
 * Used in development (NSTextView) mode only. Plan 5b routes bytes to
 * xterm.js which handles ANSI natively and does not use this class.
 */
public final class AnsiStripper {

    /**
     * Matches:
     *   OSC sequences    ESC ] <text> BEL          e.g. ESC]0;window title BEL
     *   CSI sequences    ESC [ <params> <letter>   e.g. ESC[1;32m, ESC[2K, ESC[?25l
     *   Other escapes    ESC <single non-[ char>   e.g. ESC= ESC> ESC7 ESC8 ESC M
     *   Bare CR          \r not followed by \n     e.g. spinner overwrite
     *
     * NOTE: OSC must be checked before Other, since Other also matches ESC.
     */
    private static final Pattern ANSI = Pattern.compile(
            "\u001B\\][^\u0007]*\u0007"    // OSC: ESC ] text BEL (must be first!)
            + "|\u001B\\[[0-9;:?]*[A-Za-z]"    // CSI: ESC [ params letter
            + "|\u001B[^\\[\u001B\r\n]"       // Other: ESC + single char (not [, ESC, newlines)
            + "|\r(?!\n)"                     // Bare CR not followed by LF
    );

    /**
     * Strips ANSI escape sequences and normalises CRLF to LF.
     * Returns the plain-text content suitable for NSTextView display.
     */
    public static String strip(String text) {
        // Normalise CRLF → LF first, then strip remaining control sequences
        return ANSI.matcher(text.replace("\r\n", "\n")).replaceAll("");
    }

    private AnsiStripper() {}
}
