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
                        .setSmallIcon(android.R.drawable.stat_sys_upload)
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
        int count = intent.getIntExtra("count", 5);
        new Thread(
                        () -> {
                            try {
                                SplitEngine.Source source = SourceCache.load(this, input);
                                SplitEngine.Plan plan =
                                        boundaries == null
                                                ? SplitEngine.plan(source, count)
                                                : SplitEngine.restorePlan(source, boundaries);
                                getSharedPreferences("job", 0)
                                        .edit()
                                        .putString("folder", folder.toString())
                                        .putString("name", source.name)
                                        .putInt("count", plan.count())
                                        .putLong("started", System.currentTimeMillis())
                                        .apply();
                                List<Uri> outputs =
                                        SplitEngine.export(
                                                this,
                                                input,
                                                folder,
                                                plan,
                                                cancellation,
                                                this::progress);
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
                                if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
                                running = false;
                                stopForeground(STOP_FOREGROUND_REMOVE);
                                stopSelf();
                            }
                        },
                        "clipdivide-export")
                .start();
        return START_NOT_STICKY;
    }

    private void progress(int percent, String message) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastUpdate < 400) return;
        lastUpdate = now;
        save("running", percent, message);
        getSystemService(NotificationManager.class)
                .notify(
                        ID,
                        notification
                                .setContentText(message)
                                .setProgress(100, percent, percent == 0)
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
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (cancellation != null) cancellation.cancel();
        super.onDestroy();
    }
}
