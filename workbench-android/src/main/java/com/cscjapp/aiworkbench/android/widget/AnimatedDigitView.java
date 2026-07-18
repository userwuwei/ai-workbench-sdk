package com.cscjapp.aiworkbench.android.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * 单个数字位的滚动控件。
 *
 * 它只负责 0~9 这一位数字的切换，不关心整体数字是多少。
 * 外层组合控件会把一个完整数字拆成多位，再分别交给多个 AnimatedDigitView。
 */
public class AnimatedDigitView extends FrameLayout {

    public static final int DIRECTION_UP = 1;
    public static final int DIRECTION_DOWN = -1;

    @IntDef({DIRECTION_UP, DIRECTION_DOWN})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Direction {
    }

    private final TextView currentTextView;
    private final TextView nextTextView;
    private final ValueAnimator valueAnimator;

    /**
     * 当前这一位已经显示出来的数字。
     */
    private int currentDigit = 0;

    /**
     * 数字位高度。动画本质是两个 TextView 在这个高度范围内上下平移。
     */
    private int digitHeight;
    private long animationDuration = 400L;
    private long delayStep = 80L;
    private int positionFromRight = 0;
    private int configuredWidth;
    private int configuredHeight;
    private float configuredTextSize;
    private int configuredTextColor;
    private Typeface configuredTypeface;
    private long configuredDuration;
    private long configuredDelay;
    private boolean configured;

    @Direction
    private int direction = DIRECTION_UP;

    public AnimatedDigitView(@NonNull Context context) {
        super(context);
        currentTextView = createDigitTextView(context);
        nextTextView = createDigitTextView(context);
        addView(currentTextView);
        addView(nextTextView);

        valueAnimator = ValueAnimator.ofFloat(0F, 1F);
        valueAnimator.setInterpolator(new OvershootInterpolator());
        valueAnimator.addUpdateListener(animation -> applyAnimationFrame((float) animation.getAnimatedValue()));
    }

    private TextView createDigitTextView(Context context) {
        TextView textView = new TextView(context);
        textView.setGravity(Gravity.CENTER);
        textView.setText(String.valueOf(0));
        textView.setIncludeFontPadding(false);
        LayoutParams layoutParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        layoutParams.gravity = Gravity.CENTER;
        textView.setLayoutParams(layoutParams);
        return textView;
    }

    /**
     * 统一配置单个数字位的尺寸和外观。
     */
    public void configure(int widthPx,
                          int heightPx,
                          float textSizePx,
                          @ColorInt int textColor,
                          @NonNull Typeface typeface,
                          long duration,
                          long perDigitDelay) {
        if (configured
                && configuredWidth == widthPx
                && configuredHeight == heightPx
                && Float.compare(configuredTextSize, textSizePx) == 0
                && configuredTextColor == textColor
                && configuredTypeface == typeface
                && configuredDuration == duration
                && configuredDelay == perDigitDelay) {
            return;
        }
        configured = true;
        configuredWidth = widthPx;
        configuredHeight = heightPx;
        configuredTextSize = textSizePx;
        configuredTextColor = textColor;
        configuredTypeface = typeface;
        configuredDuration = duration;
        configuredDelay = perDigitDelay;
        digitHeight = heightPx;
        animationDuration = duration;
        delayStep = perDigitDelay;

        LayoutParams textParams = new LayoutParams(widthPx, heightPx);
        textParams.gravity = Gravity.CENTER;

        currentTextView.setLayoutParams(textParams);
        currentTextView.setTextColor(textColor);
        currentTextView.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx);
        currentTextView.setTypeface(typeface);

        LayoutParams nextParams = new LayoutParams(widthPx, heightPx);
        nextParams.gravity = Gravity.CENTER;
        nextTextView.setLayoutParams(nextParams);
        nextTextView.setTextColor(textColor);
        nextTextView.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx);
        nextTextView.setTypeface(typeface);

        resetToStartPosition();
    }

    /**
     * 右侧最低位最先滚动，越靠左延迟越大。
     */
    public void setPositionFromRight(int positionFromRight) {
        this.positionFromRight = Math.max(positionFromRight, 0);
    }

    /**
     * 直接设置当前数字，不播放动画。
     */
    public void setDigitImmediately(int digit) {
        currentDigit = digit;
        currentTextView.setText(String.valueOf(digit));
        nextTextView.setText(String.valueOf(digit));
        cancelAnimation();
        currentTextView.setTranslationY(0F);
        nextTextView.setTranslationY(0F);
    }

    /**
     * 将这一位数字滚动到新的值。
     */
    public void setDigit(int digit, @Direction int direction, boolean animate) {
        if (!animate) {
            setDigitImmediately(digit);
            return;
        }
        if (digit == currentDigit) {
            return;
        }

        this.direction = direction;
        currentTextView.setText(String.valueOf(currentDigit));
        nextTextView.setText(String.valueOf(digit));

        cancelAnimation();
        resetToStartPosition();
        valueAnimator.setStartDelay(positionFromRight * delayStep);
        valueAnimator.setDuration(animationDuration);
        valueAnimator.start();
        currentDigit = digit;
    }

    private void resetToStartPosition() {
        currentTextView.setTranslationY(0F);
        if (direction == DIRECTION_UP) {
            nextTextView.setTranslationY(digitHeight);
            return;
        }
        nextTextView.setTranslationY(-digitHeight);
    }

    /**
     * 根据动画进度实时调整两个数字层的位置。
     */
    private void applyAnimationFrame(float fraction) {
        if (direction == DIRECTION_UP) {
            currentTextView.setTranslationY(-digitHeight * fraction);
            nextTextView.setTranslationY(digitHeight - digitHeight * fraction);
            return;
        }
        currentTextView.setTranslationY(digitHeight * fraction);
        nextTextView.setTranslationY(-digitHeight + digitHeight * fraction);
    }

    public void cancelAnimation() {
        if (valueAnimator.isStarted()) {
            valueAnimator.cancel();
        }
    }
}
