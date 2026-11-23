package app.clipdivide;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

final class ClipTimeline extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long[] boundaries;

    ClipTimeline(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setPlan(SplitEngine.Plan plan) {
        boundaries = plan == null ? null : plan.boundaries;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (boundaries == null || boundaries.length < 2) return;
        long total = boundaries[boundaries.length - 1];
        if (total <= 0) return;
        float density = getResources().getDisplayMetrics().density;
        float gap = density * 2.5f;
        float radius = density * 5f;
        int w = getWidth();
        int h = getHeight();
        for (int i = 0; i < boundaries.length - 1; i++) {
            float left = (float) ((double) boundaries[i] / total * w);
            float right = (float) ((double) boundaries[i + 1] / total * w);
            float segGap = Math.min(gap, (right - left) * 0.25f);
            paint.setColor(i % 2 == 0 ? Color.rgb(29, 106, 80) : Color.rgb(138, 181, 156));
            canvas.drawRoundRect(
                    left,
                    0,
                    Math.max(left + density, right - (i < boundaries.length - 2 ? segGap : 0)),
                    h,
                    radius,
                    radius,
                    paint);
        }
    }
}
