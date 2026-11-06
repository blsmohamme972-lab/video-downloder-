package app.streamsave;

import org.json.JSONException;
import org.json.JSONObject;

/** A durable job. Signed media URLs are deliberately never persisted. */
public final class Job {
    public String id = "";
    public String url = "";
    public String title = "";
    public String format = "";
    public String qualityLabel = "";
    public boolean audioOnly;
    public State state = State.QUEUED;
    public float progress;
    public String statusLine = "Queued";
    public String message = "";
    public String contentUri = "";
    public String pendingUri = "";
    public String localFile = "";
    public long createdAt;
    public long completedAt;
    public long bytes;

    public boolean active() {
        return state == State.QUEUED || state == State.DOWNLOADING
                || state == State.MERGING || state == State.SAVING;
    }

    public Job copy() {
        Job j = new Job();
        j.id = id; j.url = url; j.title = title; j.format = format;
        j.qualityLabel = qualityLabel; j.audioOnly = audioOnly; j.state = state;
        j.progress = progress; j.statusLine = statusLine; j.message = message;
        j.contentUri = contentUri; j.pendingUri = pendingUri; j.localFile = localFile;
        j.createdAt = createdAt; j.completedAt = completedAt; j.bytes = bytes;
        return j;
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id); o.put("url", url); o.put("title", title);
        o.put("format", format); o.put("qualityLabel", qualityLabel);
        o.put("audioOnly", audioOnly); o.put("state", state.name());
        o.put("progress", Float.isFinite(progress) ? progress : 0);
        o.put("statusLine", statusLine); o.put("message", message);
        o.put("contentUri", contentUri); o.put("pendingUri", pendingUri);
        o.put("localFile", localFile); o.put("createdAt", createdAt);
        o.put("completedAt", completedAt); o.put("bytes", bytes);
        return o;
    }

    static Job fromJson(JSONObject o) throws JSONException {
        Job j = new Job();
        j.id = o.getString("id");
        if (!j.id.matches("[a-f0-9-]{36}")) throw new JSONException("Invalid job identifier");
        j.url = o.getString("url");
        j.title = o.optString("title", "YouTube video");
        j.format = o.getString("format");
        j.qualityLabel = o.optString("qualityLabel", "Best available");
        j.audioOnly = o.optBoolean("audioOnly");
        try { j.state = State.valueOf(o.optString("state", "PAUSED")); }
        catch (IllegalArgumentException ignored) { j.state = State.PAUSED; }
        double p = o.optDouble("progress", 0);
        j.progress = Double.isFinite(p) ? (float) Math.min(100, Math.max(0, p)) : 0;
        j.statusLine = o.optString("statusLine", "");
        j.message = o.optString("message", "");
        j.contentUri = o.optString("contentUri", "");
        j.pendingUri = o.optString("pendingUri", "");
        j.localFile = o.optString("localFile", "");
        j.createdAt = o.optLong("createdAt");
        j.completedAt = o.optLong("completedAt");
        j.bytes = o.optLong("bytes");
        return j;
    }
}
