function startScreenCapture() {
	if (window.Android && window.Android.isInterfaceAvailable()) {
		window.Android.startScreenCapture();
		showStatus("Screen capture requested");
	} else {
		socket.emit("screenCaptureCommand", { roomId });
		showStatus("Screen capture command sent to phone");
	}
}
