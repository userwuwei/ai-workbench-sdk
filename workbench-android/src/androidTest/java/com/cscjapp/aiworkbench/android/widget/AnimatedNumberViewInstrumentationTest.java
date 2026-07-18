package com.cscjapp.aiworkbench.android.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class AnimatedNumberViewInstrumentationTest {
  @Test
  public void oneViewInstanceAcceptsContinuousIncreasingStreamValues() {
    AtomicReference<AnimatedNumberView> original = new AtomicReference<>();
    AtomicReference<AnimatedNumberView> afterUpdates = new AtomicReference<>();
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () -> {
              Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
              AnimatedNumberView view = new AnimatedNumberView(context);
              original.set(view);
              view.setNumber(1L, false);
              view.setNumber(12L);
              view.setNumber(128L);
              afterUpdates.set(view);
            });

    assertSame(original.get(), afterUpdates.get());
    assertEquals(128L, original.get().getCurrentNumber());
  }

  @Test
  public void highFrequencyUpdatesQueueOnlyLatestTargetWithoutRestartingCurrentAnimation() {
    AtomicReference<AnimatedNumberView> result = new AtomicReference<>();
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () -> {
              Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
              AnimatedNumberView view = new AnimatedNumberView(context);
              view.setNumber(1L, false);
              for (long value = 2L; value <= 100_000L; value++) view.setNumber(value);
              assertEquals(1, view.animationStartCountForTest());
              view.finishTransitionForTest();
              assertEquals(2, view.animationStartCountForTest());
              view.finishTransitionForTest();
              result.set(view);
            });

    assertEquals(100_000L, result.get().getCurrentNumber());
    assertTrue(!result.get().isTransitionRunningForTest());
    assertEquals(2, result.get().animationStartCountForTest());
  }
}
