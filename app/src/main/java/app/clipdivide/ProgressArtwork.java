package app.clipdivide;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** An accessible progress ring that transforms into a drawn checkmark on completion. */
final class ProgressArtwork extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float progress, reveal;
    private boolean complete;
    private ValueAnimator animation;

    ProgressArtwork(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void progress(int value) {
        progress = Math.max(progress, value);
        invalidate();
    }

    void complete(boolean animate) {
        complete = true;
        progress = 100;
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
            reveal = 1;
            invalidate();
            return;
        }
        animation = ValueAnimator.ofFloat(0, 1);
        animation.setDuration(750);
        animation.setInterpolator(new DecelerateInterpolator());
        animation.addUpdateListener(
                value -> {
                    reveal = (float) value.getAnimatedValue();
                    invalidate();
                });
        animation.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animation != null) animation.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight()),
                x = getWidth() / 2f,
                y = getHeight() / 2f,
                radius = size * .36f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(size * .035f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(Color.rgb(49, 89, 75));
        canvas.drawCircle(x, y, radius, paint);
        paint.setColor(Color.rgb(216, 247, 117));
        canvas.drawArc(
                new RectF(x - radius, y - radius, x + radius, y + radius),
                -90,
                complete ? 360 : Math.max(3, progress * 3.6f),
                false,
                paint);
        if (complete) {
            Path check = new Path();
            check.moveTo(x - radius * .43f, y);
            check.lineTo(x - radius * .08f, y + radius * .33f);
            check.lineTo(x + radius * .48f, y - radius * .3f);
            PathMeasure measure = new PathMeasure(check, false);
            Path portion = new Path();
            measure.getSegment(0, measure.getLength() * reveal, portion, true);
            paint.setStrokeWidth(size * .045f);
            canvas.drawPath(portion, paint);
            for (int i = 0; i < 8; i++) {
                double angle = Math.PI * 2 * i / 8;
                float inner = radius * (1.28f + reveal * .15f),
                        outer = inner + size * .045f * reveal;
                paint.setAlpha(Math.round(200 * (1 - reveal)));
                canvas.drawLine(
                        x + (float) Math.cos(angle) * inner,
                        y + (float) Math.sin(angle) * inner,
                        x + (float) Math.cos(angle) * outer,
                        y + (float) Math.sin(angle) * outer,
                        paint);
            }
            paint.setAlpha(255);
        } else {
            paint.setStyle(Paint.Style.FILL);
            paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            paint.setTextSize(size * .18f);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(
                    Math.round(progress) + "%",
                    x,
                    y - (paint.ascent() + paint.descent()) / 2,
                    paint);
        }
    }
}
