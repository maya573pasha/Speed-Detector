package com.cv.speeddetector;

import android.Manifest;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.*;
import android.view.MotionEvent;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private CameraHelper cameraHelper;
    private SpeedDetector speedDetector;

    private androidx.camera.view.PreviewView previewView;
    private OverlayView overlayView;

    private TextView tvSpeed, tvSpeedUnit, tvMaxSpeed, tvAvgSpeed;
    private TextView tvCalibStatus, tvTargetInfo;
    private TextView tvObjectCount, tvScaleVal, tvSessionTime, tvHistory;

    private Button btnCalibrate, btnStartStop, btnSave, btnGetStarted, btnUnit, btnLock;
    private View[] graphBars = new View[8];
    private View cardTarget, startOverlay;

    private boolean detecting = false;
    private boolean calibrationMode = false;
    private boolean useKmh = true;

    private int cachedViewW = 0, cachedViewH = 0;

    private Handler timerHandler = new Handler(Looper.getMainLooper());
    private long sessionStartMs = 0;
    private Runnable timerRunnable;

    private static class SpeedRecord {
        int id; double speedKmh; String time;
        SpeedRecord(int id, double s, String t) { this.id = id; speedKmh = s; time = t; }
    }
    private List<SpeedRecord> savedRecords = new ArrayList<>();

    // Calibration
    private float[] tapX = new float[2], tapY = new float[2];
    private int tapCount = 0;
    private double knownMetres = 3.5;

    private static final String[] CALIB_LABELS = {
            "Lane width (3.5 m)",
            "Car length (4.5 m)",
            "Car width (2.0 m)",
            "Bus length (12.0 m)",
            "Custom distance…"
    };
    private static final double[] CALIB_METRES = {3.5, 4.5, 2.0, 12.0, -1};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView   = findViewById(R.id.previewView);
        overlayView   = findViewById(R.id.overlayView);
        tvSpeed       = findViewById(R.id.tvSpeed);
        tvSpeedUnit   = findViewById(R.id.tvSpeedUnit);
        tvMaxSpeed    = findViewById(R.id.tvMaxSpeed);
        tvAvgSpeed    = findViewById(R.id.tvAvgSpeed);
        tvCalibStatus = findViewById(R.id.tvCalibStatus);
        tvTargetInfo  = findViewById(R.id.tvTargetInfo);
        tvObjectCount = findViewById(R.id.tvObjectCount);
        tvScaleVal    = findViewById(R.id.tvScaleVal);
        tvSessionTime = findViewById(R.id.tvSessionTime);
        tvHistory     = findViewById(R.id.tvHistory);
        btnCalibrate  = findViewById(R.id.btnCalibrate);
        btnStartStop  = findViewById(R.id.btnStartStop);
        btnSave       = findViewById(R.id.btnSave);
        btnGetStarted = findViewById(R.id.btnGetStarted);
        btnUnit       = findViewById(R.id.btnUnit);
        btnLock       = findViewById(R.id.btnLock);
        cardTarget    = findViewById(R.id.cardTarget);
        startOverlay  = findViewById(R.id.startOverlay);

        int[] barIds = {R.id.bar0, R.id.bar1, R.id.bar2, R.id.bar3,
                R.id.bar4, R.id.bar5, R.id.bar6, R.id.bar7};
        for (int i = 0; i < 8; i++) graphBars[i] = findViewById(barIds[i]);

        speedDetector = new SpeedDetector();
        cameraHelper  = new CameraHelper();

        previewView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or2, ob) -> {
            cachedViewW = previewView.getWidth();
            cachedViewH = previewView.getHeight();
            if (cachedViewW > 0 && cachedViewH > 0) {
                speedDetector.setViewSize(cachedViewW, cachedViewH);
            }
        });

        previewView.post(() -> {
            if (previewView.getWidth() > 0) {
                cachedViewW = previewView.getWidth();
                cachedViewH = previewView.getHeight();
                speedDetector.setViewSize(cachedViewW, cachedViewH);
            }
        });

        btnUnit.setOnClickListener(v -> {
            useKmh = !useKmh;
            overlayView.useKmh = useKmh;
            String unit = useKmh ? "km/h" : "m/s";
            btnUnit.setText(useKmh ? "KM/H" : "M/S");
            tvSpeedUnit.setText(unit);
            updateStatsUI(new ArrayList<>());
            updateHistoryUI();
            overlayView.invalidate();
        });

        btnLock.setOnClickListener(v -> {
            if (speedDetector.getLockedId() >= 0) {
                speedDetector.lockTarget(-1);
                overlayView.updateVehicles(overlayView.getVehicles(), -1);
                btnLock.setText("TAP TO LOCK");
                cardTarget.setVisibility(View.GONE);
                tvSpeed.setText("--");
            } else {
                Toast.makeText(this, "Tap an object on screen to lock",
                        Toast.LENGTH_SHORT).show();
            }
        });

        btnGetStarted.setOnClickListener(v -> startOverlay.setVisibility(View.GONE));

        btnSave.setOnClickListener(v -> {
            int lid = speedDetector.getLockedId();
            // FIX 1: Original code saved even when no target was locked (lid < 0).
            // Guard so we only save when there is an active locked target.
            if (lid >= 0) {
                double kmh = speedDetector.getSessionMaxSpeed();
                // FIX 2: Don't save a 0 km/h record — it just means no speed
                // data was collected yet (e.g. object was just locked).
                if (kmh <= 0) {
                    Toast.makeText(this, "No speed data yet — keep tracking",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                String time = android.text.format.DateFormat
                        .format("HH:mm", System.currentTimeMillis()).toString();
                savedRecords.add(0, new SpeedRecord(lid, kmh, time));
                if (savedRecords.size() > 8) savedRecords.remove(savedRecords.size() - 1);
                updateHistoryUI();
                Toast.makeText(this, "Record saved!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Lock a target first", Toast.LENGTH_SHORT).show();
            }
        });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, 100);
        } else {
            startCamera();
        }

        btnCalibrate.setOnClickListener(v -> {
            if (!calibrationMode) {
                showCalibrationPresetDialog();
            } else {
                cancelCalibration();
            }
        });

        btnStartStop.setOnClickListener(v -> {
            detecting = !detecting;
            Toast.makeText(this, detecting ? "Detection Started" : "Detection Stopped",
                    Toast.LENGTH_SHORT).show();
            if (detecting) {
                btnStartStop.setText("STOP");
                btnStartStop.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(0xFFE53935));
                sessionStartMs = SystemClock.elapsedRealtime();
                speedDetector.resetSession();
                overlayView.updateVehicles(new ArrayList<>(), -1);
                startSessionTimer();
            } else {
                btnStartStop.setText("START");
                btnStartStop.setBackgroundTintList(null);
                tvSpeed.setText("--");
                overlayView.updateVehicles(new ArrayList<>(), -1);
                speedDetector.lockTarget(-1);
                btnLock.setText("TAP TO LOCK");
                cardTarget.setVisibility(View.GONE);
                stopSessionTimer();
            }
        });

        // FIX 3: overlayView.setOnTouchListener must call view.performClick()
        // when it consumes the event (ACTION_DOWN returning true) to satisfy
        // accessibility requirements and avoid the "performClick not called"
        // lint warning that suppresses accessibility events.
        overlayView.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (calibrationMode) {
                    handleCalibrationTap(event.getX(), event.getY());
                    view.performClick();
                    return true;
                }
                if (detecting && speedDetector.getLockedId() < 0) {
                    float x = event.getX(), y = event.getY();
                    // FIX 4: getVehicles() now returns a snapshot copy (fixed in
                    // OverlayView) so iterating here is safe from CME.
                    for (DetectedVehicle v : overlayView.getVehicles()) {
                        Rect expanded = new Rect(
                                v.boundingBox.left   - 80, v.boundingBox.top    - 80,
                                v.boundingBox.right  + 80, v.boundingBox.bottom + 80);
                        if (expanded.contains((int) x, (int) y)) {
                            speedDetector.lockTarget(v.id);
                            overlayView.updateVehicles(overlayView.getVehicles(), v.id);
                            btnLock.setText("UNLOCK");
                            tvTargetInfo.setText("Locked: ID #" + v.id);
                            cardTarget.setVisibility(View.VISIBLE);
                            view.performClick();
                            return true;
                        }
                    }
                }
            }
            return false;
        });
    }

    // ─── Calibration ─────────────────────────────────────────────────────────

    private void showCalibrationPresetDialog() {
        new AlertDialog.Builder(this)
                .setTitle("What will you tap on screen?")
                .setItems(CALIB_LABELS, (dialog, which) -> {
                    if (CALIB_METRES[which] < 0) {
                        showCustomDistanceDialog();
                    } else {
                        knownMetres = CALIB_METRES[which];
                        beginCalibrationTap();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showCustomDistanceDialog() {
        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint("e.g. 3.5");

        new AlertDialog.Builder(this)
                .setTitle("Enter real-world distance (metres)")
                .setView(input)
                .setPositiveButton("OK", (d, w) -> {
                    try {
                        double parsed = Double.parseDouble(input.getText().toString());
                        // FIX 5: Reject non-positive custom distances instead of
                        // silently falling back to 3.5, which would give the user
                        // a wrong calibration with no indication of the problem.
                        if (parsed <= 0) {
                            Toast.makeText(this,
                                    "Distance must be greater than 0",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        knownMetres = parsed;
                    } catch (NumberFormatException e) {
                        Toast.makeText(this,
                                "Invalid number — using default 3.5 m",
                                Toast.LENGTH_SHORT).show();
                        knownMetres = 3.5;
                    }
                    beginCalibrationTap();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void beginCalibrationTap() {
        calibrationMode = true;
        overlayView.calibrationMode = true;
        tapCount = 0;
        overlayView.tap1 = overlayView.tap2 = null;
        tvCalibStatus.setText(String.format(Locale.US,
                "Tap START of %.1f m distance", knownMetres));
        tvCalibStatus.setTextColor(0xFF00E5FF);
        btnCalibrate.setText("CANCEL");
        overlayView.invalidate();
    }

    private void cancelCalibration() {
        calibrationMode = false;
        overlayView.calibrationMode = false;
        overlayView.tap1 = overlayView.tap2 = null;
        tapCount = 0;
        btnCalibrate.setText("CALIBRATE");
        tvCalibStatus.setText("Calibration cancelled");
        tvCalibStatus.setTextColor(0xFFFFCC00);
        overlayView.invalidate();
    }

    private void handleCalibrationTap(float x, float y) {
        if (tapCount == 0) {
            tapX[0] = x; tapY[0] = y;
            overlayView.tap1 = new android.graphics.PointF(x, y);
            tapCount = 1;
            tvCalibStatus.setText(String.format(Locale.US,
                    "Now tap END of %.1f m distance\n(tap first point to redo it)",
                    knownMetres));
            overlayView.invalidate();
        } else if (tapCount == 1) {
            float dx = x - tapX[0], dy = y - tapY[0];
            double distFromFirst = Math.sqrt(dx * dx + dy * dy);
            if (distFromFirst < 60) {
                tapX[0] = x; tapY[0] = y;
                overlayView.tap1 = new android.graphics.PointF(x, y);
                tvCalibStatus.setText("Point 1 updated — now tap END point");
                overlayView.invalidate();
                return;
            }
            tapX[1] = x; tapY[1] = y;
            overlayView.tap2 = new android.graphics.PointF(x, y);
            overlayView.invalidate();
            tapCount = 2;
            finalizeCalibration();
        }
    }

    private void finalizeCalibration() {
        double dx = tapX[1] - tapX[0], dy = tapY[1] - tapY[0];
        double px = Math.sqrt(dx * dx + dy * dy);

        if (px < 30) {
            tvCalibStatus.setText("Points too close — tap further apart");
            tvCalibStatus.setTextColor(0xFFFF4444);
            tapCount = 0;
            overlayView.tap1 = overlayView.tap2 = null;
            overlayView.invalidate();
            return;
        }

        double scale = knownMetres / px;
        speedDetector.setScale(scale);
        tvScaleVal.setText(String.format(Locale.US, "%.4f", scale));

        calibrationMode = false;
        overlayView.calibrationMode = false;
        btnCalibrate.setText("CALIBRATE");
        tapCount = 0;

        tvCalibStatus.setText(String.format(Locale.US,
                "Calibrated: %.4f m/px (%.1fm over %dpx)",
                scale, knownMetres, (int) px));
        tvCalibStatus.setTextColor(0xFF4CAF50);

        Toast.makeText(this, "Calibrated!", Toast.LENGTH_SHORT).show();
        overlayView.invalidate();
    }

    private void startSessionTimer() {
        timerRunnable = new Runnable() {
            @Override public void run() {
                long elapsed = SystemClock.elapsedRealtime() - sessionStartMs;
                long mins = elapsed / 60000, secs = (elapsed % 60000) / 1000;
                tvSessionTime.setText(String.format(Locale.US, "%02d:%02d", mins, secs));
                timerHandler.postDelayed(this, 1000);
            }
        };
        timerHandler.post(timerRunnable);
    }

    private void stopSessionTimer() {
        if (timerRunnable != null) timerHandler.removeCallbacks(timerRunnable);
        tvSessionTime.setText("00:00");
    }

    private void updateHistoryUI() {
        if (savedRecords.isEmpty()) { tvHistory.setText("No records yet"); return; }
        StringBuilder sb = new StringBuilder();
        String unit = useKmh ? "km/h" : "m/s";
        for (SpeedRecord r : savedRecords) {
            double spd = useKmh ? r.speedKmh : r.speedKmh / 3.6;
            sb.append(String.format(Locale.US,
                    "ID#%d  %.1f %s  %s\n", r.id, spd, unit, r.time));
        }
        tvHistory.setText(sb.toString().trim());
    }

    private void updateStatsUI(List<DetectedVehicle> vehicles) {
        tvObjectCount.setText(String.valueOf(vehicles.size()));
        double maxKmh = speedDetector.getSessionMaxSpeed();
        double avgKmh = speedDetector.getSessionAvgSpeed();
        if (useKmh) {
            tvMaxSpeed.setText(maxKmh > 0
                    ? String.format(Locale.US, "MAX %.1f km/h", maxKmh) : "MAX --");
            tvAvgSpeed.setText(avgKmh > 0
                    ? String.format(Locale.US, "AVG %.1f", avgKmh) : "AVG --");
        } else {
            tvMaxSpeed.setText(maxKmh > 0
                    ? String.format(Locale.US, "MAX %.1f m/s", maxKmh / 3.6) : "MAX --");
            tvAvgSpeed.setText(avgKmh > 0
                    ? String.format(Locale.US, "AVG %.1f", avgKmh / 3.6) : "AVG --");
        }
    }

    private void updateSpeedGraph(List<Double> history) {
        int n = history.size();
        double maxVal = 1;
        for (double d : history) if (d > maxVal) maxVal = d;

        for (int i = 0; i < 8; i++) {
            if (graphBars[i] == null) continue;
            int histIdx = n - 8 + i;
            float fraction = (histIdx >= 0 && histIdx < n)
                    ? (float) (history.get(histIdx) / maxVal) : 0f;
            fraction = Math.max(fraction, 0.05f);
            android.view.ViewGroup.LayoutParams lp = graphBars[i].getLayoutParams();
            // FIX 6: LayoutParams can be null before the view is measured.
            // Guard to prevent a NullPointerException on the very first frame.
            if (lp == null) continue;
            lp.height = (int) (fraction * 40);
            graphBars[i].setLayoutParams(lp);
            graphBars[i].setBackgroundColor(i == 7 ? 0xFF00E5FF : 0xFF1A4060);
        }
    }

    private void startCamera() {
        cameraHelper.startCamera(this, this, previewView,
                (boxes, imageWidth, imageHeight, rotation) -> {
                    if (!detecting) return;

                    final int vw = cachedViewW, vh = cachedViewH;
                    if (vw <= 0 || vh <= 0) return;

                    List<Rect> mapped = new ArrayList<>();
                    for (Rect box : boxes) {
                        mapped.add(CoordinateMapper.map(
                                box, imageWidth, imageHeight, rotation, vw, vh));
                    }

                    List<DetectedVehicle> vehicles = speedDetector.detectVehicles(mapped);
                    List<Double> speedHistory      = speedDetector.getLockedSpeedHistory();
                    int lockedId                   = speedDetector.getLockedId();

                    runOnUiThread(() -> {
                        // Pass lockedId atomically with vehicles so OverlayView.onDraw
                        // always sees a consistent (vehicles, lockedId) pair.
                        // The previous two-step write (overlayView.lockedId = x; then
                        // updateVehicles()) let invalidate() fire between the two writes,
                        // causing the locked box to never enter the isLocked draw branch.
                        overlayView.updateVehicles(vehicles, lockedId);
                        updateStatsUI(vehicles);
                        updateSpeedGraph(speedHistory);

                        if (lockedId >= 0 && !vehicles.isEmpty()) {
                            DetectedVehicle locked = vehicles.get(0);
                            double spd = useKmh ? locked.speedKmh : locked.speedKmh / 3.6;
                            tvSpeed.setText(String.format(Locale.US, "%.1f", spd));
                        } else if (lockedId < 0) {
                            tvSpeed.setText("--");
                        }
                        // lockedId >= 0 but vehicles empty = target out of frame;
                        // keep last speed reading visible (no setText call).
                    });
                });
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms,
                                           @NonNull int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == 100 && res.length > 0
                && res[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            Toast.makeText(this, "Camera permission required",
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopSessionTimer();
        // FIX 9: Guard against double-destroy (e.g. config change) since
        // cameraHelper.shutdown() is not idempotent in the original code.
        if (cameraHelper != null) {
            cameraHelper.shutdown();
            cameraHelper = null;
        }
    }
}