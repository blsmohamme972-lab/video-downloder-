package app.streamsave;

/** An actual downloadable representation, including its exact stream IDs. */
public final class Quality {
    public final String id;
    public final String label;
    public final String detail;
    public final String format;
    public final boolean audioOnly;
    public final int height;
    public final long estimatedBytes;

    public Quality(String id, String label, String detail, String format,
                   boolean audioOnly, int height, long estimatedBytes) {
        this.id = id;
        this.label = label;
        this.detail = detail;
        this.format = format;
        this.audioOnly = audioOnly;
        this.height = height;
        this.estimatedBytes = estimatedBytes;
    }
}
