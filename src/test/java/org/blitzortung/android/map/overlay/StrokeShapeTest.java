package org.blitzortung.android.map.overlay;

import android.graphics.Canvas;
import android.graphics.Paint;
import com.xtremelabs.robolectric.RobolectricTestRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.mockito.Matchers.anyFloat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@RunWith(RobolectricTestRunner.class)
public class StrokeShapeTest {

    @Mock
    private Canvas canvas;

    @Mock
    private Paint paint;

    private StrokeShape strokeShape;

    private final int color = 0x102030;

    private final int width = 12;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        strokeShape = new StrokeShape(width, color);
    }

    @Test
    public void testDraw()
    {
        strokeShape.draw(canvas, paint);

        verify(paint, times(1)).setColor(color);
        verify(paint, times(1)).setStrokeWidth(width/4);
        verify(canvas, times(2)).drawLine(anyFloat(), anyFloat(), anyFloat(), anyFloat(), eq(paint));
    }

}