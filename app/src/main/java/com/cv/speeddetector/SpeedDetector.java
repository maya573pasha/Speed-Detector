package com.cv.speeddetector;

import android.graphics.Rect;
import java.util.*;

public class SpeedDetector {

    private final Map<Integer, TrackingInfo> trackedObjects = new HashMap<>();
    private int nextId = 0;
    private double scaleMetresPerPixel = 0.01;
    private int lockedId = -1;

    private double sessionMaxSpeed = 0;
    private double sessionSpeedSum = 0;
    private int sessionSpeedCount  = 0;

    public void setViewSize(int w, int h) { }

    public void lockTarget(int id)  { this.lockedId = id; }
    public int  getLockedId()       { return lockedId; }

    public double getSessionMaxSpeed() { return sessionMaxSpeed; }
    public double getSessionAvgSpeed() {
        return sessionSpeedCount > 0 ? sessionSpeedSum / sessionSpeedCount : 0;
    }

    public void resetSession() {
        sessionMaxSpeed = sessionSpeedSum = 0;
        sessionSpeedCount = 0;
        trackedObjects.clear();
        nextId   = 0;
        lockedId = -1;
    }

    public synchronized List<DetectedVehicle> detectVehicles(List<Rect> boxes) {
        long now = System.currentTimeMillis();
        Set<Integer> claimed = new HashSet<>();

        for (Rect box : boxes) {
            int cx = box.centerX(), cy = box.centerY();

            // nearest unmatched track within 300 px
            int    bestId   = -1;
            double bestDist = 300;
            for (Map.Entry<Integer, TrackingInfo> e : trackedObjects.entrySet()) {
                if (claimed.contains(e.getKey())) continue;
                double d = Math.hypot(cx - e.getValue().lastX, cy - e.getValue().lastY);
                if (d < bestDist) { bestDist = d; bestId = e.getKey(); }
            }

            TrackingInfo info;
            if (bestId == -1) {
                bestId = nextId++;
                info   = new TrackingInfo();
                trackedObjects.put(bestId, info);
            } else {
                info = trackedObjects.get(bestId);
                claimed.add(bestId);

                double distPx = Math.hypot(cx - info.lastX, cy - info.lastY);
                double dt     = (now - info.lastSeen) / 1000.0;

                if (dt > 0 && dt <= 0.5 && distPx > 2) {
                    double raw = (distPx * scaleMetresPerPixel / dt) * 3.6;
                    info.speedHistory.add(raw);
                    if (info.speedHistory.size() > 6) info.speedHistory.remove(0);
                }
            }

            info.lastX    = cx;
            info.lastY    = cy;
            info.lastSeen = now;
            info.lastBox  = box;

            double speed = info.speedHistory.isEmpty() ? 0
                    : info.speedHistory.stream().mapToDouble(d -> d).average().orElse(0);

            if (bestId == lockedId && speed > 0) {
                if (speed > sessionMaxSpeed) sessionMaxSpeed = speed;
                sessionSpeedSum += speed;
                sessionSpeedCount++;
            }
        }

        // Remove tracks not seen for >1 second
        trackedObjects.entrySet().removeIf(e -> now - e.getValue().lastSeen > 1000);

        // Return ALL current tracks so every box stays visible on screen
        List<DetectedVehicle> results = new ArrayList<>();
        for (Map.Entry<Integer, TrackingInfo> e : trackedObjects.entrySet()) {
            TrackingInfo info = e.getValue();
            double speed = info.speedHistory.isEmpty() ? 0
                    : info.speedHistory.stream().mapToDouble(d -> d).average().orElse(0);
            results.add(new DetectedVehicle(e.getKey(), info.lastBox, speed));
        }
        return results;
    }

    /** Returns the current smoothed speed of the locked target, or -1 if no lock. */
    public double getLockedSpeed() {
        if (lockedId < 0 || !trackedObjects.containsKey(lockedId)) return -1;
        TrackingInfo info = trackedObjects.get(lockedId);
        if (info.speedHistory.isEmpty()) return 0;
        return info.speedHistory.stream().mapToDouble(d -> d).average().orElse(0);
    }

    public List<Double> getLockedSpeedHistory() {
        if (lockedId >= 0 && trackedObjects.containsKey(lockedId))
            return new ArrayList<>(trackedObjects.get(lockedId).speedHistory);
        return new ArrayList<>();
    }

    public void setScale(double s) { if (s > 0) scaleMetresPerPixel = s; }

    private static class TrackingInfo {
        int lastX, lastY;
        long lastSeen;
        Rect lastBox = new Rect();
        List<Double> speedHistory = new ArrayList<>();
    }
}