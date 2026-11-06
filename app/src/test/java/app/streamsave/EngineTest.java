package app.streamsave;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

/** Tests user-controlled links and format selection; no network or device is needed. */
public final class EngineTest {
    private static final String ID = "BaW_jenozKc";
    private static final String CANONICAL = "https://www.youtube.com/watch?v=" + ID;

    @Test public void acceptsWatchShortsMusicAndShareText() {
        for (String input : Arrays.asList(
                CANONICAL + "&list=PLtest&index=4",
                "http://m.youtube.com/watch?v=" + ID + "&t=15",
                "https://music.youtube.com/watch?v=" + ID,
                "https://youtu.be/" + ID + "?si=tracking",
                "https://www.youtube.com/shorts/" + ID,
                "https://www.youtube.com/live/" + ID,
                "https://www.youtube-nocookie.com/embed/" + ID,
                "You should watch this\nhttps://youtu.be/" + ID + ").",
                "youtube.com/watch?v=" + ID,
                "WWW.YOUTUBE.COM/watch?v=" + ID,
                "https://youtube.com/watch?feature=share&v=" + ID)) {
            assertEquals(input, CANONICAL, Engine.normalizeUrl(input));
        }
    }

    @Test public void rejectsLookalikeHostsCredentialsAndSchemes() {
        for (String input : Arrays.asList(
                "https://youtube.com.evil.invalid/watch?v=" + ID,
                "https://evil.youtube.com/watch?v=" + ID,
                "https://notyoutube.com/watch?v=" + ID,
                "https://youtube.com@evil.invalid/watch?v=" + ID,
                "https://evil@youtube.com/watch?v=" + ID,
                "https://youtu.be.evil.invalid/" + ID,
                "https://youtube.com:444/watch?v=" + ID,
                "file://youtube.com/watch?v=" + ID,
                "ftp://youtube.com/watch?v=" + ID,
                "javascript:youtube.com/watch?v=" + ID,
                "https://youtube.com/playlist?list=PLtest",
                "https://youtube.com/@channel",
                "https://youtu.be/short",
                "https://youtu.be/" + ID + "EXTRA",
                "https://youtube.com/watch?v=%ZZ",
                "https://youtube.com/watch?v=BaW_jenoz+c",
                "https://%79outube.com/watch?v=" + ID,
                "", "not a link")) {
            try {
                Engine.normalizeUrl(input);
                fail("Accepted unsafe or unsupported input: " + input);
            } catch (IllegalArgumentException expected) {
                assertFalse(expected.getMessage().isEmpty());
            }
        }
    }

    @Test public void usesExactIdsAndFiltersImagesDrmAndMissingStreams() throws Exception {
        JSONArray formats = validFormats();
        formats.put(format("sb0", "images", "none", 8192, 8192, "mhtml", 9).put("protocol", "mhtml"));
        formats.put(format("drm", "av01", "none", 4320, 7680, "mp4", 9000).put("has_drm", true));
        formats.put(format("missing", "av01", "none", 1440, 2560, "mp4", 1000).put("url", ""));
        formats.put(format("not_a_stream", "none", "none", 0, 0, "json", 0));
        formats.put(format("javascript", "av01", "none", 3000, 4000, "mp4", 1000).put("url", "javascript:alert(1)"));
        VideoDetails video = Engine.parseDetails(CANONICAL,
                metadata(formats).put("format_id", "401+251").toString());
        assertEquals("A calm day", video.title);
        assertEquals("Example channel", video.uploader);
        assertEquals("1:01:01", video.durationLabel);
        assertEquals(5, video.qualities.size());
        Quality best = video.qualities.get(0);
        assertEquals("best", best.id);
        assertEquals("401+251", best.format);
        assertTrue(best.detail.contains("MKV"));
        assertEquals(3200, best.estimatedBytes);
        assertEquals("401+251", video.qualities.get(1).format);
        assertEquals("137+251", video.qualities.get(2).format);
        assertEquals("22", video.qualities.get(3).format);
        assertTrue(video.qualities.get(3).detail.contains("MP4"));
        assertEquals("251", video.qualities.get(4).format);
        assertTrue(video.qualities.get(4).audioOnly);
        for (Quality quality : video.qualities) {
            assertFalse(quality.format.contains("sb0"));
            assertFalse(quality.format.contains("drm"));
            assertFalse(quality.format.contains("missing"));
            assertFalse(quality.format.contains("javascript"));
            assertFalse(quality.format.contains("bestvideo"));
        }
    }

    @Test public void fallsBackToPlayableExactIdsWhenSelectedFormatIsInvalid() throws Exception {
        VideoDetails video = Engine.parseDetails(CANONICAL,
                metadata(validFormats()).put("format_id", "unavailable+251").toString());
        assertEquals("401+251", video.qualities.get(0).format);
    }

    @Test public void acceptsManifestAndLabelsPortraitResolution() throws Exception {
        JSONArray formats = new JSONArray();
        formats.put(format("251", "none", "opus", 0, 0, "webm", 200));
        formats.put(format("399", "av01", "none", 1920, 1080, "mp4", 3000)
                .put("url", "").put("manifest_url", "https://media.example/playlist.m3u8"));
        VideoDetails video = Engine.parseDetails(CANONICAL,
                metadata(formats).put("format_id", "399+251").toString());
        assertEquals("1080p · Full HD", video.qualities.get(1).label);
        assertEquals(1920, video.qualities.get(1).height);
        assertEquals("399+251", video.qualities.get(1).format);
    }

    @Test public void rejectsLiveAndUnusableMetadata() throws Exception {
        JSONObject live = metadata(validFormats()).put("is_live", true);
        try {
            Engine.parseDetails(CANONICAL, live.toString());
            fail("Accepted a live stream");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("Live"));
        }
        JSONArray images = new JSONArray().put(format("sb0", "images", "none", 90, 160, "mhtml", 1));
        try {
            Engine.parseDetails(CANONICAL, metadata(images).toString());
            fail("Accepted storyboard-only metadata");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("No downloadable"));
        }
    }

    private static JSONArray validFormats() throws Exception {
        return new JSONArray()
                .put(format("140", "none", "mp4a", 0, 0, "m4a", 100))
                .put(format("251", "none", "opus", 0, 0, "webm", 200))
                .put(format("22", "avc1", "mp4a", 720, 1280, "mp4", 1000))
                .put(format("137", "avc1", "none", 1080, 1920, "mp4", 2000))
                .put(format("401", "av01", "none", 2160, 3840, "mp4", 3000));
    }

    private static JSONObject metadata(JSONArray formats) throws Exception {
        return new JSONObject().put("id", ID).put("title", "A calm day")
                .put("channel", "Example channel").put("duration", 3661)
                .put("thumbnail", "https://i.ytimg.com/vi/" + ID + "/hqdefault.jpg")
                .put("formats", formats);
    }

    private static JSONObject format(String id, String video, String audio,
                                     int height, int width, String ext, long bytes) throws Exception {
        return new JSONObject().put("format_id", id).put("vcodec", video)
                .put("acodec", audio).put("height", height).put("width", width)
                .put("ext", ext).put("filesize", bytes).put("fps", 30)
                .put("url", "https://media.example/" + id).put("protocol", "https");
    }
}
