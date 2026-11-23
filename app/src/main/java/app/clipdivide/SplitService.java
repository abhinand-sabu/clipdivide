package app.clipdivide;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.*;

import java.util.List;
import java.util.concurrent.CancellationException;

public final class SplitService extends Service {
    public static volatile boolean running;
    private CancellationSignal cancellation;
    private PowerManager.WakeLock wakeLock;
    private long lastUpdate;
    private Notification.Builder notification;
    private static final int ID = 1;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "cancel".equals(intent.getAction())) {
            if (cancellation != null) cancellation.cancel();
            if (!running) stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;
        if (intent == null || intent.getStringExtra("input") == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        cancellation = new CancellationSignal();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(
                new NotificationChannel(
                        "export", "Video splitting", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open =
                PendingIntent.getActivity(
                        this,
                        0,
                        new Intent(this, MainActivity.class),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent cancel =
                PendingIntent.getService(
                        this,
                        1,
                        new Intent(this, SplitService.class).setAction("cancel"),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        notification =
                new Notification.Builder(this, "export")
                        .setSmallIcon(R.drawable.ic_stat_split)
                        .setContentTitle("ClipDivide")
                        .setContentText("Preparing your clips")
                        .setContentIntent(open)
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .addAction(new Notification.Action.Builder(null, "Cancel", cancel).build());
        try {
            int type =
                    Build.VERSION.SDK_INT >= 35
                            ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                            : ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            startForeground(ID, notification.build(), type);
            wakeLock =
                    getSystemService(PowerManager.class)
                            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClipDivide:export");
            wakeLock.acquire(6 * 60 * 60 * 1000L);
        } catch (Exception error) {
            save("error", 0, "Could not start export. " + error.getMessage());
            running = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        save("running", 0, "Starting export");
        Uri input = Uri.parse(intent.getStringExtra("input"));
        Uri folder = Uri.parse(intent.getStringExtra("folder"));
        long[] boundaries = intent.getLongArrayExtra("boundaries");
        int mode = intent.getIntExtra("mode", 0);
        int count = intent.getIntExtra("count", 5);
        double durationSec = intent.getDoubleExtra("durationSec", 60);
        double sizeMb = intent.getDoubleExtra("sizeMb", 100);
        long[] cuts = intent.getLongArrayExtra("cuts");
        new Thread(
                        () -> {
                            try {
                                boolean wasCached = SourceCache.loadIfCached(this, input) != null;
                                SplitEngine.Source source =
                                        SourceCache.importVideo(
                                                this,
                                                input,
                                                cancellation,
                                                (p, msg) ->
                                                        progress(
                                                                wasCached ? p : Math.round(p * 0.2f),
                                                                msg));
                                SplitEngine.Plan plan;
                                if (boundaries != null) {
                                    plan = SplitEngine.restorePlan(source, boundaries);
                                } else if (mode == 1) {
                                    plan = SplitEngine.planDuration(source, durationSec);
                                } else if (mode == 2) {
                                    plan = SplitEngine.planSize(source, sizeMb);
                                } else if (mode == 3 && cuts != null) {
                                    plan = SplitEngine.planCuts(source, cuts);
                                } else {
                                    plan = SplitEngine.plan(source, count);
                                }
                                getSharedPreferences("job", 0)
                                        .edit()
                                        .putString("folder", folder.toString())
                                        .putString("name", source.name)
                                        .putInt("count", plan.count())
                                        .putLong("started", System.currentTimeMillis())
                                        .apply();
                                int base = wasCached ? 0 : 20;
                                double span = wasCached ? 1.0 : 0.8;
                                List<Uri> outputs =
                                        SplitEngine.export(
                                                this,
                                                input,
                                                folder,
                                                plan,
                                                cancellation,
                                                (p, msg) ->
                                                        progress(
                                                                base + (int) Math.round(p * span),
                                                                msg));
                                StringBuilder saved = new StringBuilder();
                                for (Uri output : outputs) saved.append(output).append('\n');
                                getSharedPreferences("job", 0)
                                        .edit()
                                        .putString("outputs", saved.toString())
                                        .putLong("finished", System.currentTimeMillis())
                                        .commit();
                                save("done", 100, outputs.size() + " clips saved");
                            } catch (CancellationException error) {
                                save("cancelled", 0, "Cancelled. Incomplete clips removed.");
                            } catch (Exception error) {
                                save(
                                        "error",
                                        0,
                                        error.getMessage() == null
                                                ? "The video could not be split."
                                                : error.getMessage());
                            } finally {
                                releaseWakeLock();
                                running = false;
                                stopForeground(STOP_FOREGROUND_REMOVE);
                                stopSelf();
                            }
                        },
                        "clipdivide-export")
                .start();
        return START_NOT_STICKY;
    }

    private synchronized void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {
            }
        }
    }

    private void progress(int percent, String message) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastUpdate < 400) return;
        lastUpdate = now;
        int clamped = Math.max(0, Math.min(99, percent));
        save("running", clamped, message);
        getSystemService(NotificationManager.class)
                .notify(
                        ID,
                        notification
                                .setContentText(message)
                                .setProgress(100, clamped, clamped == 0)
                                .build());
    }

    private void save(String state, int percent, String message) {
        android.content.SharedPreferences.Editor edit =
                getSharedPreferences("job", 0)
                        .edit()
                        .putString("state", state)
                        .putInt("percent", percent)
                        .putString("message", message);
        if ("running".equals(state)) edit.apply();
        else edit.commit();
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        if (cancellation != null) cancellation.cancel();
        releaseWakeLock();
        save("error", 0, "Export timed out after the system limit.");
        running = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (cancellation != null) cancellation.cancel();
        releaseWakeLock();
        super.onDestroy();
    }
}
