package com.cscjapp.aiworkbench.android.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

import com.cscjapp.aiworkbench.android.R;

/**
 * AI 工作台等待态卡片动效。
 *
 * 只负责绘制可复用的等待光效，不承载任何业务进度语义。
 */
public class WorkbenchWaitingAuraView extends View {

    private static final int DEFAULT_PRIMARY_COLOR = Color.rgb(116, 235, 213);
    private static final int DEFAULT_SECONDARY_COLOR = Color.rgb(143, 183, 255);
    private static final int DEFAULT_SWEEP_COLOR = Color.rgb(134, 239, 172);
    private static final long DEFAULT_BORDER_DURATION = 2200L;
    private static final long DEFAULT_SWEEP_DURATION = 3200L;
    private static final float DEFAULT_GLOW_ALPHA = 0.46F;
    private static final float DEFAULT_SWEEP_ALPHA = 0.08F;
    private static final float BASE_BORDER_ALPHA = 0.10F;
    private static final float GLOW_ALPHA_SCALE = 0.32F;
    private static final float BORDER_SEGMENT_RATIO = 0.15F;
    private static final float SWEEP_MIN_WIDTH_DP = 32F;
    private static final float SWEEP_WIDTH_RATIO = 0.18F;
    private static final float PULSE_ALPHA_BASE = 0.88F;
    private static final float PULSE_ALPHA_RANGE = 0.12F;

    private final Paint baseBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF contentRect = new RectF();
    private final Path borderPath = new Path();
    private final Path segmentPath = new Path();
    private final Path clipPath = new Path();
    private final Path sweepPath = new Path();
    private final PathMeasure pathMeasure = new PathMeasure();
    private final Matrix sweepMatrix = new Matrix();

    private float cornerRadius;
    private float borderWidth;
    private long borderDuration;
    private long sweepDuration;
    private int primaryColor;
    private int secondaryColor;
    private int sweepColor;
    private float glowAlpha;
    private float sweepAlpha;
    private boolean active = false;
    private long animationStartMs = 0L;
    private ValueAnimator animator;
    private LinearGradient sweepGradient;
    private float borderLength;
    private float borderSegmentLength;
    private float sweepTravelDistance;
    private float sweepStartOffset;
    private float sweepPathOffset;
    private boolean geometryReady;
    private boolean windowVisible = true;
    private boolean aggregatedVisible = true;
    private int geometryBuildCount;
    private int shaderBuildCount;

    public WorkbenchWaitingAuraView(Context context) {
        this(context, null);
    }

    public WorkbenchWaitingAuraView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public WorkbenchWaitingAuraView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initAttributes(context, attrs);
        initPaints();
        initAnimator();
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setTranslationZ(dp(6));
    }

    private void initAttributes(Context context, @Nullable AttributeSet attrs) {
        TypedArray array = context.obtainStyledAttributes(attrs, R.styleable.AiwWorkbenchWaitingAuraView);
        cornerRadius = array.getDimension(
                R.styleable.AiwWorkbenchWaitingAuraView_aiw_waitingAura_cornerRadius,
                dp(10));
        borderWidth = array.getDimension(
                R.styleable.AiwWorkbenchWaitingAuraView_aiw_waitingAura_borderWidth,
                dp(1));
        borderDuration = Math.max(300L, array.getInt(
                R.styleable.AiwWorkbenchWaitingAuraView_aiw_waitingAura_borderDuration,
                (int) DEFAULT_BORDER_DURATION));
        sweepDuration = Math.max(300L, array.getInt(
                R.styleable.AiwWorkbenchWaitingAuraView_aiw_waitingAura_sweepDuration,
                (int) DEFAULT_SWEEP_DURATION));
        primaryColor = array.getColor(
                R.styleable.AiwWorkbenchWaitingAuraView_aiw_waitingAura_primaryColor,
                DEFAULT_PRIMARY_COLOR);
        secondaryColor = array.getColor(
                R.styleable.AiwWorkbenchWaitingAuraView_aiw_waitingAura_secondaryColor,
                DEFAULT_SECONDARY_COLOR);
        sweepColor = array.getColor(
                R.styleable.AiwWorkbenchWaitingAuraView_aiw_waitingAura_sweepColor,
                DEFAULT_SWEEP_COLOR);
        glowAlpha = clampAlpha(array.getFloat(
                R.styleable.AiwWorkbenchWaitingAuraView_aiw_waitingAura_glowAlpha,
                DEFAULT_GLOW_ALPHA));
        sweepAlpha = clampAlpha(array.getFloat(
                R.styleable.AiwWorkbenchWaitingAuraView_aiw_waitingAura_sweepAlpha,
                DEFAULT_SWEEP_ALPHA));
        array.recycle();
    }

    private void initPaints() {
        baseBorderPaint.setStyle(Paint.Style.STROKE);
        baseBorderPaint.setStrokeWidth(Math.max(1F, borderWidth));
        baseBorderPaint.setColor(applyAlpha(secondaryColor, BASE_BORDER_ALPHA));

        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setStrokeJoin(Paint.Join.ROUND);
        glowPaint.setStrokeWidth(Math.max(borderWidth * 2F, dp(2)));
        glowPaint.setColor(applyAlpha(primaryColor, glowAlpha * GLOW_ALPHA_SCALE));

        highlightPaint.setStyle(Paint.Style.STROKE);
        highlightPaint.setStrokeCap(Paint.Cap.ROUND);
        highlightPaint.setStrokeJoin(Paint.Join.ROUND);
        highlightPaint.setStrokeWidth(Math.max(borderWidth, dp(1)));
        highlightPaint.setColor(applyAlpha(primaryColor, glowAlpha));

        sweepPaint.setStyle(Paint.Style.FILL);
    }

    private void initAnimator() {
        animator = ValueAnimator.ofFloat(0F, 1F);
        animator.setDuration(Math.max(borderDuration, sweepDuration));
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> invalidate());
    }

    public void setActive(boolean active) {
        setActive(active, 0L);
    }

    public void setActive(boolean active, long startMs) {
        boolean startChanged = active && startMs > 0L && animationStartMs != startMs;
        if (this.active == active && !startChanged) {
            if (active) {
                startAnimatorIfNeeded();
            }
            return;
        }
        this.active = active;
        if (active) {
            if (startMs > 0L) {
                animationStartMs = startMs;
            } else if (animationStartMs <= 0L) {
                animationStartMs = SystemClock.uptimeMillis();
            }
            startAnimatorIfNeeded();
        } else {
            stopAnimator();
        }
        invalidate();
    }

    public void resetAnimationPhase() {
        animationStartMs = 0L;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (active) {
            startAnimatorIfNeeded();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimator();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        windowVisible = visibility == VISIBLE;
        updateAnimatorState();
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        aggregatedVisible = isVisible;
        updateAnimatorState();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateAnimatorState();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        rebuildGeometry(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!active || !geometryReady) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        drawSweep(canvas, now);
        drawBorder(canvas, now);
    }

    private void rebuildGeometry(int width, int height) {
        geometryReady = width > 0 && height > 0;
        sweepPaint.setShader(null);
        sweepGradient = null;
        if (!geometryReady) {
            borderLength = 0F;
            borderSegmentLength = 0F;
            return;
        }
        geometryBuildCount++;
        float inset = Math.max(glowPaint.getStrokeWidth(), highlightPaint.getStrokeWidth()) * 0.5F + 1F;
        contentRect.set(inset, inset, width - inset, height - inset);

        borderPath.reset();
        borderPath.addRoundRect(contentRect, cornerRadius, cornerRadius, Path.Direction.CW);
        clipPath.reset();
        clipPath.addRoundRect(contentRect, cornerRadius, cornerRadius, Path.Direction.CW);

        pathMeasure.setPath(borderPath, true);
        borderLength = pathMeasure.getLength();
        borderSegmentLength = borderLength * BORDER_SEGMENT_RATIO;

        float bandWidth = Math.max(dp(SWEEP_MIN_WIDTH_DP), width * SWEEP_WIDTH_RATIO);
        float skew = height * 0.42F;
        sweepStartOffset = -bandWidth;
        sweepTravelDistance = width + bandWidth * 2F;
        sweepPath.reset();
        sweepPath.moveTo(skew, 0F);
        sweepPath.lineTo(bandWidth + skew, 0F);
        sweepPath.lineTo(bandWidth, height);
        sweepPath.lineTo(0F, height);
        sweepPath.close();
        sweepPathOffset = 0F;

        int transparent = applyAlpha(sweepColor, 0F);
        int body = applyAlpha(sweepColor, sweepAlpha);
        sweepGradient = new LinearGradient(
                0F,
                0F,
                bandWidth + skew,
                0F,
                new int[]{transparent, body, transparent},
                new float[]{0F, 0.5F, 1F},
                Shader.TileMode.CLAMP);
        shaderBuildCount++;
        sweepPaint.setShader(sweepGradient);
    }

    private void drawSweep(Canvas canvas, long now) {
        if (sweepGradient == null) return;
        float left = sweepStartOffset + sweepTravelDistance * resolvePhase(sweepDuration, now);
        sweepPath.offset(left - sweepPathOffset, 0F);
        sweepPathOffset = left;
        sweepMatrix.setTranslate(left, 0F);
        sweepGradient.setLocalMatrix(sweepMatrix);
        int save = canvas.save();
        canvas.clipPath(clipPath);
        canvas.drawPath(sweepPath, sweepPaint);
        canvas.restoreToCount(save);
    }

    private void drawBorder(Canvas canvas, long now) {
        canvas.drawPath(borderPath, baseBorderPaint);
        if (borderLength <= 0F) {
            return;
        }
        float start = resolvePhase(borderDuration, now) * borderLength;
        float end = start + borderSegmentLength;

        segmentPath.reset();
        appendSegment(borderLength, start, end);
        float pulse = resolvePulse(now);
        glowPaint.setColor(applyAlpha(primaryColor, glowAlpha * GLOW_ALPHA_SCALE * pulse));
        highlightPaint.setColor(applyAlpha(primaryColor, glowAlpha * pulse));
        canvas.drawPath(segmentPath, glowPaint);
        canvas.drawPath(segmentPath, highlightPaint);
    }

    private void appendSegment(float length, float start, float end) {
        if (end <= length) {
            pathMeasure.getSegment(start, end, segmentPath, true);
            return;
        }
        pathMeasure.getSegment(start, length, segmentPath, true);
        pathMeasure.getSegment(0F, end - length, segmentPath, true);
    }

    private float resolvePhase(long duration, long now) {
        long startMs = animationStartMs > 0L ? animationStartMs : SystemClock.uptimeMillis();
        long elapsedMs = Math.max(0L, now - startMs);
        return (elapsedMs % duration) / (float) duration;
    }

    private float resolvePulse(long now) {
        double radians = resolvePhase(borderDuration, now) * Math.PI * 2D;
        return PULSE_ALPHA_BASE + PULSE_ALPHA_RANGE * (float) ((Math.sin(radians) + 1D) * 0.5D);
    }

    private void startAnimatorIfNeeded() {
        if (!canAnimate()) {
            return;
        }
        if (animator.isStarted()) {
            return;
        }
        animator.start();
    }

    private void stopAnimator() {
        if (animator.isStarted()) {
            animator.cancel();
        }
    }

    private void updateAnimatorState() {
        if (canAnimate()) startAnimatorIfNeeded();
        else stopAnimator();
    }

    private boolean canAnimate() {
        return active
                && isAttachedToWindow()
                && getVisibility() == VISIBLE
                && windowVisible
                && aggregatedVisible;
    }

    int geometryBuildCountForTest() {
        return geometryBuildCount;
    }

    int shaderBuildCountForTest() {
        return shaderBuildCount;
    }

    boolean isAnimatorRunningForTest() {
        return animator.isRunning();
    }

    private float clampAlpha(float value) {
        return Math.max(0F, Math.min(1F, value));
    }

    private int applyAlpha(int color, float alpha) {
        int resolvedAlpha = Math.round(Color.alpha(color) * clampAlpha(alpha));
        return Color.argb(resolvedAlpha, Color.red(color), Color.green(color), Color.blue(color));
    }
    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

}
