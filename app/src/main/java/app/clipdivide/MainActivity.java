package app.clipdivide;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int PAPER = 0xffF5F5EF,
            INK = 0xff163B30,
            MUTED = 0xff65766C,
            GREEN = 0xff1D6A50,
            LIME = 0xffD8F775;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final String[] modeNames = {"Clip count", "Duration", "File size", "Custom times"};
    private final String[] values = {"5", "60", "100", ""};
    private final Button[] modeButtons = new Button[4];
    private SharedPreferences config, job;
    private Uri input, folder;
    private SplitEngine.Source source;
    private SplitEngine.Plan plan;
    private CancellationSignal inspection;
    private boolean inspecting, starting;
    private int mode;
    private String screen = "", lastJob = "";
    private LinearLayout shell, editor;
    private TextView fileName,
            fileInfo,
            fieldLabel,
            fieldHelp,
            previewTitle,
            previewNote,
            folderLabel,
            error;
    private EditText parameter;
    private Button pick, split, folderButton, previewButton;
    private ClipTimeline timeline;
    private ProgressArtwork artwork;
    private TextView exportTitle, exportStatus, exportTime;
    private Button cancel;
    private final Runnable poll =
            new Runnable() {
                public void run() {
                    refreshJob();
                    handler.postDelayed(this, 500);
                }
            };

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        config = getSharedPreferences("config", 0);
        job = getSharedPreferences("job", 0);
        if (config.contains("folder")) folder = Uri.parse(config.getString("folder", ""));
        mode = Math.max(0, Math.min(3, config.getInt("mode", 0)));
        for (int i = 0; i < 4; i++) values[i] = config.getString("value" + i, values[i]);
        getWindow()
                .getDecorView()
                .setOnApplyWindowInsetsListener(
                        (view, insets) -> {
                            if (Build.VERSION.SDK_INT >= 30) {
                                android.graphics.Insets bars =
                                        insets.getInsets(
                                                WindowInsets.Type.systemBars()
                                                        | WindowInsets.Type.displayCutout());
                                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                            } else {
                                view.setPadding(
                                        insets.getSystemWindowInsetLeft(),
                                        insets.getSystemWindowInsetTop(),
                                        insets.getSystemWindowInsetRight(),
                                        insets.getSystemWindowInsetBottom());
                            }
                            return insets;
                        });
        if (Build.VERSION.SDK_INT >= 33)
            getOnBackInvokedDispatcher()
                    .registerOnBackInvokedCallback(
                            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
        String status = job.getString("state", "");
        if ("running".equals(status) && !SplitService.running) {
            job.edit()
                    .putString("state", "error")
                    .putString(
                            "message",
                            "Android interrupted the export. Split again to clean up incomplete"
                                    + " clips and restart.")
                    .commit();
        }
        if ("done".equals(job.getString("state", ""))) showExport(true, false);
        else if (SplitService.running) showExport(false, false);
        else {
            showEditor();
        }
    }

    private void base(boolean dark) {
        int background = dark ? INK : PAPER;
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        getWindow().setStatusBarContrastEnforced(false);
        getWindow().setNavigationBarContrastEnforced(false);
        getWindow().getDecorView().setBackgroundColor(dark ? INK : PAPER);
        shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(dark ? INK : PAPER);
        setContentView(shell);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow()
                    .getDecorView()
                    .post(
                            () -> {
                                WindowInsetsController controller =
                                        getWindow().getInsetsController();
                                if (controller != null) {
                                    int lightIcons =
                                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                                    | WindowInsetsController
                                                            .APPEARANCE_LIGHT_NAVIGATION_BARS;
                                    controller.setSystemBarsAppearance(
                                            dark ? 0 : lightIcons, lightIcons);
                                }
                            });
        }
        getWindow()
                .getDecorView()
                .setSystemUiVisibility(
                        dark
                                ? 0
                                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    private void brand(LinearLayout root, boolean dark) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(app.clipdivide.R.drawable.brand_icon);
        GradientDrawable clip = shape(INK, 14, 0);
        icon.setBackground(clip);
        icon.setClipToOutline(true);
        row.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        TextView name = text("ClipDivide", 21, dark ? PAPER : INK);
        name.setTypeface(null, Typeface.BOLD);
        names.addView(name);
        names.addView(text("Lossless video splitter", 12, dark ? 0xffB5CDBD : MUTED));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.leftMargin = dp(12);
        row.addView(names, params);
        add(root, row, 0);
    }

    private void showEditor() {
        screen = "editor";
        base(false);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        editor = column();
        editor.setPadding(dp(22), dp(22), dp(22), dp(20));
        scroll.addView(editor);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        brand(editor, false);
        LinearLayout video = card(editor, 24);
        smallLabel(video, "SOURCE VIDEO");
        fileName = text(source == null ? "Choose your video" : source.name, 19, INK);
        fileName.setTypeface(null, Typeface.BOLD);
        fileName.setMaxLines(2);
        fileName.setEllipsize(TextUtils.TruncateAt.END);
        add(video, fileName, 10);
        fileInfo = text(source == null ? "H.264 or H.265 · AAC audio" : "", 13, MUTED);
        add(video, fileInfo, 6);
        pick = button(source == null ? "Choose video" : "Change video", false, false);
        add(video, pick, 16);
        pick.setOnClickListener(
                view ->
                        startActivityForResult(
                                new Intent(Intent.ACTION_OPEN_DOCUMENT)
                                        .setType("video/*")
                                        .addCategory(Intent.CATEGORY_OPENABLE)
                                        .addFlags(
                                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                                                        | Intent
                                                                .FLAG_GRANT_PERSISTABLE_URI_PERMISSION),
                                10));

        LinearLayout options = card(editor, 14);
        smallLabel(options, "SPLIT BY");
        for (int row = 0; row < 2; row++) {
            LinearLayout line = new LinearLayout(this);
            for (int col = 0; col < 2; col++) {
                int index = row * 2 + col;
                Button option = button(modeNames[index], false, false);
                modeButtons[index] = option;
                LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, dp(48), 1);
                if (col == 1) cell.leftMargin = dp(8);
                line.addView(option, cell);
                option.setOnClickListener(
                        view -> {
                            values[mode] = parameter.getText().toString();
                            mode = index;
                            configureMode();
                            persistOptions();
                        });
            }
            add(options, line, row == 0 ? 12 : 8);
        }
        fieldLabel = text("", 16, INK);
        fieldLabel.setTypeface(null, Typeface.BOLD);
        add(options, fieldLabel, 22);
        parameter = new EditText(this);
        parameter.setTextColor(INK);
        parameter.setTextSize(28);
        parameter.setPadding(dp(14), dp(8), dp(14), dp(8));
        parameter.setBackground(shape(PAPER, 12, 0xffDAE3D9));
        parameter.setSelectAllOnFocus(true);
        parameter.setSingleLine(true);
        add(options, parameter, 10);
        fieldHelp = text("", 12, MUTED);
        add(options, fieldHelp, 8);
        parameter.addTextChangedListener(
                new TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int a, int c, int f) {}

                    public void afterTextChanged(Editable e) {}

                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                        values[mode] = s.toString();
                        updatePlan();
                        persistOptions();
                    }
                });
        previewTitle = text("", 19, INK);
        previewTitle.setTypeface(null, Typeface.BOLD);
        add(options, previewTitle, 20);
        timeline = new ClipTimeline(this);
        LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(-1, dp(22));
        bar.topMargin = dp(12);
        options.addView(timeline, bar);
        previewNote = text("", 13, MUTED);
        add(options, previewNote, 10);
        previewButton = button("View clip times", false, false);
        add(options, previewButton, 12);
        previewButton.setOnClickListener(view -> showTimes());
        folderButton = button("Choose output folder", false, false);
        add(editor, folderButton, 16);
        folderButton.setOnClickListener(
                view ->
                        startActivityForResult(
                                new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                                        .addFlags(
                                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                                                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                                        | Intent
                                                                .FLAG_GRANT_PERSISTABLE_URI_PERMISSION),
                                11));
        folderLabel =
                text(
                        folder == null
                                ? "Use a local folder with room for your exported clips."
                                : folderName(folder),
                        12,
                        MUTED);
        add(editor, folderLabel, 6);
        error = text("", 13, 0xffA33C32);
        add(editor, error, 12);
        LinearLayout footer = column();
        footer.setPadding(dp(22), dp(8), dp(22), dp(14));
        shell.addView(footer);
        split = button("Split video", true, false);
        add(footer, split, 0);
        split.setOnClickListener(view -> begin());
        configureMode();
        if (source != null) showSource();
        updatePlan();
    }

    private void configureMode() {
        String[] labels = {
            "Number of clips", "Seconds per clip", "Approximate MB per clip", "Start new clips at"
        };
        String[] help = {
            "Clips are balanced across the closest keyframes.",
            "The final clip keeps the remaining video.",
            "Estimated from average bitrate. File sizes can vary.",
            "Enter positions in the original video, separated by commas. Each position starts a new"
                + " clip.\n\n"
                + "For example, 00:30, 01:20 creates three clips.\n"
                + "• Beginning → 00:30\n"
                + "• 00:30 → 01:20\n"
                + "• 01:20 → end of video\n\n"
                + "Use mm:ss or hh:mm:ss. Cuts snap to the closest keyframes; View clip times shows"
                + " the actual result."
        };
        fieldLabel.setText(labels[mode]);
        fieldHelp.setText(help[mode]);
        parameter.setContentDescription(labels[mode]);
        parameter.setInputType(
                mode == 3
                        ? android.text.InputType.TYPE_CLASS_TEXT
                        : android.text.InputType.TYPE_CLASS_NUMBER
                                | (mode == 0
                                        ? 0
                                        : android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL));
        parameter.setFilters(
                new InputFilter[] {new InputFilter.LengthFilter(mode == 3 ? 16000 : 9)});
        parameter.setHint(mode == 3 ? "00:30, 01:20" : "");
        parameter.setText(values[mode]);
        for (int i = 0; i < 4; i++) {
            modeButtons[i].setBackgroundTintList(ColorStateList.valueOf(i == mode ? INK : PAPER));
            modeButtons[i].setTextColor(i == mode ? PAPER : INK);
            modeButtons[i].setSelected(i == mode);
        }
        updatePlan();
    }

    private void persistOptions() {
        SharedPreferences.Editor edit = config.edit().putInt("mode", mode);
        for (int i = 0; i < 4; i++) edit.putString("value" + i, values[i]);
        edit.apply();
    }

    private void inspect() {
        if (inspection != null) inspection.cancel();
        inspection = new CancellationSignal();
        CancellationSignal current = inspection;
        Uri chosen = input;
        inspecting = true;
        source = null;
        plan = null;
        fileName.setText("Opening video…");
        fileInfo.setText("Reading video information");
        updatePlan();
        worker.execute(
                () -> {
                    try {
                        SplitEngine.Source imported =
                                SourceCache.importVideo(
                                        this,
                                        chosen,
                                        current,
                                        (p, message) ->
                                                runOnUiThread(
                                                        () -> {
                                                            if (current == inspection
                                                                    && "editor".equals(screen))
                                                                fileInfo.setText(message);
                                                        }));
                        runOnUiThread(
                                () -> {
                                    if (current != inspection || isDestroyed()) return;
                                    source = imported;
                                    inspecting = false;
                                    if ("editor".equals(screen)) {
                                        showSource();
                                        updatePlan();
                                    }
                                });
                    } catch (Exception failure) {
                        runOnUiThread(
                                () -> {
                                    if (current != inspection || isDestroyed()) return;
                                    inspecting = false;
                                    if ("editor".equals(screen)) {
                                        fileName.setText("Choose another video");
                                        fileInfo.setText(failure.getMessage());
                                        updatePlan();
                                    }
                                });
                    }
                });
    }

    private void showSource() {
        fileName.setText(source.name);
        fileInfo.setText(
                SplitEngine.time(source.durationUs)
                        + "  ·  "
                        + size(source.bytes)
                        + "  ·  "
                        + (source.codecs.contains("video/hevc") ? "H.265" : "H.264"));
        pick.setText("Change video");
    }

    private void updatePlan() {
        if (previewTitle == null || !"editor".equals(screen)) return;
        plan = null;
        String value = parameter.getText().toString().trim();
        if (source == null) {
            previewTitle.setText(inspecting ? "Preparing your video" : "Preview your clips");
            previewNote.setText(
                    inspecting
                            ? "Cut points are checked once when you import."
                            : "Choose a video to see its split preview.");
        } else
            try {
                if (mode == 0) plan = SplitEngine.plan(source, Integer.parseInt(value));
                if (mode == 1) plan = SplitEngine.planDuration(source, Double.parseDouble(value));
                if (mode == 2) plan = SplitEngine.planSize(source, Double.parseDouble(value));
                if (mode == 3) {
                    String[] parts = value.split(",", -1);
                    long[] cuts = new long[parts.length];
                    for (int i = 0; i < parts.length; i++) cuts[i] = parseTime(parts[i]);
                    plan = SplitEngine.planCuts(source, cuts);
                }
                long minimum = Long.MAX_VALUE, maximum = 0;
                double worst = 0, ideal = (double) source.durationUs / plan.count();
                for (int i = 0; i < plan.count(); i++) {
                    long length = plan.boundaries[i + 1] - plan.boundaries[i];
                    minimum = Math.min(minimum, length);
                    maximum = Math.max(maximum, length);
                    worst = Math.max(worst, Math.abs(length - ideal) / 1e6);
                }
                previewTitle.setText(plan.count() + " clips · original quality");
                String note = length(minimum) + "–" + length(maximum) + " each";
                if (mode == 0 || mode == 2)
                    note +=
                            String.format(
                                    Locale.US, "\nLargest variation from average  %.2f s", worst);
                if (mode == 3) {
                    double snap = 0;
                    String[] times = value.split(",");
                    for (int i = 0; i < times.length; i++)
                        snap =
                                Math.max(
                                        snap,
                                        Math.abs(plan.boundaries[i + 1] - parseTime(times[i]))
                                                / 1e6);
                    note += String.format(Locale.US, "\nLargest cut adjustment  %.2f s", snap);
                }
                previewNote.setText(note);
            } catch (NumberFormatException failure) {
                previewTitle.setText(
                        "Enter "
                                + (mode == 0
                                        ? "a clip count"
                                        : mode == 3 ? "valid cut times" : "a value"));
                previewNote.setText(fieldHelp.getText());
            } catch (Exception failure) {
                previewTitle.setText("Adjust your split");
                previewNote.setText(failure.getMessage());
            }
        timeline.setPlan(plan);
        timeline.setVisibility(plan == null ? View.GONE : View.VISIBLE);
        previewButton.setVisibility(plan == null ? View.GONE : View.VISIBLE);
        split.setEnabled(
                plan != null
                        && folder != null
                        && !inspecting
                        && !starting
                        && !SplitService.running);
    }

    static long parseTime(String value) {
        String[] fields = value.trim().split(":", -1);
        if (fields.length > 3 || value.trim().isEmpty()) throw new NumberFormatException();
        double total = 0;
        for (int i = 0; i < fields.length; i++) {
            double number = Double.parseDouble(fields[i]);
            if (!Double.isFinite(number)
                    || number < 0
                    || (fields.length > 1 && i > 0 && number >= 60))
                throw new NumberFormatException();
            total = total * 60 + number;
        }
        if (total > Long.MAX_VALUE / 1e6) throw new NumberFormatException();
        return Math.round(total * 1e6);
    }

    private void showTimes() {
        if (plan == null) return;
        StringBuilder times = new StringBuilder();
        for (int i = 0; i < plan.count(); i++)
            times.append(
                    String.format(
                            Locale.US,
                            "%02d    %s → %s\n",
                            i + 1,
                            SplitEngine.time(plan.boundaries[i]),
                            SplitEngine.time(plan.boundaries[i + 1])));
        TextView view = text(times.toString(), 14, INK);
        view.setPadding(dp(22), dp(12), dp(22), dp(12));
        view.setTypeface(Typeface.MONOSPACE);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(view);
        new AlertDialog.Builder(this)
                .setTitle("Clip times")
                .setView(scroll)
                .setPositiveButton("Done", null)
                .show();
    }

    private void begin() {
        if (plan == null || folder == null || starting || SplitService.running) return;
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(parameter.getWindowToken(), 0);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 20);
        starting = true;
        job.edit()
                .putString("state", "running")
                .putInt("percent", 0)
                .putString("message", "Starting export")
                .putInt("count", plan.count())
                .putLong("started", System.currentTimeMillis())
                .putString("folder", folder.toString())
                .putString("name", source.name)
                .commit();
        try {
            startForegroundService(
                    new Intent(this, SplitService.class)
                            .putExtra("input", input.toString())
                            .putExtra("folder", folder.toString())
                            .putExtra("boundaries", plan.boundaries));
            showExport(false, false);
        } catch (Exception failure) {
            starting = false;
            job.edit()
                    .putString("state", "error")
                    .putString("message", failure.getMessage())
                    .apply();
            error.setText(failure.getMessage());
            updatePlan();
        }
    }

    private void showExport(boolean done, boolean animate) {
        screen = done ? "done" : "running";
        base(true);
        LinearLayout content = column();
        content.setPadding(dp(26), dp(24), dp(26), dp(24));
        shell.addView(content, new LinearLayout.LayoutParams(-1, -1));
        brand(content, true);
        LinearLayout middle = column();
        middle.setGravity(Gravity.CENTER);
        content.addView(middle, new LinearLayout.LayoutParams(-1, 0, 1));
        artwork = new ProgressArtwork(this);
        middle.addView(artwork, new LinearLayout.LayoutParams(dp(204), dp(204)));
        exportTitle = text(done ? "Your clips are ready" : "Splitting your video", 29, PAPER);
        exportTitle.setTypeface(null, Typeface.BOLD);
        exportTitle.setGravity(Gravity.CENTER);
        add(middle, exportTitle, 20);
        exportStatus =
                text(
                        done
                                ? completedCount() + " clips saved"
                                : job.getString("message", "Starting export"),
                        16,
                        0xffBDD0C2);
        exportStatus.setGravity(Gravity.CENTER);
        exportStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(middle, exportStatus, 12);
        exportTime = text("", 13, 0xffA4BFAE);
        exportTime.setGravity(Gravity.CENTER);
        add(middle, exportTime, 8);
        TextView name = text(job.getString("name", ""), 14, 0xffA4BFAE);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        add(middle, name, 24);
        if (done) {
            artwork.complete(animate);
            long seconds =
                    Math.max(0, (job.getLong("finished", 0) - job.getLong("started", 0)) / 1000);
            exportTime.setText(
                    job.getLong("finished", 0) > 0 && job.getLong("started", 0) > 0
                            ? "Finished in " + elapsed(seconds)
                            : "");
            Button open = button("Open output folder", true, true);
            add(content, open, 12);
            open.setOnClickListener(view -> openOutput());
            Button share = button("Share clips", false, true);
            add(content, share, 8);
            share.setOnClickListener(view -> shareOutputs());
            Button another = button("Split another video", false, true);
            add(content, another, 8);
            another.setOnClickListener(view -> returnToEditor());
            if (animate) {
                middle.setAlpha(0);
                middle.setTranslationY(dp(16));
                middle.animate().alpha(1).translationY(0).setDuration(450).start();
            }
        } else {
            cancel = button("Cancel export", false, true);
            add(content, cancel, 12);
            cancel.setOnClickListener(
                    view -> {
                        startService(new Intent(this, SplitService.class).setAction("cancel"));
                        cancel.setEnabled(false);
                        exportStatus.setText("Removing incomplete clips…");
                    });
            TextView background =
                    text("You can keep using your phone while this saves.", 12, 0xffA4BFAE);
            background.setGravity(Gravity.CENTER);
            add(content, background, 12);
        }
    }

    private void refreshJob() {
        String state = job.getString("state", "");
        if (SplitService.running) starting = false;
        if ("done".equals(state) && !"done".equals(screen)) {
            starting = false;
            showExport(true, "running".equals(screen));
        } else if ("running".equals(state)
                && ("running".equals(screen) || SplitService.running || starting)) {
            if (!"running".equals(screen)) showExport(false, false);
            int percent = job.getInt("percent", 0);
            artwork.progress(percent);
            exportStatus.setText(job.getString("message", "Saving your clips"));
            exportTime.setText(
                    elapsed(
                                    Math.max(
                                            0,
                                            (System.currentTimeMillis()
                                                            - job.getLong(
                                                                    "started",
                                                                    System.currentTimeMillis()))
                                                    / 1000))
                            + " elapsed");
        } else if (("error".equals(state) || "cancelled".equals(state)) && !state.equals(lastJob)) {
            starting = false;
            if (!"editor".equals(screen)) {
                showEditor();
                if (source == null && input != null) inspect();
            }
            error.setText(job.getString("message", "Export stopped"));
        }
        lastJob = state;
    }

    private int completedCount() {
        int count = job.getInt("count", 0);
        if (count > 0) return count;
        for (String uri : job.getString("outputs", "").split("\n"))
            if (!uri.trim().isEmpty()) count++;
        return count;
    }

    private void returnToEditor() {
        job.edit().putString("state", "idle").apply();
        if (inspection != null) inspection.cancel();
        input = null;
        source = null;
        plan = null;
        inspecting = false;
        showEditor();
    }

    private Uri outputFolder() {
        String value = job.getString("folder", folder == null ? "" : folder.toString());
        return value.isEmpty() ? null : Uri.parse(value);
    }

    private void openOutput() {
        Uri output = outputFolder();
        if (output == null) return;
        try {
            startActivity(
                    new Intent(Intent.ACTION_VIEW)
                            .setDataAndType(
                                    DocumentsContract.buildDocumentUriUsingTree(
                                            output, DocumentsContract.getTreeDocumentId(output)),
                                    DocumentsContract.Document.MIME_TYPE_DIR)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (Exception failure) {
            Toast.makeText(this, "Open Files → " + folderName(output), Toast.LENGTH_LONG).show();
        }
    }

    private void shareOutputs() {
        ArrayList<Uri> outputs = new ArrayList<>();
        for (String value : job.getString("outputs", "").split("\n"))
            if (!value.trim().isEmpty()) outputs.add(Uri.parse(value.trim()));
        if (outputs.isEmpty()) return;
        Intent share =
                new Intent(Intent.ACTION_SEND_MULTIPLE)
                        .setType("video/mp4")
                        .putParcelableArrayListExtra(Intent.EXTRA_STREAM, outputs)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ClipData grants = ClipData.newUri(getContentResolver(), "Clips", outputs.get(0));
        for (int i = 1; i < outputs.size(); i++) grants.addItem(new ClipData.Item(outputs.get(i)));
        share.setClipData(grants);
        startActivity(Intent.createChooser(share, "Share clips"));
    }

    // Returned URI grants are explicitly masked to the two persistable permission flags.
    @android.annotation.SuppressLint("WrongConstant")
    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri chosen = data.getData();
        try {
            getContentResolver()
                    .takePersistableUriPermission(
                            chosen,
                            data.getFlags()
                                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
            if (request == 10) {
                input = chosen;
                error.setText("");
                inspect();
            }
            if (request == 11) {
                folder = chosen;
                config.edit().putString("folder", folder.toString()).apply();
                folderLabel.setText(folderName(folder));
                folderButton.setText("Change output folder");
                updatePlan();
            }
        } catch (Exception failure) {
            error.setText("Choose a local video or folder that the app can access.");
        }
    }

    private String folderName(Uri uri) {
        return DocumentsContract.getTreeDocumentId(uri).replace("primary:", "Internal storage / ");
    }

    private void back() {
        if ("running".equals(screen)) {
            moveTaskToBack(true);
            return;
        }
        if ("done".equals(screen)) {
            returnToEditor();
            return;
        }
        finish();
    }

    // API 29–32 use this callback. API 33+ uses the registered OnBackInvoked callback.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override
    public void onBackPressed() {
        back();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(poll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(poll);
    }

    @Override
    protected void onDestroy() {
        if (inspection != null) inspection.cancel();
        worker.shutdown();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(size);
        view.setLineSpacing(dp(3), 1);
        return view;
    }

    private void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        parent.addView(child, params);
    }

    private GradientDrawable shape(int color, int radius, int border) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (border != 0) drawable.setStroke(dp(1), border);
        return drawable;
    }

    private LinearLayout card(LinearLayout parent, int top) {
        LinearLayout view = column();
        view.setPadding(dp(18), dp(18), dp(18), dp(18));
        view.setBackground(shape(Color.WHITE, 22, 0xffE3E9DF));
        add(parent, view, top);
        return view;
    }

    private void smallLabel(LinearLayout parent, String label) {
        TextView view = text(label, 11, MUTED);
        view.setTypeface(null, Typeface.BOLD);
        view.setLetterSpacing(.12f);
        parent.addView(view);
    }

    private Button button(String label, boolean filled, boolean dark) {
        Button view = new Button(this);
        view.setText(label);
        view.setTextSize(14);
        view.setAllCaps(false);
        view.setTypeface(null, Typeface.BOLD);
        view.setMinHeight(dp(52));
        view.setMinimumHeight(dp(52));
        view.setPadding(dp(12), dp(4), dp(12), dp(4));
        int background = filled ? (dark ? LIME : GREEN) : (dark ? 0xff2A5143 : 0xffEDF2E9);
        view.setBackground(shape(background, 14, 0));
        view.setBackgroundTintList(
                new ColorStateList(
                        new int[][] {new int[] {android.R.attr.state_enabled}, new int[] {}},
                        new int[] {background, dark ? 0xff2A5143 : 0xffDCE3D9}));
        view.setTextColor(filled ? (dark ? INK : Color.WHITE) : (dark ? PAPER : INK));
        return view;
    }

    private static String size(long bytes) {
        if (bytes <= 0) return "Size unknown";
        return bytes >= 1_000_000_000
                ? String.format(Locale.US, "%.2f GB", bytes / 1e9)
                : String.format(Locale.US, "%.0f MB", bytes / 1e6);
    }

    private static String length(long us) {
        return us >= 60_000_000
                ? String.format(Locale.US, "%d:%04.1f", us / 60_000_000, (us % 60_000_000) / 1e6)
                : String.format(Locale.US, "%.2f s", us / 1e6);
    }

    private static String elapsed(long seconds) {
        return seconds < 60
                ? seconds + "s"
                : String.format(Locale.US, "%dm %02ds", seconds / 60, seconds % 60);
    }
}
