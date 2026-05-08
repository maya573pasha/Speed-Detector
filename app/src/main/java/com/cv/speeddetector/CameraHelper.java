package com.cv.speeddetector;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.util.Log;
import android.util.Size;
import androidx.annotation.OptIn;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.objects.DetectedObject;
import com.google.mlkit.vision.objects.ObjectDetection;
import com.google.mlkit.vision.objects.ObjectDetector;
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CameraHelper {

    private static final String TAG = "CameraHelper";

    private ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private ObjectDetector objectDetector;
    private FrameCallback callback;

    // FIX 1: Track whether the detector has already been shut down so we
    // never call close() twice, which throws an IllegalStateException.
    private volatile boolean isShutDown = false;

    public interface FrameCallback {
        void onDetected(List<Rect> boundingBoxes,
                        int imageWidth, int imageHeight, int rotationDegrees);
    }

    public CameraHelper() {
        ObjectDetectorOptions options = new ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                .enableMultipleObjects()
                .build();
        objectDetector = ObjectDetection.getClient(options);
    }

    public void startCamera(Context context, LifecycleOwner owner,
                            PreviewView previewView, FrameCallback cb) {
        this.callback = cb;

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Camera permission not granted — aborting startCamera");
            return;
        }

        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(context);

        future.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::processImageProxy);

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(owner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, imageAnalysis);

            } catch (Exception e) {
                Log.e(TAG, "Failed to start camera", e);
            }
        }, ContextCompat.getMainExecutor(context));
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void processImageProxy(ImageProxy imageProxy) {
        // FIX 2: Guard against processing after shutdown to avoid
        // "Task already completed" crashes from ML Kit.
        if (isShutDown || imageProxy.getImage() == null) {
            imageProxy.close();
            return;
        }

        int rotation = imageProxy.getImageInfo().getRotationDegrees();
        // FIX 3: Raw width/height from ImageProxy are the SENSOR dimensions
        // (before rotation). For 90°/270° rotations ML Kit maps boxes to the
        // rotated frame, so downstream callers need the rotated dimensions.
        // We pass the raw values and let CoordinateMapper handle the swap,
        // which it already does correctly — so we keep rawW/rawH here.
        int rawW = imageProxy.getWidth();
        int rawH = imageProxy.getHeight();

        InputImage image = InputImage.fromMediaImage(
                imageProxy.getImage(), rotation);

        objectDetector.process(image)
                .addOnSuccessListener(objects -> {
                    // FIX 4: Check isShutDown again before invoking callback —
                    // the executor may have been shut down while ML Kit was
                    // running inference, making the callback reference stale.
                    if (isShutDown) {
                        imageProxy.close();
                        return;
                    }
                    List<Rect> boxes = new ArrayList<>();
                    for (DetectedObject obj : objects) {
                        boxes.add(obj.getBoundingBox());
                    }
                    if (callback != null) {
                        callback.onDetected(boxes, rawW, rawH, rotation);
                    }
                    imageProxy.close();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Detection failed", e);
                    imageProxy.close();
                });
    }

    public void shutdown() {
        // FIX 5: Set flag first so in-flight tasks skip their callbacks.
        isShutDown = true;
        cameraExecutor.shutdown();
        if (objectDetector != null) {
            objectDetector.close();
            // FIX 6: Null out the reference so a second call to shutdown()
            // doesn't try to close an already-closed detector.
            objectDetector = null;
        }
    }
}