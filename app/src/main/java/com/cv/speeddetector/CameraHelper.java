package com.cv.speeddetector;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.util.Log;
import androidx.camera.core.CameraSelector;
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

    private ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private ObjectDetector objectDetector;
    private FrameCallback callback;

    public interface FrameCallback {
        void onDetected(List<Rect> boundingBoxes);
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
            return;
        }

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(context);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
                    processImageProxy(imageProxy);
                });

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(owner, cameraSelector, preview, imageAnalysis);

            } catch (Exception e) {
                Log.e("CameraHelper", "Failed to start camera", e);
            }
        }, ContextCompat.getMainExecutor(context));
    }

    private void processImageProxy(ImageProxy imageProxy) {
        if (imageProxy.getImage() == null) {
            imageProxy.close();
            return;
        }

        InputImage image = InputImage.fromMediaImage(
                imageProxy.getImage(), imageProxy.getImageInfo().getRotationDegrees());

        objectDetector.process(image)
                .addOnSuccessListener(objects -> {
                    List<Rect> boxes = new ArrayList<>();
                    for (DetectedObject obj : objects) {
                        boxes.add(obj.getBoundingBox());
                    }
                    if (callback != null) {
                        callback.onDetected(boxes);
                    }
                    imageProxy.close();
                })
                .addOnFailureListener(e -> {
                    Log.e("CameraHelper", "Object detection failed", e);
                    imageProxy.close();
                });
    }

    public void shutdown() {
        cameraExecutor.shutdown();
        if (objectDetector != null) {
            objectDetector.close();
        }
    }
}