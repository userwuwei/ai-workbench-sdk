package com.cscjapp.aiworkbench.android.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;


import com.cscjapp.aiworkbench.android.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 通用数字滚动控件。
 *
 * 和原项目里的温度/时间组合控件不同，这个类只做一件事：
 * 展示一个普通整数，并在数值变化时让每一位数字执行错峰滚动动画。
 *
 * 适用场景：
 * 1. 金额、积分、计数器
 * 2. 温度、转速、步数等普通数字展示
 * 3. 任何不想被 HH:MM:SS 固定格式限制的数字动画场景
 */
public class AnimatedNumberView extends LinearLayout {

    private static final String DEFAULT_FONT_ASSET_PATH = "fonts/Consolas.ttf";
    private static final int DEFAULT_DIGIT_WIDTH_DP = 40;
    private static final int DEFAULT_DIGIT_HEIGHT_DP = 64;
    private static final int DEFAULT_DIGIT_SPACING_DP = 2;
    private static final int DEFAULT_TEXT_SIZE_SP = 40;
    private static final long DEFAULT_ANIMATION_DURATION = 400L;
    private static final long DEFAULT_PER_DIGIT_DELAY = 60L;

    private final List<AnimatedDigitView> digitViews = new ArrayList<>();
    private TextView signView;

    private int digitWidthPx;
    private int digitHeightPx;
    private int digitSpacingPx;
    private float textSizePx;
    private int textColor;
    private long animationDuration;
    private long perDigitDelay;
    private int minDigits;
    private Typeface digitTypeface = Typeface.MONOSPACE;

    /**
     * 当前控件最终确认展示出来的数值。
     */
    private long currentNumber = 0L;
    private long displayedNumber = 0L;
    private long transitionTargetNumber = 0L;

    /**
     * 是否已经初始化过数值。
     * 第一次赋值时通常直接显示，不做动画。
     */
    private boolean hasInitialized = false;

    /**
     * 某些场景下位数会缩短，例如 100 -> 99。
     * 为了先把滚动动画做完整，这里会暂时保留 3 位，动画结束后再裁成 2 位。
     */
    private Runnable transitionCompletion;
    private boolean transitionRunning;
    private int animationStartCount;

    public AnimatedNumberView(@NonNull Context context) {
        this(context, null);
    }

    public AnimatedNumberView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AnimatedNumberView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        initAttributes(context, attrs);
        rebuildChildren(getDigitCount(0), false);
        settleImmediately(0L);
    }

    private void initAttributes(Context context, @Nullable AttributeSet attrs) {
        TypedArray array = context.obtainStyledAttributes(attrs, R.styleable.AiwAnimatedNumberView);
        digitWidthPx = array.getDimensionPixelSize(
                R.styleable.AiwAnimatedNumberView_aiw_animatedNumber_digitWidth,
                dp(context, DEFAULT_DIGIT_WIDTH_DP));
        digitHeightPx = array.getDimensionPixelSize(
                R.styleable.AiwAnimatedNumberView_aiw_animatedNumber_digitHeight,
                dp(context, DEFAULT_DIGIT_HEIGHT_DP));
        digitSpacingPx = array.getDimensionPixelSize(
                R.styleable.AiwAnimatedNumberView_aiw_animatedNumber_digitSpacing,
                dp(context, DEFAULT_DIGIT_SPACING_DP));
        textSizePx = array.getDimension(
                R.styleable.AiwAnimatedNumberView_aiw_animatedNumber_textSize,
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                        DEFAULT_TEXT_SIZE_SP,
                        getResources().getDisplayMetrics()));
        textColor = array.getColor(
                R.styleable.AiwAnimatedNumberView_aiw_animatedNumber_textColor,
                Color.WHITE);
        animationDuration = array.getInt(
                R.styleable.AiwAnimatedNumberView_aiw_animatedNumber_duration,
                (int) DEFAULT_ANIMATION_DURATION);
        perDigitDelay = array.getInt(
                R.styleable.AiwAnimatedNumberView_aiw_animatedNumber_perDigitDelay,
                (int) DEFAULT_PER_DIGIT_DELAY);
        minDigits = Math.max(1, array.getInt(
                R.styleable.AiwAnimatedNumberView_aiw_animatedNumber_minDigits,
                1));
        digitTypeface = resolveDigitTypeface(context, array);
        array.recycle();
    }

    private Typeface resolveDigitTypeface(Context context, TypedArray array) {
        String assetPath = array.getString(R.styleable.AiwAnimatedNumberView_aiw_animatedNumber_fontAssetPath);
        if (TextUtils.isEmpty(assetPath)) {
            assetPath = DEFAULT_FONT_ASSET_PATH;
        }
        try {
            return Typeface.createFromAsset(context.getAssets(), assetPath);
        } catch (RuntimeException ignore) {
            return Typeface.MONOSPACE;
        }
    }

    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /**
     * 设置新的数字，并执行滚动动画。
     */
    public void setNumber(long number) {
        boolean shouldAnimate = hasInitialized;
        setNumberInternal(number, shouldAnimate, true);
    }

    /**
     * 设置新的数字，可自行决定是否播放动画。
     */
    public void setNumber(long number, boolean animate) {
        setNumberInternal(number, animate && hasInitialized, true);
    }

    public long getCurrentNumber() {
        return currentNumber;
    }

    /**
     * 当你想做“只刷新样式、不改数值”时可以调用这个方法。
     */
    public void refreshCurrentNumber() {
        setNumberInternal(currentNumber, false, false);
    }

    private void setNumberInternal(long number, boolean animate, boolean updateCurrentNumber) {
        if (updateCurrentNumber && hasInitialized && number == currentNumber) {
            return;
        }
        if (updateCurrentNumber) {
            currentNumber = number;
            hasInitialized = true;
        }
        long target = updateCurrentNumber ? currentNumber : number;
        if (!animate) {
            cancelTransition(false);
            settleImmediately(target);
            return;
        }
        if (transitionRunning) {
            return;
        }
        startTransition(displayedNumber, target);
    }

    private void startTransition(long oldNumber, long targetNumber) {
        if (oldNumber == targetNumber) {
            settleImmediately(targetNumber);
            return;
        }
        int oldDigitCount = getDigitCount(oldNumber);
        int targetDigitCount = getDigitCount(targetNumber);
        int transitionDigitCount = Math.max(minDigits, Math.max(oldDigitCount, targetDigitCount));
        boolean negative = targetNumber < 0;
        boolean rebuilt = ensureStructure(transitionDigitCount, negative);

        String oldDigits = formatDigits(oldNumber, transitionDigitCount);
        String targetDigits = formatDigits(targetNumber, transitionDigitCount);

        int direction = targetNumber >= oldNumber
                ? AnimatedDigitView.DIRECTION_UP
                : AnimatedDigitView.DIRECTION_DOWN;

        for (int index = 0; index < digitViews.size(); index++) {
            AnimatedDigitView digitView = digitViews.get(index);
            int oldDigit = oldDigits.charAt(index) - '0';
            int newDigit = targetDigits.charAt(index) - '0';
            int positionFromRight = transitionDigitCount - 1 - index;
            digitView.setPositionFromRight(positionFromRight);
            if (rebuilt) {
                digitView.setDigitImmediately(oldDigit);
            }
            digitView.setDigit(newDigit, direction, true);
        }
        transitionTargetNumber = targetNumber;
        transitionRunning = true;
        animationStartCount++;
        long totalDuration =
                animationDuration + perDigitDelay * Math.max(transitionDigitCount - 1, 0);
        transitionCompletion = this::completeTransition;
        postDelayed(transitionCompletion, totalDuration);
    }

    private void completeTransition() {
        transitionCompletion = null;
        long completedTarget = transitionTargetNumber;
        transitionRunning = false;
        settleImmediately(completedTarget);
        if (currentNumber != displayedNumber) {
            startTransition(displayedNumber, currentNumber);
        }
    }

    private void settleImmediately(long number) {
        int digitCount = getDigitCount(number);
        ensureStructure(digitCount, number < 0);
        String digits = formatDigits(number, digitCount);
        for (int index = 0; index < digitViews.size(); index++) {
            digitViews.get(index).setDigitImmediately(digits.charAt(index) - '0');
        }
        displayedNumber = number;
        transitionTargetNumber = number;
    }

    /**
     * 保证子 View 结构和目标数字匹配。
     *
     * 当前仅支持普通整数，所以结构只分两部分：
     * 1. 可选的负号
     * 2. 多个数字位
     */
    private boolean ensureStructure(int digitCount, boolean negative) {
        boolean needRebuild = digitViews.size() != digitCount;
        if (!needRebuild) {
            if (negative && signView == null) {
                needRebuild = true;
            }
            if (!negative && signView != null) {
                needRebuild = true;
            }
        }
        if (needRebuild) {
            rebuildChildren(digitCount, negative);
        }
        return needRebuild;
    }

    private void rebuildChildren(int digitCount, boolean negative) {
        removeAllViews();
        digitViews.clear();
        signView = null;

        if (negative) {
            signView = createSignTextView();
            addView(signView);
        }

        for (int index = 0; index < digitCount; index++) {
            AnimatedDigitView digitView = new AnimatedDigitView(getContext());
            digitViews.add(digitView);
            addView(digitView);
        }

        applyChildStyle();
    }

    private void applyChildStyle() {
        if (signView != null) {
            LayoutParams signParams = new LayoutParams(LayoutParams.WRAP_CONTENT, digitHeightPx);
            signParams.rightMargin = digitSpacingPx;
            signView.setLayoutParams(signParams);
            signView.setTextColor(textColor);
            signView.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx);
            signView.setTypeface(digitTypeface);
        }

        for (int index = 0; index < digitViews.size(); index++) {
            AnimatedDigitView digitView = digitViews.get(index);
            LayoutParams params = new LayoutParams(digitWidthPx, digitHeightPx);
            if (index < digitViews.size() - 1) {
                params.rightMargin = digitSpacingPx;
            }
            digitView.setLayoutParams(params);
            digitView.configure(digitWidthPx,
                    digitHeightPx,
                    textSizePx,
                    textColor,
                    digitTypeface,
                    animationDuration,
                    perDigitDelay);
        }
    }

    private TextView createSignTextView() {
        TextView textView = new TextView(getContext());
        textView.setText("-");
        textView.setGravity(Gravity.CENTER);
        textView.setIncludeFontPadding(false);
        return textView;
    }

    private String formatDigits(long number, int digitCount) {
        String raw = Long.toString(number);
        String absolute = raw.charAt(0) == '-' ? raw.substring(1) : raw;
        if (absolute.length() >= digitCount) return absolute;
        StringBuilder padded = new StringBuilder(digitCount);
        for (int index = absolute.length(); index < digitCount; index++) padded.append('0');
        return padded.append(absolute).toString();
    }

    private int getDigitCount(long number) {
        String raw = Long.toString(number);
        int digits = raw.charAt(0) == '-' ? raw.length() - 1 : raw.length();
        return Math.max(minDigits, digits);
    }

    private void cancelTransition(boolean settleLatest) {
        if (transitionCompletion != null) {
            removeCallbacks(transitionCompletion);
            transitionCompletion = null;
        }
        transitionRunning = false;
        for (AnimatedDigitView digitView : digitViews) digitView.cancelAnimation();
        if (settleLatest) settleImmediately(currentNumber);
    }

    /**
     * 释放动画，避免离开页面后还有回调继续执行。
     */
    public void cancelAnimations() {
        cancelTransition(true);
    }

    int animationStartCountForTest() {
        return animationStartCount;
    }

    boolean isTransitionRunningForTest() {
        return transitionRunning;
    }

    void finishTransitionForTest() {
        if (transitionCompletion == null) return;
        removeCallbacks(transitionCompletion);
        completeTransition();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelAnimations();
    }
}
