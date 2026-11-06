package app.streamsave;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Metadata captured when the user examines a single video. */
public final class VideoDetails {
    public final String id;
    public final String title;
    public final String uploader;
    public final String durationLabel;
    public final String thumbnail;
    public final String url;
    public final long durationSeconds;
    public final List<Quality> qualities;

    public VideoDetails(String id, String title, String uploader, String durationLabel,
                        String thumbnail, String url, long durationSeconds,
                        List<Quality> qualities) {
        this.id = id;
        this.title = title;
        this.uploader = uploader;
        this.durationLabel = durationLabel;
        this.thumbnail = thumbnail;
        this.url = url;
        this.durationSeconds = durationSeconds;
        this.qualities = Collections.unmodifiableList(new ArrayList<>(qualities));
    }
}
