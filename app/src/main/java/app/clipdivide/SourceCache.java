package app.clipdivide;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.CancellationSignal;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/** Persist imported keyframe indexes per URI so exporting and reopening never repeat the scan. */
final class SourceCache {
    private static final int MAX_ENTRIES = 8;

    private static File cacheDir(Context context) {
        File dir = new File(context.getFilesDir(), "source-cache");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static AtomicFile fileFor(Context context, Uri uri) {
        return new AtomicFile(new File(cacheDir(context), keyFor(uri.toString()) + ".json"));
    }

    static String keyFor(String uri) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(uri.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++)
                hex.append(String.format(Locale.US, "%02x", digest[i] & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException error) {
            return Integer.toHexString(uri.hashCode());
        }
    }

    static JSONObject toJson(String uri, long modified, SplitEngine.Source source)
            throws JSONException {
        JSONObject data = new JSONObject();
        data.put("uri", uri);
        data.put("name", source.name);
        data.put("bytes", source.bytes);
        data.put("totalVideoBytes", source.totalVideoBytes);
        data.put("modified", modified);
        data.put("duration", source.durationUs);
        data.put("rotation", source.rotation);
        data.put("video", source.videoTrack);
        data.put("skippedTracks", source.skippedTracks);
        data.put("keys", new JSONArray(source.keys));
        data.put("keyBytes", new JSONArray(source.keyBytes));
        data.put("tracks", new JSONArray(source.tracks));
        data.put("codecs", new JSONArray(source.codecs));
        return data;
    }

    static SplitEngine.Source fromJson(JSONObject data) throws JSONException {
        SplitEngine.Source source = new SplitEngine.Source();
        source.name = data.getString("name");
        source.bytes = data.getLong("bytes");
        source.totalVideoBytes = data.optLong("totalVideoBytes", 0);
        source.durationUs = data.getLong("duration");
        source.rotation = data.getInt("rotation");
        source.videoTrack = data.getInt("video");
        source.skippedTracks = data.optInt("skippedTracks", 0);
        JSONArray keys = data.getJSONArray("keys"),
                keyBytes = data.optJSONArray("keyBytes"),
                tracks = data.getJSONArray("tracks"),
                codecs = data.getJSONArray("codecs");
        for (int i = 0; i < keys.length(); i++) source.keys.add(keys.getLong(i));
        if (keyBytes != null) {
            for (int i = 0; i < keyBytes.length(); i++) source.keyBytes.add(keyBytes.getLong(i));
        }
        for (int i = 0; i < tracks.length(); i++) {
            source.tracks.add(tracks.getInt(i));
            source.codecs.add(codecs.getString(i));
        }
        return source;
    }

    static synchronized void save(Context context, Uri uri, SplitEngine.Source source)
            throws Exception {
        JSONObject data = toJson(uri.toString(), modified(context, uri), source);
        AtomicFile cache = fileFor(context, uri);
        FileOutputStream stream = cache.startWrite();
        try {
            stream.write(data.toString().getBytes(StandardCharsets.UTF_8));
            cache.finishWrite(stream);
        } catch (Exception error) {
            cache.failWrite(stream);
            throw error;
        }
        trimCache(cacheDir(context));
    }

    private static void trimCache(File dir) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null || files.length <= MAX_ENTRIES) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < files.length - MAX_ENTRIES; i++) files[i].delete();
    }

    static synchronized SplitEngine.Source loadIfCached(Context context, Uri uri) {
        try {
            return load(context, uri);
        } catch (Exception ignored) {
            return null;
        }
    }

    static synchronized SplitEngine.Source load(Context context, Uri uri) throws Exception {
        AtomicFile cache = fileFor(context, uri);
        JSONObject data;
        try (InputStream stream = cache.openRead()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) bytes.write(buffer, 0, read);
            data = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        }
        if (!uri.toString().equals(data.getString("uri"))
                || data.optLong("modified") != modified(context, uri))
            throw new IOException("The video changed. Choose it again to refresh its cut points.");
        SplitEngine.Source source = fromJson(data);
        if (!source.hasKeys())
            throw new IOException("The cached index is incomplete. Refreshing cut points.");
        try (Cursor cursor =
                context.getContentResolver()
                        .query(uri, new String[] {OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null
                    && cursor.moveToFirst()
                    && !cursor.isNull(0)
                    && source.bytes != cursor.getLong(0))
                throw new IOException("The video size changed. Choose it again.");
        }
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, uri, null);
            long duration = 0;
            int supportedTracks = 0;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || (!mime.startsWith("video/") && !mime.startsWith("audio/")))
                    continue;
                if (supportedTracks >= source.tracks.size()
                        || source.tracks.get(supportedTracks) != i
                        || !source.codecs.get(supportedTracks).equals(mime))
                    throw new IOException("The video format changed. Choose it again.");
                if (format.containsKey(MediaFormat.KEY_DURATION))
                    duration = Math.max(duration, format.getLong(MediaFormat.KEY_DURATION));
                supportedTracks++;
            }
            if (supportedTracks != source.tracks.size() || duration != source.durationUs)
                throw new IOException("The video duration changed. Choose it again.");
        } finally {
            extractor.release();
        }
        cache.getBaseFile().setLastModified(System.currentTimeMillis());
        return source;
    }

    private static long modified(Context context, Uri uri) {
        try (Cursor cursor =
                context.getContentResolver()
                        .query(
                                uri,
                                new String[] {DocumentsContract.Document.COLUMN_LAST_MODIFIED},
                                null,
                                null,
                                null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0))
                return cursor.getLong(0);
        } catch (Exception ignored) {
        }
        return 0;
    }

    static SplitEngine.Source importVideo(
            Context context,
            Uri uri,
            CancellationSignal cancellation,
            SplitEngine.Progress progress)
            throws Exception {
        SplitEngine.Source source;
        try {
            source = load(context, uri);
        } catch (Exception missing) {
            source = SplitEngine.inspect(context, uri, cancellation, progress);
            SplitEngine.check(cancellation);
            save(context, uri, source);
        }
        SplitEngine.check(cancellation);
        return source;
    }
}
