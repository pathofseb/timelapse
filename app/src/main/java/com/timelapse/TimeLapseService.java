package com.timelapse;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TimeLapseService extends Service {

    private static final String TAG = "TimeLapseService";
    private static final int OUTPUT_FPS = 30; // Output video will be 30fps
    private static final String CHANNEL_ID = "timelapse_recording";
    private static final int NOTIFICATION_ID = 1;

    private final IBinder binder = new LocalBinder();
    private Handler captureHandler;
    private ExecutorService cameraExecutor;
    private ImageCapture imageCapture;

    private boolean isRecording = false;
    private int frameCount = 0;
    private List<String> capturedImages;
    private File outputDir;
    private int captureIntervalMs = 333; // Dynamic capture interval based on speed
    private PowerManager.WakeLock wakeLock;

    private FrameCountCallback frameCountCallback;
    private VideoCompletionCallback videoCompletionCallback;

    public interface FrameCountCallback {
        void onFrameCountUpdated(int count);
    }

    public interface VideoCompletionCallback {
        void onVideoCompleted(String videoPath);
        void onError(String error);
    }

    public class LocalBinder extends Binder {
        TimeLapseService getService() {
            return TimeLapseService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        captureHandler = new Handler(Looper.getMainLooper());
        cameraExecutor = Executors.newSingleThreadExecutor();
        capturedImages = new ArrayList<>();
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Timelapse Recording",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows when timelapse is recording");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification(int frameCount) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                notificationIntent,
                PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setContentTitle("Recording Timelapse")
                .setContentText("Captured " + frameCount + " frames")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public boolean startRecording(ImageCapture imageCapture, int speedMultiplier) {
        if (isRecording) return false;

        this.imageCapture = imageCapture;

        // Calculate capture interval based on speed multiplier
        // Formula: interval = (1000ms / OUTPUT_FPS) * speedMultiplier
        // For 10x speed: (1000/30) * 10 = 333ms (capture at 3fps)
        // For 20x speed: (1000/30) * 20 = 666ms (capture at 1.5fps)
        captureIntervalMs = (1000 / OUTPUT_FPS) * speedMultiplier;
        Log.d(TAG, "Speed: " + speedMultiplier + "x, Capture interval: " + captureIntervalMs + "ms");

        // Start foreground service with notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, createNotification(0), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else {
            startForeground(NOTIFICATION_ID, createNotification(0));
        }
        Log.d(TAG, "Started foreground service");

        // Acquire wake lock to keep CPU running while screen is off
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TimeLapse::RecordingWakeLock");
        wakeLock.acquire();
        Log.d(TAG, "Wake lock acquired");

        // Create output directory
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        outputDir = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "timelapse_" + timeStamp);
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            Log.e(TAG, "Failed to create output directory");
            return false;
        }

        isRecording = true;
        frameCount = 0;
        capturedImages.clear();

        startCapturing();
        return true;
    }

    public void stopRecording(VideoCompletionCallback callback) {
        if (!isRecording) return;

        this.videoCompletionCallback = callback;
        isRecording = false;

        // Stop capturing and compile video
        captureHandler.removeCallbacksAndMessages(null);

        if (capturedImages.isEmpty()) {
            callback.onError("No frames captured");
            return;
        }

        compileVideo();
    }

    private void startCapturing() {
        captureHandler.post(captureRunnable);
    }

    private final Runnable captureRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRecording) return;

            captureImage();
            captureHandler.postDelayed(this, captureIntervalMs);
        }
    };

    private void captureImage() {
        String fileName = String.format(Locale.getDefault(), "frame_%06d.jpg", frameCount);
        File outputFile = new File(outputDir, fileName);

        ImageCapture.OutputFileOptions outputFileOptions =
                new ImageCapture.OutputFileOptions.Builder(outputFile).build();

        imageCapture.takePicture(
                outputFileOptions,
                cameraExecutor,
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@androidx.annotation.NonNull ImageCapture.OutputFileResults output) {
                        frameCount++;
                        capturedImages.add(outputFile.getAbsolutePath());

                        // Update notification with frame count
                        NotificationManager manager = getSystemService(NotificationManager.class);
                        if (manager != null && isRecording) {
                            manager.notify(NOTIFICATION_ID, createNotification(frameCount));
                        }

                        if (frameCountCallback != null) {
                            frameCountCallback.onFrameCountUpdated(frameCount);
                        }

                        Log.d(TAG, "Image saved: " + fileName);
                    }

                    @Override
                    public void onError(@androidx.annotation.NonNull ImageCaptureException exception) {
                        Log.e(TAG, "Image capture failed: " + exception.getMessage());
                    }
                }
        );
    }

    private void compileVideo() {
        cameraExecutor.execute(() -> {
            try {
                VideoCompiler compiler = new VideoCompiler();
                String videoPath = compiler.compileImagesToVideo(this, capturedImages, outputDir.getAbsolutePath());

                // Clean up image files and temporary directory
                for (String imagePath : capturedImages) {
                    File imageFile = new File(imagePath);
                    if (imageFile.exists()) {
                        imageFile.delete();
                        Log.d(TAG, "Deleted image: " + imagePath);
                    }
                }

                // Delete temporary directory
                if (outputDir != null && outputDir.exists()) {
                    outputDir.delete();
                    Log.d(TAG, "Deleted temporary directory: " + outputDir.getAbsolutePath());
                }

                if (videoCompletionCallback != null) {
                    videoCompletionCallback.onVideoCompleted(videoPath);
                }

            } catch (Exception e) {
                Log.e(TAG, "Video compilation failed", e);
                if (videoCompletionCallback != null) {
                    videoCompletionCallback.onError("Video compilation failed: " + e.getMessage());
                }
            } finally {
                // Release wake lock
                if (wakeLock != null && wakeLock.isHeld()) {
                    wakeLock.release();
                    Log.d(TAG, "Wake lock released");
                }
                // Stop foreground service
                stopForeground(true);
                Log.d(TAG, "Stopped foreground service");
            }
        });
    }

    public void setFrameCountCallback(FrameCountCallback callback) {
        this.frameCountCallback = callback;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRecording = false;
        captureHandler.removeCallbacksAndMessages(null);
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.d(TAG, "Wake lock released in onDestroy");
        }
        stopForeground(true);
    }
}