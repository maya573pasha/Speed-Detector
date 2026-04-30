package com.cv.speeddetector;

import android.graphics.Rect;

public class DetectedVehicle {
    public int id;
    public Rect boundingBox;
    public double speedKmh;

    public DetectedVehicle(int id, Rect box, double speed) {
        this.id = id;
        this.boundingBox = box;
        this.speedKmh = speed;
    }
}