package com.example.myapplication;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.example.myapplication.service.ScreenCaptureService;

public class ScreenCapturePermissionActivity extends Activity {
    private static final int SCREEN_CAPTURE_REQUEST = 1001;
    private MediaProjectionManager mediaProjectionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_screen_capture_permission);

        mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        TextView explanation = findViewById(R.id.permission_explanation);
        explanation.setText("Screen capture permission is required to use this feature. Please grant permission.");

        Button grantButton = findViewById(R.id.btnGrantPermission);
        grantButton.setOnClickListener(v -> {
            Intent intent = mediaProjectionManager.createScreenCaptureIntent();
            startActivityForResult(intent, SCREEN_CAPTURE_REQUEST);
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SCREEN_CAPTURE_REQUEST && resultCode == RESULT_OK) {
            ScreenCaptureService.lastResultCode = resultCode;
            ScreenCaptureService.lastData = data;
            Toast.makeText(this, "Screen capture permission granted", Toast.LENGTH_SHORT).show();
            finish();
        } else {
            Toast.makeText(this, "Screen capture permission not granted", Toast.LENGTH_SHORT).show();
        }
    }
} 