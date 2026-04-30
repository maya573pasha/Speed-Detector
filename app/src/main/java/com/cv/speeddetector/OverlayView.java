package com.cv.speeddetector;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

public class OverlayView extends View {

    private List<DetectedVehicle> vehicles = new ArrayList<>();
    private Paint boxPaint, textPaint, bgPaint, tapPaint, linePaint;

    public PointF tap1 = null, tap2 = null;
    public boolean calibrationMode = false;

    public OverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        boxPaint = new Paint();
        boxPaint.setColor(Color.GREEN);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(4);

        textPaint = new Paint();
        textPaint.setColor(Color.YELLOW);
        textPaint.setTextSize(40);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);

        bgPaint = new Paint();
        bgPaint.setColor(Color.argb(160, 0, 0, 0));

        tapPaint = new Paint();
        tapPaint.setColor(Color.RED);
        tapPaint.setStyle(Paint.Style.FILL);

        linePaint = new Paint();
        linePaint.setColor(Color.RED);
        linePaint.setStrokeWidth(3);
        linePaint.setPathEffect(new DashPathEffect(new float[]{20, 10}, 0));
    }

    public void updateVehicles(List<DetectedVehicle> list) {
        this.vehicles = list;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        for (DetectedVehicle v : vehicles) {
            // Get Rect bounds - using Android's Rect (left, top, right, bottom)
            int left = v.boundingBox.left;
            int top = v.boundingBox.top;
            int right = v.boundingBox.right;
            int bottom = v.boundingBox.bottom;

            // Draw bounding box
            canvas.drawRect(left, top, right, bottom, boxPaint);

            // Draw speed label
            String label = String.format("ID%d  %.1f km/h", v.id, v.speedKmh);
            float textWidth = textPaint.measureText(label);

            // Draw background behind text
            canvas.drawRect(left, top - 50, left + textWidth + 10, top, bgPaint);

            // Draw text
            canvas.drawText(label, left + 5, top - 10, textPaint);
        }

        // Draw calibration points and line if in calibration mode
        if (calibrationMode) {
            if (tap1 != null) {
                canvas.drawCircle(tap1.x, tap1.y, 18, tapPaint);
            }
            if (tap2 != null) {
                canvas.drawCircle(tap2.x, tap2.y, 18, tapPaint);
            }
            if (tap1 != null && tap2 != null) {
                canvas.drawLine(tap1.x, tap1.y, tap2.x, tap2.y, linePaint);
            }
        }
    }
}