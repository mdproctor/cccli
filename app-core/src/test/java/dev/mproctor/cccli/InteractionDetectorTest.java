package dev.mproctor.cccli;

import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

class InteractionDetectorTest {

    /** Short quiet timeout so tests run fast. */
    private static final long TEST_QUIET_MS = 100;

    private List<ClaudeState> stateChanges;
    private InteractionDetector detector;

    @BeforeEach
    void setUp() {
        stateChanges = new CopyOnWriteArrayList<>();
        detector = new InteractionDetector(stateChanges::add, TEST_QUIET_MS);
    }

    @AfterEach
    void tearDown() {
        detector.close();
        stateChanges.clear();
    }

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test
    void initialStateIsFreeText() {
        assertEquals(ClaudeState.FREE_TEXT, detector.getState());
        assertTrue(stateChanges.isEmpty());
    }

    // ── onSubmit() ────────────────────────────────────────────────────────────

    @Test
    void onSubmitSwitchesToPassive() {
        detector.onSubmit();
        assertEquals(ClaudeState.PASSIVE, detector.getState());
        assertEquals(List.of(ClaudeState.PASSIVE), stateChanges);
    }

    @Test
    void submitDoesNotStartQuietTimer() throws InterruptedException {
        // onSubmit() enters PASSIVE but does NOT start the quiet timer.
        // The timer only starts when output arrives. So after submit, even if we
        // wait past the quiet period, we should still be in PASSIVE.
        detector.onSubmit();
        assertEquals(ClaudeState.PASSIVE, detector.getState());

        Thread.sleep(TEST_QUIET_MS + 50); // wait past the quiet window

        assertEquals(ClaudeState.PASSIVE, detector.getState(),
                "submit alone should not start the quiet timer — state must remain PASSIVE");
        assertEquals(1, stateChanges.size(),
                "only the PASSIVE transition should have fired, no FREE_TEXT");
    }

    @Test
    void submitThenOutputStartsQuietTimer() throws InterruptedException {
        // The real-world sequence: user submits, then claude starts responding.
        // submit → PASSIVE (no timer); output → timer starts → FREE_TEXT.
        CountDownLatch freeTextLatch = new CountDownLatch(1);
        detector.close();
        stateChanges.clear();
        detector = new InteractionDetector(state -> {
            stateChanges.add(state);
            if (state == ClaudeState.FREE_TEXT) freeTextLatch.countDown();
        }, TEST_QUIET_MS);

        detector.onSubmit();  // → PASSIVE, no timer
        detector.onOutput();  // → still PASSIVE, timer NOW starts

        assertTrue(freeTextLatch.await(1, TimeUnit.SECONDS),
                "output after submit should start quiet timer and eventually yield FREE_TEXT");
        assertEquals(List.of(ClaudeState.PASSIVE, ClaudeState.FREE_TEXT), stateChanges,
                "should see exactly one PASSIVE then one FREE_TEXT");
    }

    // ── onOutput() ────────────────────────────────────────────────────────────

    @Test
    void multipleOutputCallsFireOnlyOnePassiveTransition() {
        detector.onOutput();
        detector.onOutput();
        detector.onOutput();
        assertEquals(1, stateChanges.size());
        assertEquals(ClaudeState.PASSIVE, stateChanges.get(0));
    }

    @Test
    void quietTimerSwitchesBackToFreeText() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(2); // PASSIVE + FREE_TEXT
        detector.close();
        detector = new InteractionDetector(state -> {
            stateChanges.add(state);
            latch.countDown();
        }, TEST_QUIET_MS);

        detector.onOutput();
        assertTrue(latch.await(1, TimeUnit.SECONDS), "timeout waiting for state transitions");
        assertEquals(List.of(ClaudeState.PASSIVE, ClaudeState.FREE_TEXT), stateChanges);
    }

    // ── forceIdle() ───────────────────────────────────────────────────────────

    @Test
    void forceIdleSwitchesImmediately() {
        detector.onSubmit(); // → PASSIVE
        detector.forceIdle(); // → FREE_TEXT immediately
        assertEquals(ClaudeState.FREE_TEXT, detector.getState());
        assertEquals(List.of(ClaudeState.PASSIVE, ClaudeState.FREE_TEXT), stateChanges);
    }

    @Test
    void forceIdleWhenAlreadyFreeTextFiresNoCallback() {
        // forceIdle() when already FREE_TEXT should be a no-op — no spurious callback
        assertEquals(ClaudeState.FREE_TEXT, detector.getState());
        detector.forceIdle();
        assertTrue(stateChanges.isEmpty(),
                "forceIdle() when already FREE_TEXT must not fire the callback");
        assertEquals(ClaudeState.FREE_TEXT, detector.getState());
    }

    @Test
    void forceIdleCancelsQuietTimer() throws InterruptedException {
        // output → PASSIVE + timer pending; forceIdle → immediate FREE_TEXT
        // The quiet timer should be cancelled so no second FREE_TEXT fires later.
        detector.onOutput(); // → PASSIVE, timer starts
        assertEquals(ClaudeState.PASSIVE, detector.getState());

        detector.forceIdle(); // → FREE_TEXT, cancels timer
        assertEquals(ClaudeState.FREE_TEXT, detector.getState());

        // Wait past the quiet window — if timer was not cancelled, a second FREE_TEXT
        // callback would fire, but the state is already FREE_TEXT so no state change.
        // The key check: exactly one FREE_TEXT transition, not two.
        Thread.sleep(TEST_QUIET_MS + 50);

        assertEquals(List.of(ClaudeState.PASSIVE, ClaudeState.FREE_TEXT), stateChanges,
                "only one FREE_TEXT transition — timer must have been cancelled by forceIdle");
    }

    @Test
    void outputAfterForceIdleStartsNewPassiveCycle() {
        // After forceIdle(), the detector should be fully reset — new output creates
        // a new PASSIVE cycle from scratch.
        detector.onOutput();  // → PASSIVE
        detector.forceIdle(); // → FREE_TEXT
        detector.onOutput();  // → PASSIVE again

        assertEquals(ClaudeState.PASSIVE, detector.getState());
        assertEquals(
                List.of(ClaudeState.PASSIVE, ClaudeState.FREE_TEXT, ClaudeState.PASSIVE),
                stateChanges,
                "should see a full new PASSIVE cycle after forceIdle");
    }

    // ── close() ───────────────────────────────────────────────────────────────

    @Test
    void closeIsIdempotent() {
        detector.onOutput();
        detector.close();
        // tearDown() calls close() again — must not throw
        assertDoesNotThrow(() -> detector.close(),
                "second close() should not throw");
    }
}
