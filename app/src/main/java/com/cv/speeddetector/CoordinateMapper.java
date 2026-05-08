package com.cv.speeddetector;

import android.graphics.Rect;

public class CoordinateMapper {

    public static Rect map(Rect box,
                           int imageWidth, int imageHeight,
                           int rotationDegrees,
                           float viewWidth, float viewHeight) {

        // FIX 1: When the camera image is rotated 90° or 270°, its logical
        // width and height are swapped relative to the display. ML Kit reports
        // bounding-box coordinates in the *rotated* (display-aligned) space
        // when InputImage is built with a rotation parameter, so we must swap
        // imageWidth/imageHeight to get the correct aspect ratio for scaling.
        float rotatedW = (rotationDegrees == 90 || rotationDegrees == 270)
                ? imageHeight : imageWidth;
        float rotatedH = (rotationDegrees == 90 || rotationDegrees == 270)
                ? imageWidth  : imageHeight;

        // FIX 2: PreviewView defaults to FILL_CENTER scale type, which means
        // it scales the stream to fill the shorter dimension and crops the
        // longer one (no letterboxing). We must mirror that: use the *larger*
        // scale factor so both axes fill, then centre the result.
        float scale   = Math.max(viewWidth / rotatedW, viewHeight / rotatedH);
        float offsetX = (viewWidth  - rotatedW * scale) / 2f;
        float offsetY = (viewHeight - rotatedH * scale) / 2f;

        int l = (int) (box.left   * scale + offsetX);
        int t = (int) (box.top    * scale + offsetY);
        int r = (int) (box.right  * scale + offsetX);
        int b = (int) (box.bottom * scale + offsetY);

        // FIX 3: Clamp to view bounds so boxes never go off-screen and cause
        // incorrect touch-hit tests or drawing artefacts.
        l = Math.max(0, Math.min(l, (int) viewWidth));
        t = Math.max(0, Math.min(t, (int) viewHeight));
        r = Math.max(0, Math.min(r, (int) viewWidth));
        b = Math.max(0, Math.min(b, (int) viewHeight));

        // FIX 4: Guarantee l < r and t < b after clamping (degenerate boxes
        // would crash Rect consumers or produce invisible hit areas).
        if (l >= r) r = l + 1;
        if (t >= b) b = t + 1;

        return new Rect(l, t, r, b);
    }
}