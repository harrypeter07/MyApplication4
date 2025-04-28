package com.example.myapplication.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;
import androidx.core.app.NotificationCompat;

import com.example.myapplication.MainActivity;
import com.example.myapplication.R;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ScreenCaptureService extends Service {
    private static final String TAG = "ScreenCaptureService";
    private static final String CHANNEL_ID = "screen_capture_channel";
    private static final int NOTIFICATION_ID = 2;
    private static final int VIRTUAL_DISPLAY_FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR;
    private static final int SCREENSHOT_INTERVAL_MS = 2000; // Capture every 2 seconds
    private static final String SERVER_URL = "https://myapplication4.onrender.com/upload"; // Updated to Render URL
    
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private Surface surface;
    private ImageReader imageReader;
    private final IBinder binder = new LocalBinder();
    private int width;
    private int height;
    private int densityDpi;
    private Handler handler;
    private boolean isCapturing = false;
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    
    public class LocalBinder extends Binder {
        public ScreenCaptureService getService() {
            return ScreenCaptureService.this;
        }
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate called");
        createNotificationChannel();
        
        // Initialize screen size
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        width = wm.getCurrentWindowMetrics().getBounds().width();
        height = wm.getCurrentWindowMetrics().getBounds().height();
        densityDpi = Resources.getSystem().getDisplayMetrics().densityDpi;
        
        handler = new Handler(Looper.getMainLooper());
        
        startForeground(NOTIFICATION_ID, createNotification());
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("resultCode") && intent.hasExtra("data")) {
            int resultCode = intent.getIntExtra("resultCode", -1);
            Intent data = intent.getParcelableExtra("data");
            
            if (resultCode != -1 && data != null) {
                MediaProjectionManager mediaProjectionManager = 
                        (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data);
                
                Log.d(TAG, "MediaProjection created successfully");
            }
        }
        
        return START_STICKY;
    }
    
    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Screen Capture Service")
                .setContentText(isCapturing ? "Capturing screen..." : "Ready to capture")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }
    
    private void createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Screen Capture Service",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows the status of the screen capture service");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }
    
    public void startVirtualDisplay(Surface surface) {
        this.surface = surface;
        
        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection is null");
            return;
        }
        
        // Create ImageReader for screenshot capture
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(reader -> {
            if (isCapturing) {
                captureScreenshot();
            }
        }, handler);
        
        // Create virtual display
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenCapture",
                width, height, densityDpi,
                VIRTUAL_DISPLAY_FLAGS,
                imageReader.getSurface(),
                null, handler);
        
        Log.d(TAG, "Virtual display created: " + width + "x" + height);
        
        // Start periodic screenshot capturing
        startPeriodicCapture();
    }
    
    private void startPeriodicCapture() {
        isCapturing = true;
        updateNotification();
        
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!isCapturing) {
                    return;
                }
                
                captureScreenshot();
                handler.postDelayed(this, SCREENSHOT_INTERVAL_MS);
            }
        });
    }
    
    private void captureScreenshot() {
        if (imageReader == null) {
            Log.e(TAG, "ImageReader is null");
            return;
        }
        
        Image image = null;
        try {
            image = imageReader.acquireLatestImage();
            if (image == null) {
                Log.w(TAG, "Acquired image was null");
                return;
            }
            
            // Convert image to bitmap and save to file
            Bitmap bitmap = imageToBitmap(image);
            if (bitmap != null) {
                String timestamp = String.valueOf(System.currentTimeMillis());
                File screenshotFile = new File(getCacheDir(), "screenshot_" + timestamp + ".jpg");
                
                try (FileOutputStream output = new FileOutputStream(screenshotFile)) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output);
                    Log.d(TAG, "Screenshot saved to file: " + screenshotFile.getAbsolutePath());
                    uploadScreenshot(screenshotFile);
                } catch (IOException e) {
                    Log.e(TAG, "Error saving screenshot to file", e);
                } finally {
                    bitmap.recycle();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error capturing screenshot", e);
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }
    
    private Bitmap imageToBitmap(Image image) {
        if (image == null) {
            return null;
        }
        
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * width;
        
        // Create bitmap
        Bitmap bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(buffer);
        
        // Crop the bitmap if needed
        if (bitmap.getWidth() > width || bitmap.getHeight() > height) {
            Bitmap croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height);
            bitmap.recycle();
            return croppedBitmap;
        }
        
        return bitmap;
    }
    
    private void uploadScreenshot(File screenshotFile) {
        Log.d(TAG, "Starting screenshot upload: " + screenshotFile.getName());
        
        if (!screenshotFile.exists()) {
            Log.e(TAG, "Screenshot file does not exist: " + screenshotFile.getAbsolutePath());
            return;
        }
        
        try {
            // Create a multipart request body with the correct field name "image"
            RequestBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("image", screenshotFile.getName(),
                            RequestBody.create(MediaType.parse("image/jpeg"), screenshotFile))
                    .build();
            
            // Create the request with proper headers
            Request request = new Request.Builder()
                    .url(SERVER_URL)
                    .post(requestBody)
                    .addHeader("Accept", "application/json")
                    .build();
            
            Log.d(TAG, "Sending request to server: " + SERVER_URL);
            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "Failed to upload screenshot: " + e.getMessage(), e);
                    // Keep the file if upload fails
                }
                
                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        String responseBody = response.body() != null ? response.body().string() : "No response body";
                        if (response.isSuccessful()) {
                            Log.d(TAG, "Screenshot uploaded successfully: " + screenshotFile.getName() +
                                    ", Response: " + response.code() + ", Body: " + responseBody);
                            if (screenshotFile.exists()) {
                                if (screenshotFile.delete()) {
                                    Log.d(TAG, "Temporary screenshot file deleted");
                                } else {
                                    Log.w(TAG, "Failed to delete temporary screenshot file");
                                }
                            }
                        } else {
                            Log.e(TAG, "Server error: " + response.code() + " - " + 
                                    response.message() + ", Body: " + responseBody);
                        }
                    } finally {
                        response.close();
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error preparing upload request", e);
        }
    }
    
    private void updateNotification() {
        NotificationManager notificationManager = 
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIFICATION_ID, createNotification());
    }
    
    public void stopProjection() {
        isCapturing = false;
        updateNotification();
        
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        
        Log.d(TAG, "Screen capture stopped");
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        stopProjection();
    }
}