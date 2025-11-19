package com.timelapse;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
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
import java.io.FileOutputStream;
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
    private static final int FRAMES_PER_SEGMENT = 300; // Compile every 300 frames (10 seconds of output video)

    private final IBinder binder = new LocalBinder();
    private Handler captureHandler;
    private ExecutorService cameraExecutor;
    private ExecutorService compilationExecutor;
    private ImageCapture imageCapture;

    private boolean isRecording = false;
    private int frameCount = 0;
    private int totalFrameCount = 0;
    private List<String> capturedImages;
    private List<String> compiledSegments;
    private File outputDir;
    private int captureIntervalMs = 333; // Dynamic capture interval based on speed
    private PowerManager.WakeLock wakeLock;
    private boolean isCompiling = false;
    private boolean showTimestamp = false;

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
        compilationExecutor = Executors.newSingleThreadExecutor();
        capturedImages = new ArrayList<>();
        compiledSegments = new ArrayList<>();
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

    public boolean startRecording(ImageCapture imageCapture, int speedMultiplier, boolean showTimestamp) {
        if (isRecording) return false;

        this.imageCapture = imageCapture;
        this.showTimestamp = showTimestamp;

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
        totalFrameCount = 0;
        capturedImages.clear();
        compiledSegments.clear();

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
                        // Apply timestamp overlay if enabled
                        if (showTimestamp) {
                            applyTimestampOverlay(outputFile.getAbsolutePath());
                        }

                        frameCount++;
                        totalFrameCount++;
                        capturedImages.add(outputFile.getAbsolutePath());

                        // Update notification with frame count
                        NotificationManager manager = getSystemService(NotificationManager.class);
                        if (manager != null && isRecording) {
                            manager.notify(NOTIFICATION_ID, createNotification(totalFrameCount));
                        }

                        if (frameCountCallback != null) {
                            frameCountCallback.onFrameCountUpdated(totalFrameCount);
                        }

                        Log.d(TAG, "Image saved: " + fileName);

                        // Check if we should compile a segment
                        if (frameCount >= FRAMES_PER_SEGMENT && !isCompiling) {
                            compileSegment();
                        }
                    }

                    @Override
                    public void onError(@androidx.annotation.NonNull ImageCaptureException exception) {
                        Log.e(TAG, "Image capture failed: " + exception.getMessage());
                    }
                }
        );
    }

    private void compileSegment() {
        isCompiling = true;

        // Create a copy of the current frame list to compile
        final List<String> framesToCompile = new ArrayList<>(capturedImages);
        final int segmentNumber = compiledSegments.size();

        Log.d(TAG, "Starting segment compilation #" + segmentNumber + " with " + framesToCompile.size() + " frames");

        compilationExecutor.execute(() -> {
            try {
                VideoCompiler compiler = new VideoCompiler();
                String segmentPath = compiler.compileImagesToVideo(this, framesToCompile, outputDir.getAbsolutePath(), segmentNumber);

                synchronized (compiledSegments) {
                    compiledSegments.add(segmentPath);
                }

                // Delete compiled frames to free up storage
                for (String imagePath : framesToCompile) {
                    File imageFile = new File(imagePath);
                    if (imageFile.exists()) {
                        imageFile.delete();
                        Log.d(TAG, "Deleted compiled frame: " + imagePath);
                    }
                }

                // Clear the compiled frames from the list and reset counter
                synchronized (capturedImages) {
                    capturedImages.clear();
                    frameCount = 0;
                }

                Log.d(TAG, "Segment #" + segmentNumber + " compiled successfully: " + segmentPath);

            } catch (Exception e) {
                Log.e(TAG, "Segment compilation failed", e);
            } finally {
                isCompiling = false;
            }
        });
    }

    private void compileVideo() {
        cameraExecutor.execute(() -> {
            try {
                VideoCompiler compiler = new VideoCompiler();
                String videoPath;

                // Compile any remaining frames as final segment
                if (!capturedImages.isEmpty()) {
                    Log.d(TAG, "Compiling remaining " + capturedImages.size() + " frames");
                    String finalSegmentPath = compiler.compileImagesToVideo(this, capturedImages, outputDir.getAbsolutePath(), compiledSegments.size());
                    synchronized (compiledSegments) {
                        compiledSegments.add(finalSegmentPath);
                    }
                }

                // If we have multiple segments, merge them
                if (compiledSegments.size() > 1) {
                    Log.d(TAG, "Merging " + compiledSegments.size() + " segments");
                    videoPath = compiler.mergeVideoSegments(this, compiledSegments, outputDir.getAbsolutePath());

                    // Delete individual segments after merging
                    for (String segmentPath : compiledSegments) {
                        File segmentFile = new File(segmentPath);
                        if (segmentFile.exists()) {
                            segmentFile.delete();
                            Log.d(TAG, "Deleted segment: " + segmentPath);
                        }
                    }
                } else if (compiledSegments.size() == 1) {
                    // Only one segment, save it to gallery
                    String segmentPath = compiledSegments.get(0);
                    Log.d(TAG, "Single segment, saving to gallery");
                    videoPath = compiler.saveToGallery(this, segmentPath);

                    // Delete the segment file after saving
                    File segmentFile = new File(segmentPath);
                    if (segmentFile.exists()) {
                        segmentFile.delete();
                        Log.d(TAG, "Deleted segment: " + segmentPath);
                    }
                } else {
                    throw new Exception("No video segments to compile");
                }

                // Clean up remaining image files and temporary directory
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

    private void applyTimestampOverlay(String imagePath) {
        try {
            // Load the image
            Bitmap original = BitmapFactory.decodeFile(imagePath);
            if (original == null) {
                Log.e(TAG, "Failed to load image for timestamp overlay");
                return;
            }

            // Create a mutable copy
            Bitmap mutableBitmap = original.copy(Bitmap.Config.ARGB_8888, true);
            original.recycle();

            Canvas canvas = new Canvas(mutableBitmap);

            // Get current date and time
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            Date now = new Date();
            String dateStr = dateFormat.format(now);
            String timeStr = timeFormat.format(now);

            // Setup paint for minimalistic text
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.FILL);

            // Calculate text size based on image width (smaller for minimalistic look)
            int imageWidth = mutableBitmap.getWidth();
            int imageHeight = mutableBitmap.getHeight();
            float textSize = imageWidth * 0.025f; // 2.5% of image width
            paint.setTextSize(textSize);

            // Add subtle shadow for better readability
            paint.setShadowLayer(textSize * 0.15f, 0, 0, Color.BLACK);

            // Measure text dimensions
            Rect dateBounds = new Rect();
            Rect timeBounds = new Rect();
            paint.getTextBounds(dateStr, 0, dateStr.length(), dateBounds);
            paint.getTextBounds(timeStr, 0, timeStr.length(), timeBounds);

            // Position in top right corner with padding
            float padding = textSize * 0.8f;
            float dateX = imageWidth - dateBounds.width() - padding;
            float dateY = padding + dateBounds.height();
            float timeX = imageWidth - timeBounds.width() - padding;
            float timeY = dateY + timeBounds.height() + (textSize * 0.3f);

            // Draw timestamp
            canvas.drawText(dateStr, dateX, dateY, paint);
            canvas.drawText(timeStr, timeX, timeY, paint);

            // Save the modified image back to the same file
            FileOutputStream out = new FileOutputStream(imagePath);
            mutableBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out);
            out.flush();
            out.close();
            mutableBitmap.recycle();

            Log.d(TAG, "Timestamp overlay applied to: " + imagePath);

        } catch (Exception e) {
            Log.e(TAG, "Error applying timestamp overlay", e);
        }
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
        if (compilationExecutor != null) {
            compilationExecutor.shutdown();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.d(TAG, "Wake lock released in onDestroy");
        }
        stopForeground(true);
    }
}