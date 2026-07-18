package com.cscjapp.aiworkbench.android;

import android.graphics.Color;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.Nullable;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.cscjapp.aiworkbench.api.WorkbenchContextItem;
import java.util.List;

/** Exact reference context-chip renderer with host-owned removal behavior. */
final class ContextLabelAdapter extends BaseQuickAdapter<WorkbenchContextItem, BaseViewHolder> {
  interface OnRemoveListener { void onRemove(WorkbenchContextItem item); }

  private OnRemoveListener onRemoveListener;

  ContextLabelAdapter(@Nullable List<WorkbenchContextItem> data) {
    super(R.layout.aiw_item_context_file, data);
  }

  @Override
  protected void convert(BaseViewHolder helper, WorkbenchContextItem item) {
    TextView textView = helper.getView(R.id.aiw_text_context_file);
    TextView removeView = helper.getView(R.id.aiw_text_remove_context_file);
    textView.setText(item.label());
    textView.setTextColor(Color.WHITE);
    textView.setAlpha(1f);
    removeView.setVisibility(onRemoveListener == null ? View.GONE : View.VISIBLE);
    removeView.setOnClickListener(v -> {
      if (onRemoveListener != null) onRemoveListener.onRemove(item);
    });
  }

  void setOnRemoveListener(OnRemoveListener listener) { onRemoveListener = listener; }
}
