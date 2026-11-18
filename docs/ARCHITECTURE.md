# How ClipDivide works

## Import and caching

The Storage Access Framework grants access to the selected source URI. `MediaExtractor` reads track metadata, then scans encoded video samples to collect keyframe timestamps. `SourceCache` stores the most recently imported source index in an atomic JSON file in the app's private storage.

Cached indexes are checked against the URI, reported size and modification time, track formats, and duration. Reusing an unchanged source avoids another keyframe scan. A new editing session waits for a video selection. Export reads the cached index and the boundaries already previewed in the UI.

## Split planning

Count mode divides the duration into ideal equal intervals. Around each ideal boundary, the planner considers nearby keyframes and the previous greedy solution. Dynamic programming chooses a path minimizing the largest clip-length deviation, with squared error as a tie-breaker. Including the greedy path protects against a worse maximum error.

Duration mode snaps regularly spaced timestamps to keyframes. File-size mode estimates a count from source bytes and requested megabytes, then uses count mode. Custom mode snaps an ordered list of positions in the original video. Every plan starts at zero, ends at the source duration, and has strictly increasing boundaries.

File-size targets are approximate. Variable bitrate changes the size of individual sections. Lossless timing depends on available keyframes and can exceed a two-second adjustment.

## Streaming export

`SplitService` runs a foreground export with an ongoing notification, cancellation, and a partial wake lock. It uses the media-processing foreground-service type on Android 15 and newer and the data-sync type on older supported versions.

`SplitEngine` opens one extractor per supported track and retains those readers across clips. Each MP4 is written with `MediaMuxer`. A reusable direct byte buffer carries the encoded packets. A reusable `BufferInfo` carries their metadata. The app never loads the whole recording into RAM and never encodes the video or audio.

Video advances through keyframe boundaries while preserving reordered B-frames. Audio follows packet boundaries. Packet presentation timestamps are rebased for each clip, and rotation metadata is copied to the MP4 orientation matrix.

## Export state and cleanup

The service records state, progress, output URIs, and timing in private preferences. The activity observes that state and presents distinct editing, export, and completion screens. Completion stays visible until the user starts another split.

Each created output URI is recorded in a persistent cleanup journal. Cancellation removes outputs from the current job. A later export attempts cleanup after a process interruption, then restarts the job. Resume from the middle of a file is currently unsupported. Files may be visible in the chosen output folder while export is still running.

## Code map

| Component | Responsibility |
| --- | --- |
| `MainActivity` | File selection, four split modes, previews, progress, completion, sharing |
| `SplitEngine` | Inspection, planning, encoded-packet copying, cleanup |
| `SourceCache` | Atomic keyframe-index storage and validation |
| `SplitService` | Foreground execution, notifications, cancellation, wake lock |
| `ProgressArtwork` | Progress ring and completion animation |
| `ClipTimeline` | Proportional preview of planned clips |

## Support and validation

The current implementation accepts one H.264 or H.265 video track with optional AAC audio tracks. Output is MP4. Android 10 or newer is required. Unsupported additional tracks produce an explanation. Local storage is recommended because the MP4 writer requires a seekable output descriptor.

A generated three-hour H.264/AAC file with B-frames, approximately 508 MB, was exported to seven clips in the Android emulator. All 107,773 encoded video packets matched the source hashes and order. Exported AAC packets matched except for the source's initial pre-roll packet. Clip beginnings and endings decoded successfully.

The optimized export took approximately 11 seconds on that emulator. Cached import took approximately 32 ms. Those measurements depend on the test environment. Multi-gigabyte media, additional codecs, and a wider device matrix still need validation.
