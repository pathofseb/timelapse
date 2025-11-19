package com.timelapse;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class VideoCompiler {

    private static final String TAG = "VideoCompiler";
    private static final String MIME_TYPE = "video/avc";
    private static final int FRAME_RATE = 30;
    private static final int I_FRAME_INTERVAL = 5;
    private static final int TIMEOUT_US = 10000;

    // Overloaded method for segment compilation (does NOT save to gallery)
    public String compileImagesToVideo(Context context, List<String> imagePaths, String outputDir, int segmentNumber) throws Exception {
        if (imagePaths.isEmpty()) {
            throw new IllegalArgumentException("No images to compile");
        }

        // Create segment file path
        String segmentPath = outputDir + "/timelapse_segment_" + segmentNumber + ".mp4";
        compileImagesToVideoFile(imagePaths, segmentPath);
        Log.d(TAG, "Segment " + segmentNumber + " saved: " + segmentPath);
        return segmentPath;
    }

    // Original method for final video compilation (saves to gallery)
    public String compileImagesToVideo(Context context, List<String> imagePaths, String outputDir) throws Exception {
        if (imagePaths.isEmpty()) {
            throw new IllegalArgumentException("No images to compile");
        }

        // Create temporary video file first
        String tempOutputPath = outputDir + "/timelapse_temp.mp4";
        compileImagesToVideoFile(imagePaths, tempOutputPath);

        Log.d(TAG, "Video compilation completed, saving to gallery...");

        // Copy video to public gallery and get the final path
        String finalPath = saveVideoToGallery(context, tempOutputPath);

        // Delete temporary file
        File tempFile = new File(tempOutputPath);
        if (tempFile.exists()) {
            tempFile.delete();
            Log.d(TAG, "Temporary video file deleted");
        }

        Log.d(TAG, "Video saved to gallery: " + finalPath);
        return finalPath;
    }

    // Core compilation method used by both segment and final compilation
    private void compileImagesToVideoFile(List<String> imagePaths, String outputPath) throws Exception {

        // Read EXIF orientation from first image
        ExifInterface exif = new ExifInterface(imagePaths.get(0));
        int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);

        // Get dimensions from first image
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(imagePaths.get(0), options);
        int width = options.outWidth;
        int height = options.outHeight;

        // Swap dimensions if image is rotated 90 or 270 degrees
        if (orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_ROTATE_270) {
            int temp = width;
            width = height;
            height = temp;
        }

        // Ensure dimensions are even (required for most codecs)
        width = (width / 2) * 2;
        height = (height / 2) * 2;

        Log.d(TAG, "Video dimensions: " + width + "x" + height);
        Log.d(TAG, "EXIF orientation: " + orientation);
        Log.d(TAG, "Frame count: " + imagePaths.size());

        MediaCodec encoder = null;
        MediaMuxer muxer = null;

        try {
            // Setup encoder with YUV420 Semi-Planar format (NV21/NV12 compatible)
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
            format.setInteger(MediaFormat.KEY_BIT_RATE, width * height * 8); // Increased bitrate for better quality
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);

            encoder = MediaCodec.createEncoderByType(MIME_TYPE);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            // Setup muxer with output file
            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            int trackIndex = -1;
            boolean muxerStarted = false;
            long frameTimeUs = 0;
            long frameDurationUs = 1000000L / FRAME_RATE;

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

            for (int i = 0; i < imagePaths.size(); i++) {
                // Load and convert bitmap to YUV420 with proper rotation
                Bitmap bitmap = loadScaledBitmap(imagePaths.get(i), width, height, orientation);
                if (bitmap != null) {
                    byte[] yuvData = convertBitmapToYUV420(bitmap, width, height);
                    bitmap.recycle();

                    // Queue input frame
                    int inputBufferIndex = encoder.dequeueInputBuffer(TIMEOUT_US);
                    if (inputBufferIndex >= 0) {
                        ByteBuffer inputBuffer = encoder.getInputBuffer(inputBufferIndex);
                        inputBuffer.clear();
                        inputBuffer.put(yuvData);
                        encoder.queueInputBuffer(inputBufferIndex, 0, yuvData.length, frameTimeUs, 0);
                    }
                }

                // Signal end of stream for last frame
                if (i == imagePaths.size() - 1) {
                    int inputBufferIndex = encoder.dequeueInputBuffer(TIMEOUT_US);
                    if (inputBufferIndex >= 0) {
                        encoder.queueInputBuffer(inputBufferIndex, 0, 0, frameTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    }
                }

                // Process output
                boolean outputDone = false;
                while (!outputDone) {
                    int outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);

                    if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break;
                    } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxerStarted) {
                            throw new RuntimeException("Format changed twice");
                        }
                        MediaFormat newFormat = encoder.getOutputFormat();
                        trackIndex = muxer.addTrack(newFormat);
                        muxer.start();
                        muxerStarted = true;
                    } else if (outputBufferIndex >= 0) {
                        ByteBuffer encodedData = encoder.getOutputBuffer(outputBufferIndex);

                        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            bufferInfo.size = 0;
                        }

                        if (bufferInfo.size != 0 && muxerStarted) {
                            encodedData.position(bufferInfo.offset);
                            encodedData.limit(bufferInfo.offset + bufferInfo.size);
                            muxer.writeSampleData(trackIndex, encodedData, bufferInfo);
                        }

                        encoder.releaseOutputBuffer(outputBufferIndex, false);

                        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true;
                        }
                    }
                }

                frameTimeUs += frameDurationUs;
                Log.d(TAG, "Processed frame " + (i + 1) + "/" + imagePaths.size());
            }

        } finally {
            if (encoder != null) {
                encoder.stop();
                encoder.release();
            }
            if (muxer != null) {
                muxer.stop();
                muxer.release();
            }
        }
    }

    // Public method to save an existing video file to gallery
    public String saveToGallery(Context context, String videoPath) throws Exception {
        return saveVideoToGallery(context, videoPath);
    }

    // Merge multiple video segments into one final video
    public String mergeVideoSegments(Context context, List<String> segmentPaths, String outputDir) throws Exception {
        if (segmentPaths.isEmpty()) {
            throw new IllegalArgumentException("No segments to merge");
        }

        String mergedOutputPath = outputDir + "/timelapse_merged.mp4";
        MediaMuxer muxer = null;

        try {
            // Get format from first segment
            MediaExtractor extractor = new MediaExtractor();
            extractor.setDataSource(segmentPaths.get(0));
            MediaFormat format = null;
            int videoTrackIndex = -1;

            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat fmt = extractor.getTrackFormat(i);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                if (mime.startsWith("video/")) {
                    format = fmt;
                    videoTrackIndex = i;
                    break;
                }
            }
            extractor.release();

            if (format == null) {
                throw new Exception("No video track found in segments");
            }

            // Create muxer
            muxer = new MediaMuxer(mergedOutputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int muxerTrackIndex = muxer.addTrack(format);
            muxer.start();

            // Merge all segments
            ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024); // 1MB buffer
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            long presentationTimeUs = 0;

            for (String segmentPath : segmentPaths) {
                Log.d(TAG, "Merging segment: " + segmentPath);
                extractor = new MediaExtractor();
                extractor.setDataSource(segmentPath);
                extractor.selectTrack(0); // Video track

                while (true) {
                    buffer.clear();
                    int sampleSize = extractor.readSampleData(buffer, 0);
                    if (sampleSize < 0) {
                        break;
                    }

                    bufferInfo.offset = 0;
                    bufferInfo.size = sampleSize;
                    bufferInfo.flags = extractor.getSampleFlags();
                    bufferInfo.presentationTimeUs = presentationTimeUs;

                    muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo);

                    presentationTimeUs += 1000000L / FRAME_RATE; // Increment by frame duration
                    extractor.advance();
                }

                extractor.release();
            }

        } finally {
            if (muxer != null) {
                muxer.stop();
                muxer.release();
            }
        }

        Log.d(TAG, "Segments merged, saving to gallery...");

        // Save merged video to gallery
        String finalPath = saveVideoToGallery(context, mergedOutputPath);

        // Delete merged temp file
        File mergedFile = new File(mergedOutputPath);
        if (mergedFile.exists()) {
            mergedFile.delete();
            Log.d(TAG, "Merged temp file deleted");
        }

        return finalPath;
    }

    private String saveVideoToGallery(Context context, String tempVideoPath) throws Exception {
        File tempFile = new File(tempVideoPath);
        if (!tempFile.exists()) {
            throw new Exception("Temporary video file not found");
        }

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String displayName = "Timelapse_" + timeStamp + ".mp4";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ - Use MediaStore
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, displayName);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/TimeLapse");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);

            ContentResolver resolver = context.getContentResolver();
            Uri collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri videoUri = resolver.insert(collection, values);

            if (videoUri == null) {
                throw new Exception("Failed to create MediaStore entry");
            }

            // Copy file to MediaStore
            try (OutputStream out = resolver.openOutputStream(videoUri);
                 java.io.FileInputStream in = new java.io.FileInputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }

            // Mark as complete
            values.clear();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            resolver.update(videoUri, values, null, null);

            Log.d(TAG, "Video saved to MediaStore: " + videoUri);
            return videoUri.toString();

        } else {
            // Android 9 and below - Use legacy storage
            File dcimDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "TimeLapse");
            if (!dcimDir.exists()) {
                dcimDir.mkdirs();
            }

            File outputFile = new File(dcimDir, displayName);

            // Copy file
            try (FileOutputStream out = new FileOutputStream(outputFile);
                 java.io.FileInputStream in = new java.io.FileInputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }

            // Notify media scanner
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DATA, outputFile.getAbsolutePath());
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);

            Log.d(TAG, "Video saved to DCIM: " + outputFile.getAbsolutePath());
            return outputFile.getAbsolutePath();
        }
    }

    private Bitmap loadScaledBitmap(String imagePath, int targetWidth, int targetHeight, int orientation) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(imagePath, options);

            options.inSampleSize = calculateInSampleSize(options, targetWidth, targetHeight);
            options.inJustDecodeBounds = false;

            Bitmap bitmap = BitmapFactory.decodeFile(imagePath, options);
            if (bitmap != null) {
                // Apply EXIF rotation
                bitmap = rotateImageIfRequired(bitmap, orientation);
                // Scale to target size
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true);
                if (scaled != bitmap) {
                    bitmap.recycle();
                }
                return scaled;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading bitmap: " + imagePath, e);
        }
        return null;
    }

    private Bitmap rotateImageIfRequired(Bitmap bitmap, int orientation) {
        Matrix matrix = new Matrix();

        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.postRotate(90);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.postRotate(180);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.postRotate(270);
                break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setScale(1, -1);
                break;
            default:
                return bitmap;
        }

        try {
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (rotated != bitmap) {
                bitmap.recycle();
            }
            return rotated;
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "Out of memory rotating bitmap", e);
            return bitmap;
        }
    }

    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }

        return inSampleSize;
    }

    private byte[] convertBitmapToYUV420(Bitmap bitmap, int width, int height) {
        int[] argb = new int[width * height];
        bitmap.getPixels(argb, 0, width, 0, 0, width, height);

        byte[] yuv = new byte[width * height * 3 / 2];
        encodeYUV420SP(yuv, argb, width, height);

        return yuv;
    }

    private void encodeYUV420SP(byte[] yuv420sp, int[] argb, int width, int height) {
        final int frameSize = width * height;

        int yIndex = 0;
        int uvIndex = frameSize;

        int a, R, G, B, Y, U, V;
        int index = 0;
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {

                a = (argb[index] & 0xff000000) >> 24; // a is not used obviously
                R = (argb[index] & 0xff0000) >> 16;
                G = (argb[index] & 0xff00) >> 8;
                B = (argb[index] & 0xff) >> 0;

                // well known RGB to YUV algorithm
                Y = ((66 * R + 129 * G + 25 * B + 128) >> 8) + 16;
                U = ((-38 * R - 74 * G + 112 * B + 128) >> 8) + 128;
                V = ((112 * R - 94 * G - 18 * B + 128) >> 8) + 128;

                // NV12 has a plane of Y and interleaved planes of UV each sampled by a factor of 2
                //    meaning for every 4 Y pixels there are 1 U and 1 V.  Note the sampling is every other
                //    pixel AND every other scanline.
                yuv420sp[yIndex++] = (byte) ((Y < 0) ? 0 : ((Y > 255) ? 255 : Y));
                if (j % 2 == 0 && index % 2 == 0) {
                    yuv420sp[uvIndex++] = (byte) ((U < 0) ? 0 : ((U > 255) ? 255 : U));
                    yuv420sp[uvIndex++] = (byte) ((V < 0) ? 0 : ((V > 255) ? 255 : V));
                }

                index++;
            }
        }
    }
}