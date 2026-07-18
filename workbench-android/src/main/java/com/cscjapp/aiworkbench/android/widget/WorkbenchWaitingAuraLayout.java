package com.cscjapp.aiworkbench.android.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 等待光效专用叠层容器。
 *
 * 内容子 View 决定整体尺寸，WorkbenchWaitingAuraView 只覆盖最终尺寸，不参与 wrap_content 测量。
 */
public class WorkbenchWaitingAuraLayout extends FrameLayout {

    public WorkbenchWaitingAuraLayout(@NonNull Context context) {
        super(context);
    }

    public WorkbenchWaitingAuraLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public WorkbenchWaitingAuraLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int maxWidth = 0;
        int maxHeight = 0;
        int childState = 0;
        int count = getChildCount();
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child instanceof WorkbenchWaitingAuraView || child.getVisibility() == GONE) {
                continue;
            }
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0);
            LayoutParams params = (LayoutParams) child.getLayoutParams();
            maxWidth = Math.max(maxWidth, child.getMeasuredWidth() + params.leftMargin + params.rightMargin);
            maxHeight = Math.max(maxHeight, child.getMeasuredHeight() + params.topMargin + params.bottomMargin);
            childState = combineMeasuredStates(childState, child.getMeasuredState());
        }
        maxWidth += getPaddingLeft() + getPaddingRight();
        maxHeight += getPaddingTop() + getPaddingBottom();
        maxWidth = Math.max(maxWidth, getSuggestedMinimumWidth());
        maxHeight = Math.max(maxHeight, getSuggestedMinimumHeight());
        setMeasuredDimension(resolveSizeAndState(maxWidth, widthMeasureSpec, childState),
                resolveSizeAndState(maxHeight, heightMeasureSpec, childState << MEASURED_HEIGHT_STATE_SHIFT));

        int auraWidthSpec = MeasureSpec.makeMeasureSpec(
                Math.max(0, getMeasuredWidth() - getPaddingLeft() - getPaddingRight()),
                MeasureSpec.EXACTLY);
        int auraHeightSpec = MeasureSpec.makeMeasureSpec(
                Math.max(0, getMeasuredHeight() - getPaddingTop() - getPaddingBottom()),
                MeasureSpec.EXACTLY);
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child instanceof WorkbenchWaitingAuraView && child.getVisibility() != GONE) {
                child.measure(auraWidthSpec, auraHeightSpec);
            }
        }
    }
}
