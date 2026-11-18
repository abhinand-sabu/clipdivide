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
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Persist the imported keyframe index so exporting and reopening never repeat its scan. */
final class SourceCache {
    private static AtomicFile file(Context context) {
        return new AtomicFile(new File(context.getFilesDir(), "source-index.json"));
    }

    static synchronized void save(Context context, Uri uri, SplitEngine.Source source)
            throws Exception {
        JSONObject data = new JSONObject();
        data.put("uri", uri.toString());
        data.put("name", source.name);
        data.put("bytes", source.bytes);
        data.put("modified", modified(context, uri));
        data.put("duration", source.durationUs);
        data.put("rotation", source.rotation);
        data.put("video", source.videoTrack);
        data.put("keys", new JSONArray(source.keys));
        data.put("tracks", new JSONArray(source.tracks));
        data.put("codecs", new JSONArray(source.codecs));
        AtomicFile cache = file(context);
        FileOutputStream stream = cache.startWrite();
        try {
            stream.write(data.toString().getBytes(StandardCharsets.UTF_8));
            cache.finishWrite(stream);
        } catch (Exception error) {
            cache.failWrite(stream);
            throw error;
        }
    }

    static synchronized SplitEngine.Source load(Context context, Uri uri) throws Exception {
        JSONObject data;
        try (InputStream stream = file(context).openRead()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) bytes.write(buffer, 0, read);
            data = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        }
        if (!uri.toString().equals(data.getString("uri"))
                || data.optLong("modified") != modified(context, uri))
            throw new IOException("The video changed. Choose it again to refresh its cut points.");
        SplitEngine.Source source = new SplitEngine.Source();
        source.name = data.getString("name");
        source.bytes = data.getLong("bytes");
        source.durationUs = data.getLong("duration");
        source.rotation = data.getInt("rotation");
        source.videoTrack = data.getInt("video");
        JSONArray keys = data.getJSONArray("keys"),
                tracks = data.getJSONArray("tracks"),
                codecs = data.getJSONArray("codecs");
        for (int i = 0; i < keys.length(); i++) source.keys.add(keys.getLong(i));
        for (int i = 0; i < tracks.length(); i++) {
            source.tracks.add(tracks.getInt(i));
            source.codecs.add(codecs.getString(i));
        }
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
            if (extractor.getTrackCount() != source.tracks.size())
                throw new IOException("The video tracks changed. Choose it again.");
            for (int i = 0; i < source.tracks.size(); i++) {
                MediaFormat format = extractor.getTrackFormat(source.tracks.get(i));
                if (!source.codecs.get(i).equals(format.getString(MediaFormat.KEY_MIME)))
                    throw new IOException("The video format changed. Choose it again.");
                if (format.containsKey(MediaFormat.KEY_DURATION))
                    duration = Math.max(duration, format.getLong(MediaFormat.KEY_DURATION));
            }
            if (duration != source.durationUs)
                throw new IOException("The video duration changed. Choose it again.");
        } finally {
            extractor.release();
        }
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
