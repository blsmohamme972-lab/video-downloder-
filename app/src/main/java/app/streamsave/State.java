package app.streamsave;

/** Persisted download states. A process restart returns unfinished work to PAUSED. */
public enum State {
    QUEUED, DOWNLOADING, PAUSED, MERGING, SAVING, COMPLETED, FAILED
}
