package app.clipdivide;

import android.content.Context;
import android.graphics.*;
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
        if (boundaries == null) return;
        long total = boundaries[boundaries.length - 1];
        float gap = getResources().getDisplayMetrics().density * 2;
        for (int i = 0; i < boundaries.length - 1; i++) {
            float left = (float) ((double) boundaries[i] / total * getWidth()),
                    right = (float) ((double) boundaries[i + 1] / total * getWidth());
            paint.setColor(i % 2 == 0 ? Color.rgb(29, 106, 80) : Color.rgb(126, 169, 144));
            canvas.drawRoundRect(
                    left,
                    0,
                    Math.max(left + 1, right - Math.min(gap, (right - left) * .2f)),
                    getHeight(),
                    4,
                    4,
                    paint);
        }
    }
}
