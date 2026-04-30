package com.cv.speeddetector;

import android.Manifest;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.MotionEvent;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private CameraHelper cameraHelper;
    private SpeedDetector speedDetector;

    private androidx.camera.view.PreviewView previewView;
    private OverlayView overlayView;
    private TextView tvSpeed, tvCalibStatus;
    private Button btnCalibrate, btnStartStop;

    private boolean detecting = false;
    private boolean calibrationMode = false;

    private float[] tapX = new float[2];
    private float[] tapY = new float[2];
    private int tapCount = 0;
    private double knownMetres = 3.5;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        overlayView = findViewById(R.id.overlayView);
        tvSpeed = findViewById(R.id.tvSpeed);
        tvCalibStatus = findViewById(R.id.tvCalibStatus);
        btnCalibrate = findViewById(R.id.btnCalibrate);
        btnStartStop = findViewById(R.id.btnStartStop);

        speedDetector = new SpeedDetector();
        cameraHelper = new CameraHelper();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, 100);
        } else {
            startCamera();
        }

        btnCalibrate.setOnClickListener(v -> {
            if (!calibrationMode) {
                showCalibrationDialog();
            } else {
                calibrationMode = false;
                overlayView.calibrationMode = false;
                overlayView.tap1 = null;
                overlayView.tap2 = null;
                tapCount = 0;
                btnCalibrate.setText("Calibrate");
                tvCalibStatus.setText("Calibration cancelled");
            }
        });

        btnStartStop.setOnClickListener(v -> {
            detecting = !detecting;
            btnStartStop.setText(detecting ? "Stop Detection" : "Start Detection");
            if (!detecting) {
                tvSpeed.setText("-- km/h");
                overlayView.updateVehicles(new ArrayList<>());
            }
        });

        overlayView.setOnTouchListener((view, event) -> {
            if (!calibrationMode) return false;
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (tapCount == 0) {
                    tapX[0] = event.getX();
                    tapY[0] = event.getY();
                    overlayView.tap1 = new android.graphics.PointF(tapX[0], tapY[0]);
                    tapCount = 1;
                    tvCalibStatus.setText("Now tap 2nd point");
                } else if (tapCount == 1) {
                    tapX[1] = event.getX();
                    tapY[1] = event.getY();
                    overlayView.tap2 = new android.graphics.PointF(tapX[1], tapY[1]);
                    tapCount = 2;
                    finalizeCalibration();
                }
                overlayView.invalidate();
            }
            return true;
        });
    }

    private void showCalibrationDialog() {
        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText("3.5");

        new AlertDialog.Builder(this)
                .setTitle("Calibration Distance")
                .setMessage("Enter the real-world distance in METRES between the two points you will tap.")
                .setView(input)
                .setPositiveButton("OK", (dialog, which) -> {
                    try {
                        knownMetres = Double.parseDouble(input.getText().toString());
                    } catch (NumberFormatException e) {
                        knownMetres = 3.5;
                    }
                    calibrationMode = true;
                    overlayView.calibrationMode = true;
                    tapCount = 0;
                    overlayView.tap1 = null;
                    overlayView.tap2 = null;
                    tvCalibStatus.setText("Tap 1st point on screen");
                    btnCalibrate.setText("Cancel Calib");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void finalizeCalibration() {
        double dx = tapX[1] - tapX[0];
        double dy = tapY[1] - tapY[0];
        double pixelDist = Math.sqrt(dx * dx + dy * dy);

        if (pixelDist < 10) {
            tvCalibStatus.setText("Too close, try again");
            tapCount = 0;
            overlayView.tap1 = null;
            overlayView.tap2 = null;
            return;
        }

        double scale = knownMetres / pixelDist;
        speedDetector.setScale(scale);

        calibrationMode = false;
        overlayView.calibrationMode = false;
        btnCalibrate.setText("Calibrate");
        tapCount = 0;
        tvCalibStatus.setText(String.format("Scale: %.4f m/px", scale));
        Toast.makeText(this, "Calibrated! " + String.format("%.4f", scale) + " m/px",
                Toast.LENGTH_SHORT).show();
    }

    private void startCamera() {
        cameraHelper.startCamera(this, this, previewView, boxes -> {
            if (!detecting) return;

            List<DetectedVehicle> vehicles = speedDetector.detectVehicles(boxes);

            double maxSpeed = 0;
            for (DetectedVehicle v : vehicles) {
                if (v.speedKmh > maxSpeed) maxSpeed = v.speedKmh;
            }

            final double displaySpeed = maxSpeed;
            final List<DetectedVehicle> displayVehicles = vehicles;

            runOnUiThread(() -> {
                tvSpeed.setText(displaySpeed > 1
                        ? String.format("%.1f km/h", displaySpeed)
                        : "-- km/h");
                overlayView.updateVehicles(displayVehicles);
            });
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 100 && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraHelper != null) {
            cameraHelper.shutdown();
        }
    }
}