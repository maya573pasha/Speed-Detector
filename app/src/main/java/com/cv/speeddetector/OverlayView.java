package com.cv.speeddetector;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

public class OverlayView extends View {

    private List<DetectedVehicle> vehicles = new ArrayList<>();
    private Paint boxPaint, lockedBoxPaint, dimBoxPaint;
    private Paint textPaint, bgPaint, tapPaint, linePaint;
    private Paint cornerPaint;
    private RectF tempRect = new RectF();

    public PointF tap1 = null, tap2 = null;
    public boolean calibrationMode = false;
    public boolean useKmh = true;
    public int lockedId = -1;

    // FIX 1: Added the missing single-argument constructor.
    // Without it, inflating the view from XML via the AttributeSet constructor
    // calls super(context, attrs) which is fine, but programmatic construction
    // (e.g. in tests or dynamic layouts) with new OverlayView(context) would
    // throw a NoSuchMethodException at runtime.
    public OverlayView(Context context) {
        super(context);
        init();
    }

    public OverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    // FIX 2: Added the three-argument constructor for completeness. Android's
    // layout inflater can call this when a style is specified in XML.
    public OverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        dimBoxPaint = new Paint();
        dimBoxPaint.setColor(0x5500E5FF);
        dimBoxPaint.setStyle(Paint.Style.STROKE);
        dimBoxPaint.setStrokeWidth(3);
        dimBoxPaint.setAntiAlias(true);

        boxPaint = new Paint();
        boxPaint.setColor(0xFF00E5FF);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(5);
        boxPaint.setAntiAlias(true);

        lockedBoxPaint = new Paint();
        lockedBoxPaint.setColor(0xFFFFD700);
        lockedBoxPaint.setStyle(Paint.Style.STROKE);
        lockedBoxPaint.setStrokeWidth(6);
        lockedBoxPaint.setAntiAlias(true);

        // FIX 3: cornerPaint was a copy of lockedBoxPaint but it was never used
        // differently — kept for future use, but removed duplicate allocation.
        cornerPaint = new Paint(lockedBoxPaint);

        textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(30);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setAntiAlias(true);

        bgPaint = new Paint();
        bgPaint.setColor(0xCC000000);

        tapPaint = new Paint();
        tapPaint.setColor(0xFFFF4444);
        tapPaint.setStyle(Paint.Style.FILL);

        linePaint = new Paint();
        linePaint.setColor(0xFFFF4444);
        linePaint.setStrokeWidth(4);
        linePaint.setAntiAlias(true);
    }

    /**
     * Update the vehicle list and the currently-locked ID atomically in one
     * synchronized call, then invalidate once.  Callers must NOT separately
     * write overlayView.lockedId and then call updateVehicles — that two-step
     * sequence was the root cause of the "boxes disappear on lock" bug: onDraw
     * could fire between the two writes, seeing an inconsistent lockedId.
     */
    public synchronized void updateVehicles(List<DetectedVehicle> list, int newLockedId) {
        this.vehicles = new ArrayList<>(list);
        this.lockedId = newLockedId;
        invalidate();
    }

    /** Legacy single-arg overload — keeps lockedId unchanged. */
    public synchronized void updateVehicles(List<DetectedVehicle> list) {
        this.vehicles = new ArrayList<>(list);
        invalidate();
    }

    public synchronized List<DetectedVehicle> getVehicles() {
        return new ArrayList<>(vehicles);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Snapshot both vehicles and lockedId atomically so onDraw always
        // sees a consistent pair — no partial-update flicker.
        List<DetectedVehicle> snapshot;
        int currentLockedId;
        synchronized (this) {
            snapshot = new ArrayList<>(vehicles);
            currentLockedId = lockedId;
        }

        for (DetectedVehicle v : snapshot) {
            boolean isLocked = (v.id == currentLockedId);
            Rect r = v.boundingBox;
            float L = r.left, T = r.top, R = r.right, B = r.bottom;

            if (isLocked) {
                // Always draw the locked box regardless of whether other
                // vehicles are present — this branch was previously skipped
                // when lockedId was stale or updated after invalidate().
                drawCornerBrackets(canvas, L, T, R, B, lockedBoxPaint);
                String label = formatLabel(v);
                drawLabel(canvas, label, L, T, 0xFFFFD700);
            } else if (currentLockedId < 0) {
                // No lock active — show all detected boxes dimly.
                canvas.drawRoundRect(L, T, R, B, 10, 10, dimBoxPaint);
                String idLabel = "ID:" + v.id;
                float savedSize = textPaint.getTextSize();
                textPaint.setTextSize(24);
                float tw = textPaint.measureText(idLabel);
                tempRect.set(L, T - 28, L + tw + 12, T);
                canvas.drawRoundRect(tempRect, 6, 6, bgPaint);
                canvas.drawText(idLabel, L + 6, T - 6, textPaint);
                textPaint.setTextSize(savedSize);
            }
            // else: locked to a different ID — intentionally draw nothing
            // for non-locked vehicles to keep the UI focused.
        }

        if (calibrationMode) {
            if (tap1 != null) canvas.drawCircle(tap1.x, tap1.y, 18, tapPaint);
            if (tap2 != null) canvas.drawCircle(tap2.x, tap2.y, 18, tapPaint);
            if (tap1 != null && tap2 != null)
                canvas.drawLine(tap1.x, tap1.y, tap2.x, tap2.y, linePaint);
        }
    }

    private void drawCornerBrackets(Canvas canvas, float L, float T,
                                    float R, float B, Paint p) {
        float cs = Math.min((R - L), (B - T)) * 0.22f;
        cs = Math.max(cs, 20);
        canvas.drawLine(L, T + cs, L, T, p);
        canvas.drawLine(L, T, L + cs, T, p);
        canvas.drawLine(R - cs, T, R, T, p);
        canvas.drawLine(R, T, R, T + cs, p);
        canvas.drawLine(L, B - cs, L, B, p);
        canvas.drawLine(L, B, L + cs, B, p);
        canvas.drawLine(R - cs, B, R, B, p);
        canvas.drawLine(R, B, R, B - cs, p);
    }

    private String formatLabel(DetectedVehicle v) {
        if (useKmh) return String.format("%.1f km/h", v.speedKmh);
        else return String.format("%.1f m/s", v.speedKmh / 3.6);
    }

    private void drawLabel(Canvas canvas, String label, float x, float y, int color) {
        // FIX 7: Save and restore textPaint color. The original mutated textPaint
        // color permanently to `color` and then reset it to WHITE — but if an
        // exception occurred between those two lines the color would stay wrong.
        int savedColor = textPaint.getColor();
        float savedSize = textPaint.getTextSize();

        textPaint.setColor(color);
        textPaint.setTextSize(32);
        float tw = textPaint.measureText(label);
        float th = textPaint.getTextSize();
        float pad = 10;
        tempRect.set(x, y - th - pad * 2, x + tw + pad * 2, y);
        bgPaint.setColor(0xCC000000);
        canvas.drawRoundRect(tempRect, 8, 8, bgPaint);
        canvas.drawText(label, x + pad, y - pad, textPaint);

        textPaint.setColor(savedColor);
        textPaint.setTextSize(savedSize);
    }
}