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

    @Test
    void initialStateIsFreeText() {
        assertEquals(ClaudeState.FREE_TEXT, detector.getState());
        assertTrue(stateChanges.isEmpty());
    }

    @Test
    void onSubmitSwitchesToPassive() {
        detector.onSubmit();
        assertEquals(ClaudeState.PASSIVE, detector.getState());
        assertEquals(List.of(ClaudeState.PASSIVE), stateChanges);
    }

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

    @Test
    void forceIdleSwitchesImmediately() {
        detector.onSubmit(); // → PASSIVE
        detector.forceIdle(); // → FREE_TEXT immediately
        assertEquals(ClaudeState.FREE_TEXT, detector.getState());
        assertEquals(List.of(ClaudeState.PASSIVE, ClaudeState.FREE_TEXT), stateChanges);
    }
}
