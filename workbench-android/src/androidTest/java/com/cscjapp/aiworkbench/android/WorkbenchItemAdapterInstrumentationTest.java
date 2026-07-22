package com.cscjapp.aiworkbench.android;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.chad.library.adapter.base.BaseViewHolder;
import com.cscjapp.aiworkbench.android.widget.AnimatedNumberView;
import java.util.ArrayList;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class WorkbenchItemAdapterInstrumentationTest {
  @Test
  public void counterPayloadDoesNotRebindTitleOrContent() {
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () -> {
              Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
              WorkbenchUiItem item = WorkbenchUiItem.reason("原始标题", "原始正文");
              item.showProgressCounter = true;
              item.progressCounterValue = 1L;
              ArrayList<WorkbenchUiItem> data = new ArrayList<>();
              data.add(item);
              WorkbenchItemAdapter adapter = new WorkbenchItemAdapter(data);
              ViewGroup parent = new FrameLayout(context);
              BaseViewHolder holder =
                  adapter.onCreateViewHolder(parent, WorkbenchUiItem.TYPE_REASON);
              adapter.onBindViewHolder(holder, 0);

              item.title = "不应绑定的新标题";
              item.content = "不应绑定的新正文";
              item.progressCounterValue = 2L;
              adapter.onBindViewHolder(
                  holder,
                  0,
                  Collections.singletonList(
                      new WorkbenchStreamPayload(WorkbenchStreamPayload.COUNTER)));

              TextView title = holder.getView(R.id.aiw_tvSummaryTitle);
              TextView content = holder.getView(R.id.aiw_tvSummaryContent);
              AnimatedNumberView number = holder.getView(R.id.aiw_anvSummaryCounter);
              assertEquals("原始标题", title.getText().toString());
              assertEquals("原始正文", content.getText().toString());
              assertEquals(2L, number.getCurrentNumber());
            });
  }
}
