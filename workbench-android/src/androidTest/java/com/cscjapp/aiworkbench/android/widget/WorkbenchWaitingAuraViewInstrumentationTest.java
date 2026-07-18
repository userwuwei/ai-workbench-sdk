package com.cscjapp.aiworkbench.android.widget;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class WorkbenchWaitingAuraViewInstrumentationTest {
  @Test
  public void stableDrawsReuseGeometryAndGradient() {
    AtomicReference<int[]> counts = new AtomicReference<>();
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () -> {
              Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
              WorkbenchWaitingAuraView view = new WorkbenchWaitingAuraView(context);
              view.layout(0, 0, 720, 180);
              view.setActive(true, 1L);
              Bitmap bitmap = Bitmap.createBitmap(720, 180, Bitmap.Config.ARGB_8888);
              Canvas canvas = new Canvas(bitmap);
              int geometry = view.geometryBuildCountForTest();
              int shaders = view.shaderBuildCountForTest();
              for (int index = 0; index < 300; index++) view.draw(canvas);
              counts.set(
                  new int[] {
                    geometry,
                    shaders,
                    view.geometryBuildCountForTest(),
                    view.shaderBuildCountForTest()
                  });
              bitmap.recycle();
            });

    assertEquals(counts.get()[0], counts.get()[2]);
    assertEquals(counts.get()[1], counts.get()[3]);
    assertEquals(1, counts.get()[0]);
    assertEquals(1, counts.get()[1]);
  }
}
