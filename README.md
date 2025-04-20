# Android Screen Sharing App with WebRTC

This is a real-time screen sharing application for Android that uses WebRTC for peer-to-peer video streaming and Socket.IO for signaling.

## Features

- Screen capture using MediaProjection API
- Real-time peer-to-peer video streaming using WebRTC
- Signaling server using Node.js and Socket.IO
- Material Design UI
- Room-based sharing system

## Prerequisites

- Android Studio
- Node.js and npm
- Android device running Android 5.0 (API level 21) or higher

## Setup Instructions

### Signaling Server

1. Navigate to the server directory:

   ```bash
   cd server
   ```

2. Install dependencies:

   ```bash
   npm install
   ```

3. Start the server:
   ```bash
   npm start
   ```

The server will start on port 3000 by default. You can change the port by setting the PORT environment variable.

### Android App

1. Open the project in Android Studio

2. Update the `SIGNALING_SERVER_URL` in `SignalingClient.java` to point to your signaling server:

   ```java
   private static final String SIGNALING_SERVER_URL = "http://your-server-url:3000";
   ```

3. Build and run the app on your Android device

## Usage

### Sharing Your Screen

1. Open the app
2. Enter a room ID
3. Tap "Start Screen Share"
4. Grant screen capture permission when prompted
5. Share the room ID with the viewer

### Viewing a Shared Screen

1. Open the app
2. Enter the room ID shared by the broadcaster
3. Tap "Join Room"
4. The shared screen will appear once connected

## Permissions Required

- `INTERNET`: For network communication
- `RECORD_AUDIO`: For audio capture (optional)
- `FOREGROUND_SERVICE`: For screen capture service
- `SYSTEM_ALERT_WINDOW`: For overlay windows
- Media projection permission (requested at runtime)

## Architecture

- `MainActivity`: Handles screen sharing and room creation
- `ViewerActivity`: Displays the shared screen
- `ScreenCaptureService`: Manages screen capture using MediaProjection
- `WebRTCClient`: Handles WebRTC peer connections and media streaming
- `SignalingClient`: Manages Socket.IO communication with the signaling server

## Contributing

Feel free to submit issues and enhancement requests.

## License

This project is licensed under the MIT License - see the LICENSE file for details.
