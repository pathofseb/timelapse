package com.timelapse;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.Size;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CAMERA_PERMISSION = 100;

    private String[] getRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ only needs camera permission (uses scoped storage)
            return new String[]{Manifest.permission.CAMERA};
        } else {
            // Older Android versions need storage permissions
            return new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
            };
        }
    }

    private PreviewView viewFinder;
    private Button recordButton;
    private TextView statusText;
    private TextView frameCountText;
    private TextView speedValueText;
    private TextView zoomText;
    private TextView resolutionText;
    private TextView settingsIcon;
    private SeekBar speedSeekBar;
    private SeekBar zoomSeekBar;

    private ProcessCameraProvider cameraProvider;
    private ImageCapture imageCapture;
    private Camera camera;
    private CameraControl cameraControl;
    private TimeLapseService timeLapseService;
    private boolean serviceBound = false;
    private boolean isRecording = false;
    private int speedMultiplier = 10;
    private float originalBrightness = -1;
    private boolean screenIsDimmed = false;
    private Handler dimHandler = new Handler(Looper.getMainLooper());
    private static final long DIM_DELAY_MS = 10000; // 10 seconds

    // Resolution settings
    private SharedPreferences preferences;
    private static final String PREF_RESOLUTION = "video_resolution";
    private static final String[] RESOLUTIONS = {"720p", "1080p", "1440p", "4K"};
    private static final int[] RESOLUTION_HEIGHTS = {720, 1080, 1440, 2160};
    private int selectedResolutionIndex = 1; // Default to 1080p

    // Zoom
    private float currentZoom = 1.0f;
    private ScaleGestureDetector scaleGestureDetector;

    private final Runnable dimScreenRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRecording && !screenIsDimmed) {
                dimScreen();
            }
        }
    };

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            TimeLapseService.LocalBinder binder = (TimeLapseService.LocalBinder) service;
            timeLapseService = binder.getService();
            serviceBound = true;

            timeLapseService.setFrameCountCallback(new TimeLapseService.FrameCountCallback() {
                @Override
                public void onFrameCountUpdated(int count) {
                    runOnUiThread(() -> frameCountText.setText(String.valueOf(count)));
                }
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Keep screen on during app usage to prevent automatic sleep
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Initialize SharedPreferences
        preferences = getSharedPreferences("TimeLapsePrefs", Context.MODE_PRIVATE);
        selectedResolutionIndex = preferences.getInt(PREF_RESOLUTION, 1); // Default to 1080p

        // Initialize UI elements
        viewFinder = findViewById(R.id.viewFinder);
        recordButton = findViewById(R.id.recordButton);
        statusText = findViewById(R.id.statusText);
        frameCountText = findViewById(R.id.frameCountText);
        speedValueText = findViewById(R.id.speedValueText);
        zoomText = findViewById(R.id.zoomText);
        resolutionText = findViewById(R.id.resolutionText);
        settingsIcon = findViewById(R.id.settingsIcon);
        speedSeekBar = findViewById(R.id.speedSeekBar);
        zoomSeekBar = findViewById(R.id.zoomSeekBar);

        // Set initial resolution text
        resolutionText.setText(RESOLUTIONS[selectedResolutionIndex]);

        recordButton.setOnClickListener(v -> toggleRecording());

        // Settings icon click listener
        settingsIcon.setOnClickListener(v -> showResolutionDialog());

        // Set up touch listener for the entire view to handle touch-to-brighten
        View rootView = findViewById(android.R.id.content);
        rootView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN && isRecording && screenIsDimmed) {
                    // Touch detected while recording and screen is dimmed - brighten it
                    restoreScreenBrightness();
                    screenIsDimmed = false;

                    // Schedule dimming again after 10 seconds
                    scheduleDimming();

                    Toast.makeText(MainActivity.this, "Screen will dim again in 10 seconds", Toast.LENGTH_SHORT).show();
                }
                return false; // Allow other touch events to be processed
            }
        });

        // Speed SeekBar listener
        speedSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                speedMultiplier = progress + 1;
                speedValueText.setText(speedMultiplier + "x");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Zoom SeekBar listener
        zoomSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && cameraControl != null) {
                    // Map 0-100 to 1.0-10.0x zoom
                    currentZoom = 1.0f + (progress / 100f) * 9.0f;
                    cameraControl.setLinearZoom(progress / 100f);
                    zoomText.setText(String.format("%.1fx", currentZoom));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Pinch to zoom gesture detector
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (cameraControl != null) {
                    currentZoom *= detector.getScaleFactor();
                    currentZoom = Math.max(1.0f, Math.min(currentZoom, 10.0f));
                    float linearZoom = (currentZoom - 1.0f) / 9.0f;
                    cameraControl.setLinearZoom(linearZoom);
                    zoomSeekBar.setProgress((int) (linearZoom * 100));
                    zoomText.setText(String.format("%.1fx", currentZoom));
                }
                return true;
            }
        });

        // Add pinch to zoom to preview
        viewFinder.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                scaleGestureDetector.onTouchEvent(event);
                return true;
            }
        });

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, getRequiredPermissions(), REQUEST_CAMERA_PERMISSION);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Only bind if not already bound
        if (!serviceBound) {
            Intent intent = new Intent(this, TimeLapseService.class);
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Don't unbind service if recording is in progress
        // This allows recording to continue when screen is off
        if (serviceBound && !isRecording) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindPreview(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                Toast.makeText(this, getString(R.string.error_camera), Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindPreview(@NonNull ProcessCameraProvider cameraProvider) {
        Preview preview = new Preview.Builder().build();

        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build();

        CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

        preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

        // Unbind all use cases before rebinding
        cameraProvider.unbindAll();

        // Bind and get camera control
        camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);
        cameraControl = camera.getCameraControl();

        Log.d("MainActivity", "Camera bound at native resolution");
    }

    private void toggleRecording() {
        if (!serviceBound) {
            Toast.makeText(this, "Service not ready", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!isRecording) {
            startRecording();
        } else {
            stopRecording();
        }
    }

    private void startRecording() {
        if (imageCapture != null && timeLapseService.startRecording(imageCapture, speedMultiplier)) {
            isRecording = true;
            recordButton.setText(getString(R.string.stop_recording));
            recordButton.setBackgroundTintList(ContextCompat.getColorStateList(this, android.R.color.holo_green_dark));
            statusText.setText("Recording at " + speedMultiplier + "x speed - " + RESOLUTIONS[selectedResolutionIndex] + "\nTouch to brighten • Power button to sleep");
            speedSeekBar.setEnabled(false);
            zoomSeekBar.setEnabled(false);

            // Schedule screen dimming after 10 seconds
            scheduleDimming();
        } else {
            Toast.makeText(this, "Failed to start recording", Toast.LENGTH_SHORT).show();
        }
    }

    private void scheduleDimming() {
        // Cancel any pending dimming
        dimHandler.removeCallbacks(dimScreenRunnable);
        // Schedule new dimming after delay
        dimHandler.postDelayed(dimScreenRunnable, DIM_DELAY_MS);
    }

    private void stopRecording() {
        // Cancel any pending dimming
        dimHandler.removeCallbacks(dimScreenRunnable);

        statusText.setText(getString(R.string.processing));
        timeLapseService.stopRecording(new TimeLapseService.VideoCompletionCallback() {
            @Override
            public void onVideoCompleted(String videoPath) {
                runOnUiThread(() -> {
                    isRecording = false;
                    recordButton.setText(getString(R.string.start_recording));
                    recordButton.setBackgroundTintList(ContextCompat.getColorStateList(MainActivity.this, android.R.color.holo_red_dark));
                    statusText.setText("Video saved to gallery!");
                    frameCountText.setText("0");
                    speedSeekBar.setEnabled(true);
                    zoomSeekBar.setEnabled(true);

                    // Restore screen brightness
                    restoreScreenBrightness();
                    screenIsDimmed = false;
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Error: " + error, Toast.LENGTH_LONG).show();
                    statusText.setText("");
                    speedSeekBar.setEnabled(true);
                    zoomSeekBar.setEnabled(true);

                    // Restore screen brightness
                    restoreScreenBrightness();
                    screenIsDimmed = false;
                });
            }
        });
    }

    private boolean allPermissionsGranted() {
        for (String permission : getRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, "Camera permission is required to use this app", Toast.LENGTH_LONG).show();
                // Don't finish immediately, let user see the message and try again
                recordButton.setEnabled(false);
            }
        }
    }

    private void dimScreen() {
        WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
        if (!screenIsDimmed) {
            originalBrightness = layoutParams.screenBrightness;
        }
        layoutParams.screenBrightness = 0.01f; // Very dim (0.0 to 1.0)
        getWindow().setAttributes(layoutParams);
        screenIsDimmed = true;
        Log.d("MainActivity", "Screen dimmed");
    }

    private void restoreScreenBrightness() {
        WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
        layoutParams.screenBrightness = originalBrightness;
        getWindow().setAttributes(layoutParams);
        Log.d("MainActivity", "Screen brightness restored");
    }

    private void showResolutionDialog() {
        if (isRecording) {
            Toast.makeText(this, "Cannot change resolution while recording", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Select Video Resolution");
        builder.setSingleChoiceItems(RESOLUTIONS, selectedResolutionIndex, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                selectedResolutionIndex = which;
                resolutionText.setText(RESOLUTIONS[selectedResolutionIndex]);

                // Save preference
                preferences.edit().putInt(PREF_RESOLUTION, selectedResolutionIndex).apply();

                // Restart camera with new resolution
                if (cameraProvider != null) {
                    bindPreview(cameraProvider);
                }

                Toast.makeText(MainActivity.this,
                    "Resolution changed to " + RESOLUTIONS[selectedResolutionIndex],
                    Toast.LENGTH_SHORT).show();

                dialog.dismiss();
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Clean up handler callbacks
        dimHandler.removeCallbacks(dimScreenRunnable);

        // Always unbind service on destroy to prevent leaks
        if (serviceBound) {
            try {
                unbindService(serviceConnection);
            } catch (IllegalArgumentException e) {
                // Service was already unbound
            }
            serviceBound = false;
        }
    }
}