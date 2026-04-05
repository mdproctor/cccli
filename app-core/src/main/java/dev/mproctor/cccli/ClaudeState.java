package dev.mproctor.cccli;

/** The current interaction mode — determines UI state. */
public enum ClaudeState {
    /** Claude is waiting for user input. Input field is enabled. */
    FREE_TEXT,
    /** Claude is generating a response. Input field is disabled; Stop button visible. */
    PASSIVE
}
