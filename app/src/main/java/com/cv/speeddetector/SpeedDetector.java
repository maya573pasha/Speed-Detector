package com.cv.speeddetector;

import android.graphics.Rect;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SpeedDetector {

    private Map<Integer, TrackingInfo> trackedObjects = new HashMap<>();
    private int nextId = 0;
    private double scaleMetresPerPixel = 0.01;

    public List<DetectedVehicle> detectVehicles(List<Rect> boundingBoxes) {
        List<DetectedVehicle> results = new ArrayList<>();
        long currentTime = System.currentTimeMillis();

        Map<Integer, TrackingInfo> matchedObjects = new HashMap<>();

        for (Rect box : boundingBoxes) {
            int centerX = box.centerX();
            int centerY = box.centerY();

            // Find matching tracked object
            int matchId = -1;
            double minDist = 150;

            for (Map.Entry<Integer, TrackingInfo> entry : trackedObjects.entrySet()) {
                double dist = Math.hypot(
                        centerX - entry.getValue().lastX,
                        centerY - entry.getValue().lastY
                );
                if (dist < minDist) {
                    minDist = dist;
                    matchId = entry.getKey();
                }
            }

            double speed = 0;
            if (matchId == -1) {
                matchId = nextId++;
            } else {
                TrackingInfo info = trackedObjects.get(matchId);
                double pixelDisplacement = Math.hypot(
                        centerX - info.lastX,
                        centerY - info.lastY
                );
                double dt = (currentTime - info.lastSeen) / 1000.0;

                if (dt > 0 && dt < 0.5) {
                    double realMetres = pixelDisplacement * scaleMetresPerPixel;
                    speed = (realMetres / dt) * 3.6;

                    info.speedHistory.add(speed);
                    if (info.speedHistory.size() > 5) {
                        info.speedHistory.remove(0);
                    }
                    speed = info.speedHistory.stream()
                            .mapToDouble(d -> d).average().orElse(0);
                }
            }

            TrackingInfo info = new TrackingInfo();
            info.lastX = centerX;
            info.lastY = centerY;
            info.lastSeen = currentTime;
            matchedObjects.put(matchId, info);

            DetectedVehicle vehicle = new DetectedVehicle(matchId, box, speed);
            results.add(vehicle);
        }

        trackedObjects = matchedObjects;
        return results;
    }

    public void setScale(double metresPerPixel) {
        this.scaleMetresPerPixel = metresPerPixel;
    }

    private static class TrackingInfo {
        int lastX;
        int lastY;
        long lastSeen;
        List<Double> speedHistory = new ArrayList<>();
    }
}