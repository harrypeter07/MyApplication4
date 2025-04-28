package com.example.myapplication;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;
import com.example.myapplication.service.ImageCaptureService;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "Received broadcast: " + action);
        
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action) ||
            Intent.ACTION_REBOOT.equals(action)) {
            
            // Acquire wake lock to ensure service starts
            PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "MyApplication::BootWakeLock");
            wakeLock.acquire(60*1000L); // 1 minute
            
            try {
                Log.d(TAG, "Starting ImageCaptureService");
                Intent serviceIntent = new Intent(context, ImageCaptureService.class);
                serviceIntent.setAction(ImageCaptureService.ACTION_START_CAPTURE);
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            } finally {
                wakeLock.release();
            }
        }
    }
} 