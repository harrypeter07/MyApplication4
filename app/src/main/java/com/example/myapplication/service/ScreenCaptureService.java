package com.example.myapplication.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

import androidx.core.app.NotificationCompat;

import com.example.myapplication.R;

public class ScreenCaptureService extends Service {
    private static final String CHANNEL_ID = "ScreenCapture";
    private static final int NOTIFICATION_ID = 1;
    private static final String VIRTUAL_DISPLAY_NAME = "ScreenCapture";
    
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private final IBinder binder = new LocalBinder();
    private int width;
    private int height;
    private int density;
    private Surface surface;
    
    public class LocalBinder extends Binder {
        public ScreenCaptureService getService() {
            return ScreenCaptureService.this;
        }
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        
        WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        width = windowManager.getDefaultDisplay().getWidth();
        height = windowManager.getDefaultDisplay().getHeight();
        density = getResources().getDisplayMetrics().densityDpi;
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = createNotification();
        startForeground(NOTIFICATION_ID, notification);
        return START_NOT_STICKY;
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Screen Capture",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Screen capture service notification");
            
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }
    
    private Notification createNotification() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Screen Sharing Active")
                .setContentText("Your screen is being shared")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_LOW);
        
        return builder.build();
    }
    
    public void startProjection(int resultCode, Intent data) {
        try {
            MediaProjectionManager projectionManager = 
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (projectionManager == null) {
                Log.e("ScreenCaptureService", "Failed to get MediaProjectionManager");
                return;
            }
            mediaProjection = projectionManager.getMediaProjection(resultCode, data);
            if (mediaProjection == null) {
                Log.e("ScreenCaptureService", "Failed to create MediaProjection");
                return;
            }
        } catch (Exception e) {
            Log.e("ScreenCaptureService", "Error starting projection: " + e.getMessage());
        }
    }
    
    public void startVirtualDisplay(Surface surface) {
        try {
            this.surface = surface;
            if (mediaProjection != null) {
                virtualDisplay = mediaProjection.createVirtualDisplay(
                        VIRTUAL_DISPLAY_NAME,
                        width,
                        height,
                        density,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        surface,
                        null,
                        null);
                if (virtualDisplay == null) {
                    Log.e("ScreenCaptureService", "Failed to create virtual display");
                    return;
                }
                Log.d("ScreenCaptureService", "Virtual display created successfully");
            } else {
                Log.e("ScreenCaptureService", "MediaProjection is null");
            }
        } catch (Exception e) {
            Log.e("ScreenCaptureService", "Error creating virtual display: " + e.getMessage());
        }
    }
    
    public Surface getSurface() {
        return surface;
    }
    
    public void stopProjection() {
        try {
            if (virtualDisplay != null) {
                virtualDisplay.release();
                virtualDisplay = null;
            }
            if (mediaProjection != null) {
                mediaProjection.stop();
                mediaProjection = null;
            }
            if (surface != null) {
                surface.release();
                surface = null;
            }
            Log.d("ScreenCaptureService", "Screen capture stopped successfully");
        } catch (Exception e) {
            Log.e("ScreenCaptureService", "Error stopping projection: " + e.getMessage());
        }
    }
    
    @Override
    public void onDestroy() {
        stopProjection();
        super.onDestroy();
    }
} 