package app.clipdivide;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;

/** Reads encoded samples directly. Memory depends on the largest packet, not video length. */
public final class SplitEngine {
    public interface Progress {
        void update(int percent, String message);
    }

    public static final class Source {
        public String name;
        public long durationUs;
        public long bytes;
        public int rotation;
        public int videoTrack;
        public final List<Integer> tracks = new ArrayList<>();
        public final List<Long> keys = new ArrayList<>();
        public final List<String> codecs = new ArrayList<>();
    }

    public static final class Plan {
        public final Source source;
        public final long[] boundaries;

        Plan(Source source, long[] boundaries) {
            this.source = source;
            this.boundaries = boundaries;
        }

        public int count() {
            return boundaries.length - 1;
        }
    }

    static void check(CancellationSignal cancel) {
        if (cancel.isCanceled()) throw new CancellationException("Cancelled");
    }

    public static Source inspect(
            Context context, Uri uri, CancellationSignal cancel, Progress progress)
            throws IOException {
        Source source = new Source();
        source.name = "video";
        try (Cursor cursor =
                context.getContentResolver()
                        .query(
                                uri,
                                new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                                null,
                                null,
                                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                source.name = cursor.getString(0);
                if (!cursor.isNull(1)) source.bytes = cursor.getLong(1);
            }
        }
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, uri, null);
            source.videoTrack = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (mime.startsWith("video/")) {
                    if (source.videoTrack != -1)
                        throw new IOException(
                                "This file has multiple video tracks. Choose a single-video MP4.");
                    if (!mime.equals("video/avc") && !mime.equals("video/hevc"))
                        throw new IOException(
                                "This version supports H.264 and H.265 video. Found " + mime + ".");
                    source.videoTrack = i;
                    if (format.containsKey(MediaFormat.KEY_ROTATION))
                        source.rotation = format.getInteger(MediaFormat.KEY_ROTATION);
                } else if (mime.startsWith("audio/") && !mime.equals("audio/mp4a-latm")) {
                    throw new IOException(
                            "This version keeps AAC audio. Found "
                                    + mime
                                    + ". Choose an MP4 with AAC audio.");
                } else if (!mime.startsWith("audio/")) {
                    throw new IOException(
                            "This file includes "
                                    + mime
                                    + ". This version supports video and AAC audio tracks.");
                }
                source.tracks.add(i);
                source.codecs.add(mime);
                if (format.containsKey(MediaFormat.KEY_DURATION))
                    source.durationUs =
                            Math.max(source.durationUs, format.getLong(MediaFormat.KEY_DURATION));
            }
            if (source.videoTrack < 0 || source.durationUs <= 0)
                throw new IOException("Could not find a supported video with a valid duration.");
            extractor.selectTrack(source.videoTrack);
            long samples = 0;
            while (extractor.getSampleTime() >= 0) {
                check(cancel);
                long time = extractor.getSampleTime();
                if ((extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0)
                    throw new IOException("Encrypted videos cannot be split.");
                if ((extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        && (source.keys.isEmpty()
                                || time > source.keys.get(source.keys.size() - 1)))
                    source.keys.add(time);
                if (++samples % 4096 == 0)
                    progress.update(0, "Checking cut points · " + time(time));
                if (!extractor.advance()) break;
            }
            if (source.keys.isEmpty() || source.keys.get(0) > 100_000)
                throw new IOException("The video does not begin with a usable keyframe.");
            // A zero origin retains audio preceding a slightly offset first video sample.
            source.keys.set(0, 0L);
        } finally {
            extractor.release();
        }
        return source;
    }

    public static Plan plan(Source source, int count) throws IOException {
        validateCount(source, count);
        // Minimize the largest length error across neighboring keyframe candidates.
        // Include the previous greedy plan so the new result can never be worse.
        List<long[]> candidates = new ArrayList<>();
        candidates.add(new long[] {0});
        int previous = 0;
        for (int i = 1; i < count; i++) {
            long target = source.durationUs * i / count;
            int maximum = source.keys.size() - (count - i);
            int greedy = nearest(source.keys, target, previous + 1, maximum);
            previous = greedy;
            int center = nearest(source.keys, target, i, maximum);
            java.util.TreeSet<Long> nearby = new java.util.TreeSet<>();
            for (int k = Math.max(i, center - 3); k <= Math.min(maximum, center + 3); k++)
                nearby.add(source.keys.get(k));
            nearby.add(source.keys.get(greedy));
            long[] values = new long[nearby.size()];
            int n = 0;
            for (long value : nearby) values[n++] = value;
            candidates.add(values);
        }
        candidates.add(new long[] {source.durationUs});
        long[][] worst = new long[count + 1][];
        double[][] squared = new double[count + 1][];
        int[][] parents = new int[count + 1][];
        double ideal = (double) source.durationUs / count;
        for (int row = 0; row <= count; row++) {
            int length = candidates.get(row).length;
            worst[row] = new long[length];
            squared[row] = new double[length];
            parents[row] = new int[length];
            java.util.Arrays.fill(worst[row], Long.MAX_VALUE);
            java.util.Arrays.fill(squared[row], Double.POSITIVE_INFINITY);
        }
        worst[0][0] = 0;
        squared[0][0] = 0;
        for (int row = 1; row <= count; row++) {
            for (int j = 0; j < candidates.get(row).length; j++) {
                for (int k = 0; k < candidates.get(row - 1).length; k++) {
                    long delta = candidates.get(row)[j] - candidates.get(row - 1)[k];
                    if (delta <= 0 || worst[row - 1][k] == Long.MAX_VALUE) continue;
                    long error = Math.round(Math.abs(delta - ideal));
                    long maximum = Math.max(error, worst[row - 1][k]);
                    double total = squared[row - 1][k] + Math.pow((delta - ideal) / 1e6, 2);
                    if (maximum < worst[row][j]
                            || (maximum == worst[row][j] && total < squared[row][j])) {
                        worst[row][j] = maximum;
                        squared[row][j] = total;
                        parents[row][j] = k;
                    }
                }
            }
        }
        long[] boundaries = new long[count + 1];
        int choice = 0;
        for (int row = count; row >= 0; row--) {
            boundaries[row] = candidates.get(row)[choice];
            choice = parents[row][choice];
        }
        return new Plan(source, boundaries);
    }

    private static void validateCount(Source source, int count) throws IOException {
        if (count < 2 || count > 999) throw new IOException("Choose between 2 and 999 clips.");
        if (count > source.keys.size())
            throw new IOException(
                    "This video has "
                            + source.keys.size()
                            + " usable cut points. Choose fewer clips or a longer duration.");
    }

    private static int nearest(List<Long> keys, long target, int minimum, int maximum) {
        int low = minimum, high = maximum;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (keys.get(middle) < target) low = middle + 1;
            else high = middle;
        }
        if (low > minimum
                && Math.abs(keys.get(low - 1) - target) <= Math.abs(keys.get(low) - target))
            return low - 1;
        return low;
    }

    public static Plan planDuration(Source source, double seconds) throws IOException {
        if (!Double.isFinite(seconds) || seconds <= 0)
            throw new IOException("Enter a duration greater than zero.");
        double pieces = Math.ceil(source.durationUs / (seconds * 1e6));
        if (pieces > 999)
            throw new IOException(
                    "That duration creates over 999 clips. Choose a longer duration.");
        int count = (int) pieces;
        validateCount(source, count);
        long[] targets = new long[count - 1];
        for (int i = 1; i < count; i++) targets[i - 1] = Math.round(i * seconds * 1e6);
        return planCuts(source, targets);
    }

    public static Plan planSize(Source source, double megabytes) throws IOException {
        if (source.bytes <= 0)
            throw new IOException("This file provider does not report the video's size.");
        if (!Double.isFinite(megabytes) || megabytes <= 0)
            throw new IOException("Enter a size greater than zero.");
        double pieces = Math.ceil(source.bytes / (megabytes * 1_000_000));
        if (pieces > 999)
            throw new IOException("That size creates over 999 clips. Choose a larger size.");
        return plan(source, (int) pieces);
    }

    public static Plan planCuts(Source source, long[] targets) throws IOException {
        validateCount(source, targets.length + 1);
        long[] boundaries = new long[targets.length + 2];
        boundaries[boundaries.length - 1] = source.durationUs;
        int previous = 0;
        for (int i = 0; i < targets.length; i++) {
            if (targets[i] <= 0
                    || targets[i] >= source.durationUs
                    || (i > 0 && targets[i] <= targets[i - 1]))
                throw new IOException("Enter cut times in increasing order, inside the video.");
            int maximum = source.keys.size() - (targets.length - i);
            int best = nearest(source.keys, targets[i], previous + 1, maximum);
            boundaries[i + 1] = source.keys.get(best);
            previous = best;
        }
        return new Plan(source, boundaries);
    }

    public static Plan restorePlan(Source source, long[] boundaries) throws IOException {
        if (boundaries == null
                || boundaries.length < 3
                || boundaries.length > 1000
                || boundaries[0] != 0
                || boundaries[boundaries.length - 1] != source.durationUs)
            throw new IOException("The export plan expired. Import the video again.");
        for (int i = 1; i < boundaries.length; i++) {
            if (boundaries[i] <= boundaries[i - 1]
                    || (i < boundaries.length - 1
                            && java.util.Collections.binarySearch(source.keys, boundaries[i]) < 0))
                throw new IOException("The export plan is invalid. Import the video again.");
        }
        return new Plan(source, boundaries);
    }

    public static List<Uri> export(
            Context context,
            Uri input,
            Uri tree,
            Plan plan,
            CancellationSignal cancel,
            Progress progress)
            throws IOException {
        cleanupPending(context);
        List<Uri> outputs = new ArrayList<>();
        Uri parent =
                DocumentsContract.buildDocumentUriUsingTree(
                        tree, DocumentsContract.getTreeDocumentId(tree));
        String base =
                plan.source
                        .name
                        .replaceFirst("\\.[^.]+$", "")
                        .replaceAll("[^\\p{L}\\p{N}._ -]", "_");
        if (base.length() > 70) base = base.substring(0, 70);
        String session =
                new java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                        .format(new java.util.Date());
        ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 1024);
        List<MediaExtractor> readers = new ArrayList<>();
        List<MediaFormat> trackFormats = new ArrayList<>();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            for (int track : plan.source.tracks) {
                MediaExtractor reader = new MediaExtractor();
                readers.add(reader);
                reader.setDataSource(context, input, null);
                MediaFormat format = reader.getTrackFormat(track);
                format.removeKey(MediaFormat.KEY_ROTATION);
                trackFormats.add(format);
                reader.selectTrack(track);
                reader.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            }
            for (int clip = 0; clip < plan.count(); clip++) {
                check(cancel);
                String filename =
                        String.format(
                                Locale.US,
                                "%s_%s_%03d-of-%03d.mp4",
                                base,
                                session,
                                clip + 1,
                                plan.count());
                Uri output =
                        DocumentsContract.createDocument(
                                context.getContentResolver(), parent, "video/mp4", filename);
                if (output == null)
                    throw new IOException("The output folder could not create a clip.");
                outputs.add(output);
                savePending(context, outputs);
                try (ParcelFileDescriptor descriptor =
                        context.getContentResolver().openFileDescriptor(output, "rw")) {
                    if (descriptor == null) throw new IOException("Cannot open the output file.");
                    MediaMuxer muxer =
                            new MediaMuxer(
                                    descriptor.getFileDescriptor(),
                                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                    try {
                        muxer.setOrientationHint(plan.source.rotation);
                        List<Integer> outputTracks = new ArrayList<>();
                        for (MediaFormat format : trackFormats)
                            outputTracks.add(muxer.addTrack(format));
                        muxer.start();
                        long start = plan.boundaries[clip];
                        long end = plan.boundaries[clip + 1];
                        for (int t = 0; t < plan.source.tracks.size(); t++) {
                            int track = plan.source.tracks.get(t);
                            boolean video = track == plan.source.videoTrack;
                            MediaExtractor extractor = readers.get(t);
                            {
                                boolean began = false;
                                long packets = 0;
                                while (extractor.getSampleTime() >= 0) {
                                    check(cancel);
                                    long pts = extractor.getSampleTime();
                                    int flags = extractor.getSampleFlags();
                                    boolean sync = (flags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;
                                    // Video terminates at the next GOP boundary, retaining
                                    // reordered B-frames.
                                    if (video
                                            && began
                                            && sync
                                            && pts >= end
                                            && clip < plan.count() - 1) break;
                                    if (!video && pts >= end) break;
                                    if (!began) began = video ? sync && pts >= start : pts >= start;
                                    if (began) {
                                        long size = extractor.getSampleSize();
                                        if (size > 128L * 1024 * 1024)
                                            throw new IOException(
                                                    "A video packet is too large to process"
                                                        + " safely.");
                                        if (size > buffer.capacity())
                                            buffer = ByteBuffer.allocateDirect((int) size);
                                        buffer.clear();
                                        int read = extractor.readSampleData(buffer, 0);
                                        if (read < 0) break;
                                        if ((flags & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0)
                                            throw new IOException(
                                                    "Encrypted media cannot be split.");
                                        info.set(
                                                0,
                                                read,
                                                Math.max(0, pts - start),
                                                sync ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                                        muxer.writeSampleData(outputTracks.get(t), buffer, info);
                                        if (++packets % 128 == 0) {
                                            int percent =
                                                    (int)
                                                            (100.0
                                                                    * (clip
                                                                            + (t
                                                                                            + Math
                                                                                                    .min(
                                                                                                            1,
                                                                                                            (double)
                                                                                                                            (pts
                                                                                                                                    - start)
                                                                                                                    / (end
                                                                                                                            - start)))
                                                                                    / plan.source
                                                                                            .tracks
                                                                                            .size())
                                                                    / plan.count());
                                            progress.update(
                                                    Math.min(99, percent),
                                                    "Saving clip "
                                                            + (clip + 1)
                                                            + " of "
                                                            + plan.count());
                                        }
                                    }
                                    if (!extractor.advance()) break;
                                }
                                if (video && !began)
                                    throw new IOException(
                                            "A cut point could not be read. Try fewer clips.");
                            }
                        }
                        muxer.stop();
                    } finally {
                        muxer.release();
                    }
                }
            }
            check(cancel);
            savePending(context, new ArrayList<>());
            return outputs;
        } catch (Exception error) {
            List<Uri> pending = new ArrayList<>();
            for (Uri output : outputs) {
                try {
                    if (!DocumentsContract.deleteDocument(context.getContentResolver(), output))
                        pending.add(output);
                } catch (Exception ignored) {
                    pending.add(output);
                }
            }
            savePending(context, pending);
            if (!pending.isEmpty())
                throw new IOException(
                        "Export stopped. Could not remove "
                                + pending.size()
                                + " incomplete clip(s). Check the output folder and its"
                                + " permissions.",
                        error);
            if (error instanceof CancellationException) throw (CancellationException) error;
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException(
                    "Could not save the clips. Check free space and use a local output folder. "
                            + error.getMessage(),
                    error);
        } finally {
            for (MediaExtractor reader : readers) reader.release();
        }
    }

    private static void savePending(Context context, List<Uri> outputs) {
        java.util.Set<String> pending = new java.util.HashSet<>();
        for (Uri output : outputs) pending.add(output.toString());
        context.getSharedPreferences("job", 0).edit().putStringSet("pending", pending).commit();
    }

    private static void cleanupPending(Context context) throws IOException {
        java.util.Set<String> saved =
                context.getSharedPreferences("job", 0)
                        .getStringSet("pending", java.util.Collections.emptySet());
        List<Uri> remaining = new ArrayList<>();
        for (String value : saved) {
            Uri uri = Uri.parse(value);
            try {
                try (Cursor cursor =
                        context.getContentResolver()
                                .query(
                                        uri,
                                        new String[] {
                                            DocumentsContract.Document.COLUMN_DOCUMENT_ID
                                        },
                                        null,
                                        null,
                                        null)) {
                    if (cursor != null
                            && cursor.moveToFirst()
                            && !DocumentsContract.deleteDocument(context.getContentResolver(), uri))
                        remaining.add(uri);
                }
            } catch (Exception error) {
                remaining.add(uri);
            }
        }
        savePending(context, remaining);
        if (!remaining.isEmpty())
            throw new IOException(
                    "An interrupted export needs cleanup. Restore access to its output folder or"
                        + " remove the incomplete files there.");
    }

    public static String time(long microseconds) {
        long seconds = microseconds / 1_000_000;
        return String.format(
                Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    public static String duration(long microseconds) {
        return String.format(Locale.US, "%.2f s", microseconds / 1_000_000.0);
    }
}
