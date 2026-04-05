package dev.mproctor.cccli;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnsiStripperTest {

    @Test
    void preservesPlainText() {
        assertEquals("hello world\n", AnsiStripper.strip("hello world\n"));
    }

    @Test
    void stripsColorSequences() {
        // ESC[32m = green, ESC[0m = reset
        assertEquals("hello", AnsiStripper.strip("\u001B[32mhello\u001B[0m"));
    }

    @Test
    void stripsBoldAndMultiParamSequences() {
        // ESC[1;32m = bold green
        assertEquals("text", AnsiStripper.strip("\u001B[1;32mtext\u001B[0m"));
    }

    @Test
    void stripsCursorMovementSequences() {
        // ESC[1A = cursor up 1, ESC[2K = erase line
        assertEquals("", AnsiStripper.strip("\u001B[1A\u001B[2K"));
    }

    @Test
    void stripsOscSequences() {
        // ESC]0;title BEL — window title sequence
        assertEquals("hello", AnsiStripper.strip("\u001B]0;title\u0007hello"));
    }

    @Test
    void normalisesCrLfToLf() {
        assertEquals("hello\nworld\n", AnsiStripper.strip("hello\r\nworld\r\n"));
    }

    @Test
    void stripsBareCarriageReturn() {
        // Bare CR without LF (spinner overwrite pattern) is dropped
        assertEquals("progress", AnsiStripper.strip("progress\r"));
    }

    @Test
    void keepsLfFromCrLf() {
        // CRLF → LF (not stripped)
        String result = AnsiStripper.strip("line\r\n");
        assertEquals("line\n", result);
    }

    @Test
    void stripsComplexRealWorldSequence() {
        // Typical claude output: color + text + reset + CRLF
        String input = "\u001B[1m\u001B[32m>\u001B[0m Hello there\r\n";
        assertEquals("> Hello there\n", AnsiStripper.strip(input));
    }
}
