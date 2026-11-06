package app.streamsave;

public interface Callback {
    void complete(String message, boolean success);
}
