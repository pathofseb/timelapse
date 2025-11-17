# Time Lapse Android App

A power-efficient timelapse camera app for Android that creates timelapses by capturing images instead of recording continuous video.

## Features

- **Professional Camera UI**:
  - Full-screen camera preview
  - Floating controls with semi-transparent overlays
  - Top bar with settings icon, resolution display, and frame counter
  - Clean, modern design similar to professional camera apps
- **Resolution Options**:
  - 720p (HD) - 1280x720
  - **1080p (Full HD)** - 1920x1080 (default)
  - 1440p (2K/QHD) - 2560x1440
  - 4K (Ultra HD) - 3840x2160
  - Tap settings ⚙ icon to change resolution
  - Remembers your preferred resolution
- **Zoom Control**:
  - **Pinch to zoom** on camera preview (1.0x - 10.0x)
  - Zoom slider for precise control
  - Real-time zoom indicator
- **Adjustable Speed**: Choose timelapse speed from 1x to 100x with slider
- **Ultra Power Efficient**:
  - Screen auto-dims after 10 seconds of recording
  - Touch screen to brighten temporarily (auto-dims again in 10 seconds)
  - Press power button to turn screen completely OFF during recording
- **Wake Lock**: CPU stays awake to ensure continuous recording even with screen off
- **Saves to Gallery**: Videos automatically saved to DCIM/TimeLapse and appear in your gallery
- **High Quality Output**: Compiles images into smooth 30fps MP4 video
- **Real-time Frame Counter**: Track your progress while recording (displayed in top bar)
- **Automatic Cleanup**: All temporary images automatically deleted after video creation
- **Optimized for Samsung S24**: Native Android implementation for best performance

## How to Build

1. Install Android Studio
2. Open this project in Android Studio
3. Connect your Android device or start an emulator
4. Click "Run" or press Shift+F10

## Requirements

- Android 5.0 (API level 21) or higher
- Camera permission (only permission needed on Android 13+)

## How to Use

1. **Launch the app** and grant camera permission when prompted
2. **Adjust Settings** (optional):
   - Tap the ⚙ icon in top-left to select resolution (720p/1080p/1440p/4K)
   - Default is 1080p Full HD
3. **Frame Your Shot**:
   - **Pinch to zoom** on camera preview, or use zoom slider (🔍)
   - Zoom from 1.0x to 10.0x with smooth control
4. **Select Timelapse Speed** using the ⚡ slider (1x-100x)
5. Point the camera at your subject and tap **"Start Recording"**
   - Frame counter appears in top-right (shows number of captured frames)
   - Screen automatically dims after 10 seconds to save battery
   - **Touch screen anytime to brighten temporarily** (dims again in 10 seconds)
   - **Press the power button to turn OFF the screen** for maximum battery savings!
   - Recording continues in the background even with screen off
6. Turn screen back on (or touch to brighten) and tap **"Stop Recording"** when finished
7. Wait for video processing and saving to gallery (usually a few seconds)
8. **Find your video in Gallery app** under DCIM/TimeLapse folder
9. All temporary image files are automatically deleted

## Technical Details

- **Dynamic Capture Rate**: Capture interval automatically calculated based on selected speed
  - Formula: `interval = (1000ms / 30fps) × speed_multiplier`
  - 10x speed: captures at 3fps (every 333ms)
  - 30x speed: captures at 1fps (every 1000ms = 1 second)
  - 100x speed: captures at 0.3fps (every 3.33 seconds)
- **Output**: 30fps MP4 video for smooth playback
- **Storage**:
  - Android 10+: Uses MediaStore API to save to DCIM/TimeLapse
  - Android 9 and below: Uses legacy storage with media scanner notification
  - Temporary images stored in private app directory, deleted after video creation
- **Camera**:
  - Android CameraX library for reliable camera operations
  - Configurable resolution from 720p to 4K
  - Digital zoom support (1.0x - 10.0x)
  - Pinch-to-zoom gesture detection
  - Back camera with optimal settings for timelapse
- **Video Encoding**: MediaCodec with H.264 (AVC) codec
- **Color Format**: YUV420 for optimal compression and quality
- **UI**: Full-screen preview with floating controls, professional camera app design
- **Power Management**:
  - PARTIAL_WAKE_LOCK keeps CPU running even with screen off
  - FLAG_KEEP_SCREEN_ON prevents automatic screen timeout during app use
  - Screen auto-dims to 1% after 10 seconds of recording
  - Touch-to-brighten feature with 10-second re-dim timer
  - User can press power button to turn screen completely off for maximum battery life