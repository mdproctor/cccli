package dev.mproctor.cccli;

import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Infers Claude's interaction mode from PTY output timing.
 *
 * Rules:
 *   onSubmit()  → PASSIVE immediately (user sent input, waiting for claude)
 *   onOutput()  → PASSIVE + reset quiet timer (claude is responding)
 *   quiet timer → FREE_TEXT (claude has finished)
 *   forceIdle() → FREE_TEXT immediately (Stop clicked, window closed)
 *
 * onStateChanged is called on the scheduler thread — callers must be thread-safe.
 */
public class InteractionDetector implements AutoCloseable {

    /** Quiet timeout used in production. */
    public static final long DEFAULT_QUIET_MS = 800;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "interaction-detector");
                t.setDaemon(true);
                return t;
            });

    private final Consumer<ClaudeState> onStateChanged;
    private final long quietMs;
    private volatile ClaudeState state = ClaudeState.FREE_TEXT;
    private volatile ScheduledFuture<?> quietTimer;

    public InteractionDetector(Consumer<ClaudeState> onStateChanged) {
        this(onStateChanged, DEFAULT_QUIET_MS);
    }

    /** Package-private constructor for tests — allows shorter quiet timeout. */
    InteractionDetector(Consumer<ClaudeState> onStateChanged, long quietMs) {
        this.onStateChanged = onStateChanged;
        this.quietMs = quietMs;
    }

    /**
     * Call when the user submits text. Enters PASSIVE immediately.
     * The quiet timer does NOT start here — it starts when PTY output arrives.
     */
    public void onSubmit() {
        enterPassive();
        cancelQuietTimer();
    }

    /**
     * Call on every PTY output chunk. Enters PASSIVE (if not already) and
     * resets the quiet timer. After quietMs of silence transitions to FREE_TEXT.
     */
    public void onOutput() {
        enterPassive();
        rescheduleQuietTimer();
    }

    /**
     * Immediately transitions to FREE_TEXT, cancelling any pending quiet timer.
     * Call when Stop is clicked or the window closes.
     */
    public void forceIdle() {
        cancelQuietTimer();
        if (state != ClaudeState.FREE_TEXT) {
            state = ClaudeState.FREE_TEXT;
            onStateChanged.accept(ClaudeState.FREE_TEXT);
        }
    }

    public ClaudeState getState() { return state; }

    @Override
    public void close() {
        cancelQuietTimer();
        scheduler.shutdownNow();
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private void enterPassive() {
        if (state != ClaudeState.PASSIVE) {
            state = ClaudeState.PASSIVE;
            onStateChanged.accept(ClaudeState.PASSIVE);
        }
    }

    private void rescheduleQuietTimer() {
        cancelQuietTimer();
        quietTimer = scheduler.schedule(this::onQuiet, quietMs, TimeUnit.MILLISECONDS);
    }

    private void cancelQuietTimer() {
        ScheduledFuture<?> t = quietTimer;
        if (t != null) { t.cancel(false); quietTimer = null; }
    }

    private void onQuiet() {
        state = ClaudeState.FREE_TEXT;
        onStateChanged.accept(ClaudeState.FREE_TEXT);
    }
}
