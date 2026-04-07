package dev.mproctor.cccli;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InputRouterTest {

    private List<String> ptyWrites;
    private List<Boolean> slashModeChanges;
    private List<String> inputTextChanges;
    private InputRouter router;

    @BeforeEach
    void setUp() {
        ptyWrites        = new ArrayList<>();
        slashModeChanges = new ArrayList<>();
        inputTextChanges = new ArrayList<>();
        router = new InputRouter(
                ptyWrites::add,
                slashModeChanges::add,
                inputTextChanges::add);
    }

    // ── Entry conditions ──────────────────────────────────────────────────────

    @Test
    void slashAloneEntersPassthroughMode() {
        router.onTextChanged("/");
        assertEquals(InputRouter.Mode.SLASH_PASSTHROUGH, router.getMode());
        assertEquals(List.of("/"),    ptyWrites);
        assertEquals(List.of(true),   slashModeChanges);
        assertEquals(List.of(""),     inputTextChanges);
    }

    @Test
    void otherTextStaysNormal() {
        router.onTextChanged("a");
        assertEquals(InputRouter.Mode.NORMAL, router.getMode());
        assertTrue(ptyWrites.isEmpty());
        assertTrue(slashModeChanges.isEmpty());
    }

    @Test
    void pastedTextWithSlashStaysNormal() {
        router.onTextChanged("/abc");
        assertEquals(InputRouter.Mode.NORMAL, router.getMode());
    }

    @Test
    void emptyTextStaysNormal() {
        router.onTextChanged("");
        assertEquals(InputRouter.Mode.NORMAL, router.getMode());
    }

    // ── Text changes ignored in SLASH_PASSTHROUGH ─────────────────────────────

    @Test
    void textChangesIgnoredInPassthroughMode() {
        router.onTextChanged("/");
        ptyWrites.clear(); slashModeChanges.clear(); inputTextChanges.clear();

        router.onTextChanged("");
        assertEquals(InputRouter.Mode.SLASH_PASSTHROUGH, router.getMode());
        assertTrue(ptyWrites.isEmpty());
        assertTrue(slashModeChanges.isEmpty());
        assertTrue(inputTextChanges.isEmpty());
    }

    // ── Passthrough routing ───────────────────────────────────────────────────

    @Test
    void letterKeyForwardedToPty() {
        router.onTextChanged("/");
        ptyWrites.clear();

        router.onKeyPressed("c");
        assertEquals(List.of("c"), ptyWrites);
        assertEquals(InputRouter.Mode.SLASH_PASSTHROUGH, router.getMode());
    }

    @Test
    void arrowEscapeSequenceForwardedToPty() {
        router.onTextChanged("/");
        ptyWrites.clear();

        router.onKeyPressed("\u001B[A");
        assertEquals(List.of("\u001B[A"), ptyWrites);
        assertEquals(InputRouter.Mode.SLASH_PASSTHROUGH, router.getMode());
    }

    @Test
    void keysInNormalModeNotForwardedToPty() {
        router.onKeyPressed("x");
        assertTrue(ptyWrites.isEmpty());
        assertEquals(InputRouter.Mode.NORMAL, router.getMode());
    }

    // ── Exit via Space ────────────────────────────────────────────────────────

    @Test
    void spaceExitsPassthroughAndSendsEscape() {
        router.onTextChanged("/");
        ptyWrites.clear(); slashModeChanges.clear(); inputTextChanges.clear();

        router.onKeyPressed(" ");
        assertEquals(List.of("\u001B"),  ptyWrites);
        assertEquals(List.of(false),      slashModeChanges);
        assertEquals(List.of(""),         inputTextChanges);
        assertEquals(InputRouter.Mode.NORMAL, router.getMode());
    }

    // ── Exit via Escape ───────────────────────────────────────────────────────

    @Test
    void escapeExitsPassthroughAndSendsEscape() {
        router.onTextChanged("/");
        ptyWrites.clear(); slashModeChanges.clear(); inputTextChanges.clear();

        router.onKeyPressed("\u001B");
        assertEquals(List.of("\u001B"),  ptyWrites);
        assertEquals(List.of(false),      slashModeChanges);
        assertEquals(List.of(""),         inputTextChanges);
        assertEquals(InputRouter.Mode.NORMAL, router.getMode());
    }

    // ── Exit via Enter ────────────────────────────────────────────────────────

    @Test
    void enterExitsPassthroughAndSendsCr() {
        router.onTextChanged("/");
        ptyWrites.clear(); slashModeChanges.clear(); inputTextChanges.clear();

        router.onKeyPressed("\r");
        assertEquals(List.of("\r"), ptyWrites);
        assertEquals(List.of(false), slashModeChanges);
        assertTrue(inputTextChanges.isEmpty());
        assertEquals(InputRouter.Mode.NORMAL, router.getMode());
    }

    // ── Exit via Backspace-to-empty ───────────────────────────────────────────

    @Test
    void backspaceWithCharsBufferedStaysInPassthrough() {
        router.onTextChanged("/");
        router.onKeyPressed("c");
        ptyWrites.clear();

        router.onKeyPressed("\u007F");
        assertEquals(List.of("\u007F"), ptyWrites);
        assertEquals(InputRouter.Mode.SLASH_PASSTHROUGH, router.getMode());
    }

    @Test
    void backspaceOnEmptyBufferExitsPassthrough() {
        router.onTextChanged("/");
        ptyWrites.clear(); slashModeChanges.clear(); inputTextChanges.clear();

        router.onKeyPressed("\u007F");
        assertEquals(List.of("\u001B"), ptyWrites);
        assertEquals(List.of(false),     slashModeChanges);
        assertEquals(List.of(""),        inputTextChanges);
        assertEquals(InputRouter.Mode.NORMAL, router.getMode());
    }

    @Test
    void multipleCharsBackspacedToEmptyExitsPassthrough() {
        router.onTextChanged("/");
        router.onKeyPressed("c");      // count = 1
        router.onKeyPressed("l");      // count = 2
        router.onKeyPressed("\u007F"); // count = 1 → stays, sends \x7f
        assertEquals(InputRouter.Mode.SLASH_PASSTHROUGH, router.getMode());
        router.onKeyPressed("\u007F"); // count = 0 → stays, sends \x7f
        assertEquals(InputRouter.Mode.SLASH_PASSTHROUGH, router.getMode());

        ptyWrites.clear(); slashModeChanges.clear(); inputTextChanges.clear();
        router.onKeyPressed("\u007F"); // count already 0 → EXIT, sends \x1b
        assertEquals(List.of("\u001B"), ptyWrites);
        assertEquals(List.of(false),     slashModeChanges);
        assertEquals(List.of(""),        inputTextChanges);
        assertEquals(InputRouter.Mode.NORMAL, router.getMode());
    }

    // ── Post-exit state is clean ──────────────────────────────────────────────

    @Test
    void afterExitNewSlashEntersPassthroughAgain() {
        router.onTextChanged("/");
        router.onKeyPressed(" ");
        assertEquals(InputRouter.Mode.NORMAL, router.getMode());

        ptyWrites.clear(); slashModeChanges.clear(); inputTextChanges.clear();
        router.onTextChanged("/");
        assertEquals(InputRouter.Mode.SLASH_PASSTHROUGH, router.getMode());
        assertEquals(List.of(true), slashModeChanges);
    }
}
