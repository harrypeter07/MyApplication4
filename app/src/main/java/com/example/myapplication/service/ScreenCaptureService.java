package com.example.myapplication.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import android.os.Environment;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.content.pm.PackageManager;
import androidx.core.content.ContextCompat;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.view.Display;
import android.os.HandlerThread;

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
    
    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private Surface surface;
    private ImageReader imageReader;
    private final IBinder binder = new LocalBinder();
    private int screenWidth;
    private int screenHeight;
    private int screenDensity;
    private Handler handler;
    private boolean isCapturing = false;
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    
    public static final String ACTION_SCREENSHOT_ONCE = "com.example.myapplication.SCREENSHOT_ONCE";
    public static final String ACTION_START_SCREEN_CAPTURE = "com.example.myapplication.START_SCREEN_CAPTURE";
    public static final String ACTION_STOP_SCREEN_CAPTURE = "com.example.myapplication.STOP_SCREEN_CAPTURE";
    public static int lastResultCode = -1;
    public static Intent lastData = null;
    private BroadcastReceiver screenCaptureReceiver;
    
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
        Log.d("ScreenCaptureService", "ScreenCaptureService started");
        android.widget.Toast.makeText(getApplicationContext(), "ScreenCaptureService started", android.widget.Toast.LENGTH_SHORT).show();
        Log.d(TAG, "onCreate called");
        createNotificationChannel();
        
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        // Get screen metrics
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        screenWidth = size.x;
        screenHeight = size.y;
        screenDensity = getResources().getDisplayMetrics().densityDpi;
        // Create handler thread
        HandlerThread handlerThread = new HandlerThread("ScreenCaptureThread");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
        
        startForeground(NOTIFICATION_ID, createNotification());
        
        // Register receiver for screen capture actions
        screenCaptureReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                Log.d(TAG, "ScreenCaptureService received action: " + action);
                android.widget.Toast.makeText(context, "ScreenCaptureService received: " + action, android.widget.Toast.LENGTH_SHORT).show();
                if (ACTION_SCREENSHOT_ONCE.equals(action)) {
                    if (mediaProjection != null) {
                        captureScreenshot();
                    } else {
                        Log.w(TAG, "No MediaProjection for screenshot");
                        android.widget.Toast.makeText(context, "ScreenCaptureService: No MediaProjection permission. Please grant screen capture permission.", android.widget.Toast.LENGTH_LONG).show();
                    }
                } else if (ACTION_START_SCREEN_CAPTURE.equals(action)) {
                    if (mediaProjection != null) {
                        startPeriodicCapture();
                    } else {
                        Log.w(TAG, "No MediaProjection for start capture");
                        android.widget.Toast.makeText(context, "ScreenCaptureService: No MediaProjection permission. Please grant screen capture permission.", android.widget.Toast.LENGTH_LONG).show();
                    }
                } else if (ACTION_STOP_SCREEN_CAPTURE.equals(action)) {
                    stopProjection();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SCREENSHOT_ONCE);
        filter.addAction(ACTION_START_SCREEN_CAPTURE);
        filter.addAction(ACTION_STOP_SCREEN_CAPTURE);
        registerReceiver(screenCaptureReceiver, filter);
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand called");
        if (intent != null && intent.hasExtra("resultCode") && intent.hasExtra("data")) {
            int resultCode = intent.getIntExtra("resultCode", -1);
            Intent data = intent.getParcelableExtra("data");
            Log.d(TAG, "Received resultCode: " + resultCode + ", data: " + (data != null));
            if (resultCode != -1 && data != null) {
                MediaProjectionManager mediaProjectionManager = 
                        (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data);
                lastResultCode = resultCode;
                lastData = data;
                Log.d(TAG, "MediaProjection created and static fields set");
                // Only create virtual display if not already created
                if (virtualDisplay == null) {
                    startVirtualDisplay(null); // Will create a new virtual display
                }
            }
        } else if (lastResultCode != -1 && lastData != null && mediaProjection == null) {
            MediaProjectionManager mediaProjectionManager = 
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            mediaProjection = mediaProjectionManager.getMediaProjection(lastResultCode, lastData);
            Log.d(TAG, "MediaProjection restored from static fields");
            if (virtualDisplay == null) {
                startVirtualDisplay(null);
            }
        } else {
            Log.d(TAG, "No new permission intent, using existing MediaProjection if available");
        }
        // Keep service alive as long as possible
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
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(reader -> {
            if (isCapturing) {
                captureScreenshot();
            }
        }, handler);
        
        // Create virtual display
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenCapture",
                screenWidth, screenHeight, screenDensity,
                VIRTUAL_DISPLAY_FLAGS,
                imageReader.getSurface(),
                null, handler);
        
        Log.d(TAG, "Virtual display created: " + screenWidth + "x" + screenHeight);
        
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
            new Handler(Looper.getMainLooper()).post(() ->
                android.widget.Toast.makeText(getApplicationContext(), "ScreenCaptureService: ImageReader is null", android.widget.Toast.LENGTH_LONG).show()
            );
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
                File screenshotFile = saveBitmapToFile(bitmap);
                if (screenshotFile != null) {
                    Log.d(TAG, "Screenshot saved: " + screenshotFile.getAbsolutePath());
                    android.widget.Toast.makeText(getApplicationContext(), "Screenshot captured", android.widget.Toast.LENGTH_SHORT).show();
                    uploadScreenshot(screenshotFile);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error capturing screenshot: " + e.getMessage());
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
        int rowPadding = rowStride - pixelStride * screenWidth;
        
        // Create bitmap
        Bitmap bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(buffer);
        
        // Crop the bitmap if needed
        if (bitmap.getWidth() > screenWidth || bitmap.getHeight() > screenHeight) {
            Bitmap croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight);
            bitmap.recycle();
            return croppedBitmap;
        }
        
        return bitmap;
    }
    
    private File saveBitmapToFile(Bitmap bitmap) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        if (!picturesDir.exists()) picturesDir.mkdirs();
        File screenshotFile = new File(picturesDir, "screenshot_" + timestamp + ".jpg");

        try (FileOutputStream output = new FileOutputStream(screenshotFile)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output);
            Log.d(TAG, "Screenshot saved to file: " + screenshotFile.getAbsolutePath());

            // Notify media scanner so it appears in the gallery
            MediaScannerConnection.scanFile(
                getApplicationContext(),
                new String[]{screenshotFile.getAbsolutePath()},
                new String[]{"image/jpeg"},
                (path, uri) -> {
                    Log.d(TAG, "MediaScanner scanned: " + path + ", uri: " + uri);
                    new Handler(Looper.getMainLooper()).post(() ->
                        android.widget.Toast.makeText(getApplicationContext(), "Screenshot saved to gallery: " + path, android.widget.Toast.LENGTH_LONG).show()
                    );
                }
            );

            return screenshotFile;
        } catch (IOException e) {
            Log.e(TAG, "Error saving screenshot to file", e);
            new Handler(Looper.getMainLooper()).post(() ->
                android.widget.Toast.makeText(getApplicationContext(), "Failed to save screenshot: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show()
            );
            return null;
        }
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
                    Log.e(TAG, "Failed to upload screenshot: " + e.getMessage());
                }
                
                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        String responseBody = response.body() != null ? response.body().string() : "No response body";
                        if (response.isSuccessful()) {
                            Log.d(TAG, "Screenshot uploaded successfully");
                            new Handler(Looper.getMainLooper()).post(() ->
                                android.widget.Toast.makeText(getApplicationContext(), "Screenshot sent to server", android.widget.Toast.LENGTH_SHORT).show()
                            );
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
            lastResultCode = -1;
            lastData = null;
            Log.d(TAG, "MediaProjection stopped and static fields cleared");
        }
        Log.d(TAG, "Screen capture stopped");
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        stopProjection();
        if (screenCaptureReceiver != null) {
            unregisterReceiver(screenCaptureReceiver);
        }
        Log.d(TAG, "ScreenCaptureService destroyed");
    }
    
    // Add a method to check if MediaProjection is alive and ready
    public static boolean isMediaProjectionActive() {
        // This checks if the static fields are set and the service is running
        return lastResultCode != -1 && lastData != null;
    }

    public void startProjection(int resultCode, Intent data) {
        if (projectionManager == null) {
            projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        }
        mediaProjection = projectionManager.getMediaProjection(resultCode, data);
        saveMediaProjectionData(resultCode, data);
        // TODO: Set up VirtualDisplay and ImageReader as needed for your capture logic
        // (You can add your existing capture logic here)
    }

    public void startProjectionFromSaved() {
        SharedPreferences prefs = getSharedPreferences("screen_capture_prefs", MODE_PRIVATE);
        int resultCode = prefs.getInt("result_code", -1);
        String dataString = prefs.getString("data_intent", null);
        if (resultCode != -1 && dataString != null) {
            try {
                Intent data = Intent.parseUri(dataString, Intent.URI_INTENT_SCHEME);
                startProjection(resultCode, data);
            } catch (Exception e) {
                Log.e("ScreenCaptureService", "Failed to restore media projection", e);
            }
        }
    }

    private void saveMediaProjectionData(int resultCode, Intent data) {
        SharedPreferences prefs = getSharedPreferences("screen_capture_prefs", MODE_PRIVATE);
        prefs.edit()
             .putInt("result_code", resultCode)
             .putString("data_intent", data.toUri(Intent.URI_INTENT_SCHEME))
             .apply();
    }
}