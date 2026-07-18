package com.cscjapp.aiworkbench.android.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

public class DashedCircleLoadingView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();
    private ValueAnimator animator;
    private float rotationDegrees = 0f;

    public DashedCircleLoadingView(Context context) {
        super(context);
        init(context);
    }

    public DashedCircleLoadingView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public DashedCircleLoadingView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(context, 2));
        paint.setColor(Color.parseColor("#86EFAC"));
        paint.setPathEffect(new DashPathEffect(new float[]{dp(context, 5), dp(context, 4)}, 0));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float inset = paint.getStrokeWidth() + 1f;
        arcRect.set(inset, inset, getWidth() - inset, getHeight() - inset);
        canvas.save();
        canvas.rotate(rotationDegrees, getWidth() / 2f, getHeight() / 2f);
        canvas.drawOval(arcRect, paint);
        canvas.restore();
    }

    public void start() {
        if (animator != null && animator.isRunning()) {
            return;
        }
        animator = ValueAnimator.ofFloat(0f, 360f);
        animator.setDuration(900L);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            rotationDegrees = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    public void stop() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        rotationDegrees = 0f;
        invalidate();
    }

    private int dp(Context context, int value) {
        float density = context.getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }
}
