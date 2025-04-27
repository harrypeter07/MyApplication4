package com.example.myapplication.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import com.example.myapplication.MainActivity;
import com.example.myapplication.R;
import okhttp3.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ImageCaptureService extends Service {
    private static final String TAG = "ImageCaptureService";
    private static final String SERVER_URL = "http://192.168.92.36:3000/upload";
    private static final int CAPTURE_INTERVAL_MS = 2000; // Capture every 5 seconds
    private static final String CHANNEL_ID = "image_capture";
    private static final int NOTIFICATION_ID = 1;
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private Handler handler;
    private ExecutorService executorService;
    private boolean isCapturing = false;
    private CameraManager cameraManager;
    private String currentCameraId;
    private boolean isFrontCamera = false;
    private PowerManager.WakeLock wakeLock;
    private NotificationManager notificationManager;
    
    public static final String ACTION_SWITCH_CAMERA = "com.example.myapplication.SWITCH_CAMERA";
    public static final String EXTRA_USE_FRONT_CAMERA = "use_front_camera";
    public static final String ACTION_START_CAPTURE = "com.example.myapplication.START_CAPTURE";
    public static final String ACTION_STOP_CAPTURE = "com.example.myapplication.STOP_CAPTURE";
    private BroadcastReceiver cameraSwitchReceiver;
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate called");
        
        createNotificationChannel();
        acquireWakeLock();
        requestBatteryOptimizationExemption();
        
        handler = new Handler();
        executorService = Executors.newSingleThreadExecutor();
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        
        // Register receiver for camera switching and capture control
        cameraSwitchReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                Log.d(TAG, "Received broadcast with action: " + action);
                
                if (ACTION_SWITCH_CAMERA.equals(action)) {
                    boolean useFront = intent.getBooleanExtra(EXTRA_USE_FRONT_CAMERA, false);
                    Log.d(TAG, "Switching camera, use front: " + useFront);
                    startCamera(useFront);
                } else if (ACTION_START_CAPTURE.equals(action)) {
                    Log.d(TAG, "Starting capture");
                    if (!isCapturing) {
                        startPeriodicCapture();
                    }
                } else if (ACTION_STOP_CAPTURE.equals(action)) {
                    Log.d(TAG, "Stopping capture");
                    isCapturing = false;
                    updateNotification();
                }
            }
        };
        
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SWITCH_CAMERA);
        filter.addAction(ACTION_START_CAPTURE);
        filter.addAction(ACTION_STOP_CAPTURE);
        registerReceiver(cameraSwitchReceiver, filter);
        
        // Start with back camera by default or last used
        SharedPreferences prefs = getSharedPreferences("capture_prefs", MODE_PRIVATE);
        boolean useFront = prefs.getBoolean("use_front_camera", false);
        startCamera(useFront);
        
        // Start as foreground service
        startForeground(NOTIFICATION_ID, createNotification());
    }
    
    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent();
                intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }
        }
    }
    
    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE);
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Image Capture Service")
                .setContentText(isCapturing ? "Capturing images..." : "Ready to capture")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }
    
    private void updateNotification() {
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, createNotification());
        }
    }
    
    private void acquireWakeLock() {
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "MyApplication::ImageCaptureWakeLock");
        wakeLock.acquire(10*60*1000L); // 10 minutes
    }
    
    private void startCamera(boolean frontCamera) {
        // Save camera preference
        SharedPreferences prefs = getSharedPreferences("capture_prefs", MODE_PRIVATE);
        prefs.edit().putBoolean("use_front_camera", frontCamera).apply();
        
        try {
            // Close existing camera if any
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            
            // Find the appropriate camera
            String[] cameraIds = cameraManager.getCameraIdList();
            for (String cameraId : cameraIds) {
                android.hardware.camera2.CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                Integer facing = characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                
                if (facing != null) {
                    if (frontCamera && facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT) {
                        currentCameraId = cameraId;
                        isFrontCamera = true;
                        break;
                    } else if (!frontCamera && facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) {
                        currentCameraId = cameraId;
                        isFrontCamera = false;
                        break;
                    }
                }
            }
            
            if (currentCameraId == null) {
                Log.e(TAG, "No suitable camera found");
                return;
            }
            
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
                    != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Camera permission not granted");
                return;
            }
            
            Log.d(TAG, "Opening camera: " + currentCameraId);
            cameraManager.openCamera(currentCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    Log.d(TAG, "Camera opened successfully");
                    cameraDevice = camera;
                    createCaptureSession();
                }
                
                @Override
                public void onDisconnected(CameraDevice camera) {
                    Log.e(TAG, "Camera disconnected");
                    camera.close();
                    // Try to reopen the camera
                    handler.postDelayed(() -> startCamera(isFrontCamera), 1000);
                }
                
                @Override
                public void onError(CameraDevice camera, int error) {
                    Log.e(TAG, "Camera error: " + error);
                    camera.close();
                    // Try to reopen the camera
                    handler.postDelayed(() -> startCamera(isFrontCamera), 1000);
                }
            }, handler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to open camera", e);
            // Try to reopen the camera after a delay
            handler.postDelayed(() -> startCamera(frontCamera), 1000);
        }
    }
    
    private void createCaptureSession() {
        try {
            if (imageReader != null) {
                imageReader.close();
            }
            
            // Use a lower resolution for better performance
            imageReader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(reader -> {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image != null) {
                        Log.d(TAG, "Image captured successfully, processing...");
                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buffer.remaining()];
                        buffer.get(bytes);
                        String timestamp = String.valueOf(System.currentTimeMillis());
                        File imageFile = new File(getCacheDir(), "capture_" + timestamp + ".jpg");
                        try (FileOutputStream output = new FileOutputStream(imageFile)) {
                            output.write(bytes);
                            Log.d(TAG, "Image saved to file: " + imageFile.getAbsolutePath());
                            uploadImage(imageFile);
                        } catch (IOException e) {
                            Log.e(TAG, "Error saving image to file", e);
                        }
                    } else {
                        Log.w(TAG, "Acquired image was null");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error processing captured image", e);
                } finally {
                    if (image != null) {
                        image.close();
                    }
                }
            }, handler);
            
            Log.d(TAG, "Creating capture session");
            cameraDevice.createCaptureSession(Arrays.asList(imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            Log.d(TAG, "Capture session configured successfully");
                            captureSession = session;
                            if (isCapturing) {
                                startPeriodicCapture();
                            }
                        }
                        
                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            Log.e(TAG, "Failed to configure capture session");
                            handler.postDelayed(() -> {
                                Log.d(TAG, "Retrying capture session configuration");
                                createCaptureSession();
                            }, 1000);
                        }
                    }, handler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to create capture session", e);
            handler.postDelayed(() -> createCaptureSession(), 1000);
        }
    }
    
    private void startPeriodicCapture() {
        Log.d(TAG, "Starting periodic capture");
        isCapturing = true;
        updateNotification();
        
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!isCapturing) {
                    Log.d(TAG, "Periodic capture stopped");
                    return;
                }
                
                if (captureSession != null && cameraDevice != null) {
                    try {
                        CaptureRequest.Builder builder = 
                            cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                        builder.addTarget(imageReader.getSurface());
                        
                        // Set auto-focus mode
                        builder.set(CaptureRequest.CONTROL_AF_MODE, 
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        
                        // Set auto-exposure mode
                        builder.set(CaptureRequest.CONTROL_AE_MODE, 
                            CaptureRequest.CONTROL_AE_MODE_ON);
                        
                        Log.d(TAG, "Capturing image...");
                        captureSession.capture(builder.build(), new CameraCaptureSession.CaptureCallback() {
                            @Override
                            public void onCaptureCompleted(CameraCaptureSession session,
                                    CaptureRequest request, TotalCaptureResult result) {
                                super.onCaptureCompleted(session, request, result);
                                Log.d(TAG, "Image capture completed");
                            }
                            
                            @Override
                            public void onCaptureFailed(CameraCaptureSession session,
                                    CaptureRequest request, CaptureFailure failure) {
                                super.onCaptureFailed(session, request, failure);
                                Log.e(TAG, "Image capture failed: " + failure.getReason());
                            }
                        }, handler);
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "Failed to capture image", e);
                        createCaptureSession();
                    }
                    handler.postDelayed(this, CAPTURE_INTERVAL_MS);
                } else {
                    Log.w(TAG, "Camera not ready, retrying in 1 second");
                    handler.postDelayed(this, 1000);
                }
            }
        });
    }
    
    private void uploadImage(File imageFile) {
        Log.d(TAG, "Starting image upload: " + imageFile.getName());
        
        if (!imageFile.exists()) {
            Log.e(TAG, "Image file does not exist: " + imageFile.getAbsolutePath());
            return;
        }
        
        try {
            // Create a multipart request body with the correct field name "image"
            RequestBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("image", imageFile.getName(),
                            RequestBody.create(MediaType.parse("image/jpeg"), imageFile))
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
                    Log.e(TAG, "Failed to upload image: " + e.getMessage(), e);
                    // Keep the file if upload fails
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        String responseBody = response.body() != null ? response.body().string() : "No response body";
                        if (response.isSuccessful()) {
                            Log.d(TAG, "Image uploaded successfully: " + imageFile.getName() +
                                    ", Response: " + response.code() + ", Body: " + responseBody);
                            if (imageFile.exists()) {
                                if (imageFile.delete()) {
                                    Log.d(TAG, "Temporary image file deleted");
                                } else {
                                    Log.w(TAG, "Failed to delete temporary image file");
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
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Image Capture Service",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows the status of the image capture service");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand called");
        
        if (intent != null) {
            String action = intent.getAction();
            if (action != null) {
                Log.d(TAG, "Received action: " + action);
                
                if (ACTION_SWITCH_CAMERA.equals(action)) {
                    boolean useFrontCamera = intent.getBooleanExtra(EXTRA_USE_FRONT_CAMERA, false);
                    Log.d(TAG, "Switching camera, use front: " + useFrontCamera);
                    startCamera(useFrontCamera);
                } else if (ACTION_START_CAPTURE.equals(action)) {
                    Log.d(TAG, "Starting capture");
                    if (!isCapturing) {
                        startPeriodicCapture();
                    }
                } else if (ACTION_STOP_CAPTURE.equals(action)) {
                    Log.d(TAG, "Stopping capture");
                    isCapturing = false;
                    updateNotification();
                }
            }
        }

        // If the service is killed, restart it
        return START_STICKY;
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy called");
        isCapturing = false;
        if (captureSession != null) {
            captureSession.close();
        }
        if (cameraDevice != null) {
            cameraDevice.close();
        }
        if (imageReader != null) {
            imageReader.close();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        executorService.shutdown();
        unregisterReceiver(cameraSwitchReceiver);
        
        // Restart the service if it was killed
        Intent restartService = new Intent(getApplicationContext(), ImageCaptureService.class);
        startService(restartService);
        
        super.onDestroy();
    }
} 