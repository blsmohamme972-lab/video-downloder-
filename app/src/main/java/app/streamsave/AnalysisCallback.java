package app.streamsave;

public interface AnalysisCallback {
    void success(VideoDetails details);
    void failure(String message);
}
