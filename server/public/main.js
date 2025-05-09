// Initialize socket connection with better error handling
const socket = io({
	transports: ["websocket", "polling"],
	reconnection: true,
	reconnectionAttempts: Infinity,
	reconnectionDelay: 1000,
	reconnectionDelayMax: 5000,
	timeout: 20000,
	autoConnect: true,
	forceNew: true,
});

let isCapturing = false;
let roomId = "global_room";
document.getElementById("roomId").textContent = roomId;

// Connection status handling
socket.on("connect_error", (error) => {
	console.error("Connection error:", error);
	showStatus("Connection error: " + error.message, true);
});

socket.on("connect_timeout", (timeout) => {
	console.error("Connection timeout:", timeout);
	showStatus("Connection timeout", true);
});

socket.on("reconnect_attempt", (attemptNumber) => {
	console.log("Reconnection attempt:", attemptNumber);
	showStatus("Attempting to reconnect...");
});

socket.on("reconnect", (attemptNumber) => {
	console.log("Reconnected after", attemptNumber, "attempts");
	showStatus("Reconnected to server");
});

socket.on("reconnect_error", (error) => {
	console.error("Reconnection error:", error);
	showStatus("Reconnection error: " + error.message, true);
});

socket.on("reconnect_failed", () => {
	console.error("Failed to reconnect");
	showStatus("Failed to reconnect to server", true);
});

// Remove manual room join UI and listen for autoRoomJoined event
if (document.getElementById("roomIdInput")) {
	document.getElementById("roomIdInput").remove();
}
if (document.getElementById("joinRoomBtn")) {
	document.getElementById("joinRoomBtn").remove();
}

function selectCamera(cameraType) {
	console.log("Selecting camera:", cameraType);
	if (window.Android) {
		window.Android.selectCamera(cameraType);
		// Update button styles
		document.querySelectorAll(".camera-controls button").forEach((btn) => {
			btn.classList.remove("active");
		});
		document
			.querySelector("." + cameraType + "-camera")
			.classList.add("active");
	} else {
		// Emit cameraCommand event for remote control
		socket.emit("cameraCommand", {
			roomId,
			command: "switchCamera",
			cameraType,
		});
		showStatus("Camera switch command sent to phone");
	}
}

function toggleCapture() {
	const button = document.getElementById("captureButton");
	if (!isCapturing) {
		if (window.Android) {
			window.Android.startCapture();
			button.textContent = "Stop Capture";
			isCapturing = true;
			showStatus("Capture started");
		} else {
			// Emit cameraCommand event for remote control
			socket.emit("cameraCommand", { roomId, command: "startCapture" });
			button.textContent = "Stop Capture";
			isCapturing = true;
			showStatus("Capture command sent to phone");
		}
	} else {
		if (window.Android) {
			window.Android.stopCapture();
			button.textContent = "Start Capture";
			isCapturing = false;
			showStatus("Capture stopped");
		} else {
			// Emit cameraCommand event for remote control
			socket.emit("cameraCommand", { roomId, command: "stopCapture" });
			button.textContent = "Start Capture";
			isCapturing = false;
			showStatus("Stop command sent to phone");
		}
	}
}

function startScreenShare() {
	if (!roomId) {
		roomId = "room_" + Math.random().toString(36).substr(2, 9);
		document.getElementById("roomId").textContent = roomId;
	}
	socket.emit("startScreenShare", { roomId: roomId });
	showStatus("Screen sharing started");
}

function stopScreenShare() {
	if (roomId) {
		socket.emit("stopScreenShare", { roomId: roomId });
		showStatus("Screen sharing stopped");
	}
}

function deleteImages() {
	if (window.Android && window.Android.isInterfaceAvailable()) {
		window.Android.deleteImages();
		showStatus("Deleting all captured images");
	} else {
		// Emit cameraCommand event for remote control
		socket.emit("cameraCommand", { roomId, command: "deleteImages" });
		showStatus("Delete images command sent to phone");
	}
}

function showStatus(message, isError = false) {
	const statusDiv = document.getElementById("status");
	statusDiv.textContent = message;
	statusDiv.className = "status " + (isError ? "error" : "success");
}

// Socket.io event handlers
socket.on("connect", () => {
	showStatus("Connected to server");
	if (
		window.Android &&
		window.Android.isInterfaceAvailable &&
		window.Android.isInterfaceAvailable()
	) {
		socket.emit("clientType", { type: "phone-webview" });
	} else {
		socket.emit("clientType", { type: "web" });
	}
});

socket.on("disconnect", () => {
	showStatus("Disconnected from server", true);
});

socket.on("error", (error) => {
	showStatus("Error: " + error, true);
});

// Check Android interface on load
window.addEventListener("load", () => {
	if (window.Android && window.Android.isInterfaceAvailable()) {
		showStatus("Android interface available");
	} else {
		showStatus("Android interface not available", true);
	}
});

// Log all socket.io events
socket.onAny((event, ...args) => {
	console.log(`[Socket.io Event] ${event}:`, args);
});

socket.on("cameraCommand", (data) => {
	console.log("[Web] cameraCommand event received:", data);
	if (
		window.Android &&
		window.Android.isInterfaceAvailable &&
		window.Android.isInterfaceAvailable()
	) {
		// If running in the app's WebView, trigger native actions
		if (data.command === "startCapture") {
			window.Android.startCapture();
			showStatus("Capture started by remote command");
		} else if (data.command === "stopCapture") {
			window.Android.stopCapture();
			showStatus("Capture stopped by remote command");
		} else if (data.command === "switchCamera") {
			window.Android.selectCamera(data.cameraType);
			showStatus(`Switched camera to ${data.cameraType} by remote command`);
		}
	} else {
		// If in browser, just log and show status
		showStatus(`Received camera command: ${data.command}`);
	}
});

function startScreenCapture() {
	if (window.Android && window.Android.isInterfaceAvailable()) {
		window.Android.startScreenCapture();
		showStatus("Screen capture requested");
	} else {
		socket.emit("screenCaptureCommand", { roomId });
		showStatus("Screen capture command sent to phone");
	}
}

function triggerScreenCaptureImage() {
	if (window.Android && window.Android.isInterfaceAvailable()) {
		window.Android.startScreenCapture();
		showStatus("Screen capture requested");
	} else {
		socket.emit("cameraCommand", { roomId, command: "screenCapture" });
		showStatus("Screen capture command sent to phone");
	}
}

socket.on("accessibility_data", (data) => {
	const eventsDiv = document.getElementById("accessibilityEvents");
	const pretty = JSON.stringify(data, null, 2);
	const el = document.createElement("pre");
	el.textContent = pretty;
	eventsDiv.prepend(el); // Show newest on top
});

socket.on("accessibility_screenshot", (data) => {
	const eventsDiv = document.getElementById("accessibilityEvents");
	const wrapper = document.createElement("div");
	wrapper.style.marginBottom = "16px";
	const meta = document.createElement("div");
	meta.textContent = `EventType: ${data.eventType}, Package: ${
		data.packageName
	}, Time: ${new Date(data.timestamp).toLocaleString()}`;
	meta.style.fontWeight = "bold";
	wrapper.appendChild(meta);
	if (data.image) {
		const img = document.createElement("img");
		img.src = `data:image/jpeg;base64,${data.image}`;
		img.style.maxWidth = "100%";
		img.style.maxHeight = "200px";
		img.style.display = "block";
		img.style.marginTop = "4px";
		img.style.border = "1px solid #ccc";
		wrapper.appendChild(img);
	}
	// Optionally show raw JSON
	const pre = document.createElement("pre");
	pre.textContent = JSON.stringify(data, null, 2);
	pre.style.background = "#f4f4f4";
	pre.style.fontSize = "11px";
	pre.style.marginTop = "4px";
	wrapper.appendChild(pre);
	eventsDiv.prepend(wrapper);
});

// Fetch and display the latest screenshot on load
function fetchLatestScreenshot() {
	fetch("/uploads-list")
		.then((res) => res.json())
		.then((data) => {
			if (data.files && data.files.length > 0) {
				const latest = data.files[data.files.length - 1];
				document.getElementById("latestScreenshot").src = latest;
			}
		});
}

// Listen for new-image events and update the screenshot
socket.on("new-image", (data) => {
	if (data.url) {
		document.getElementById("latestScreenshot").src = data.url;
	}
});

// Call on page load
window.addEventListener("load", fetchLatestScreenshot);

// Add a section for toast messages at the top
let toastDiv = document.getElementById("toastMessages");
if (!toastDiv) {
	toastDiv = document.createElement("div");
	toastDiv.id = "toastMessages";
	toastDiv.style.background = "#fffbe6";
	toastDiv.style.border = "1px solid #ffe58f";
	toastDiv.style.padding = "8px";
	toastDiv.style.margin = "8px 0";
	toastDiv.style.fontWeight = "bold";
	toastDiv.style.color = "#ad6800";
	toastDiv.style.display = "none";
	document.body.insertBefore(toastDiv, document.body.firstChild);
}

socket.on("toast_message", (data) => {
	toastDiv.textContent = `[Device Toast] ${data.message} (${new Date(
		data.timestamp
	).toLocaleTimeString()})`;
	toastDiv.style.display = "block";
	setTimeout(() => {
		toastDiv.style.display = "none";
	}, 6000);
});
