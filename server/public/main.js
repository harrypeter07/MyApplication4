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
let roomId = null;

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
		showStatus("Android interface not available", true);
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
			showStatus("Android interface not available", true);
		}
	} else {
		if (window.Android) {
			window.Android.stopCapture();
			button.textContent = "Start Capture";
			isCapturing = false;
			showStatus("Capture stopped");
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

function showStatus(message, isError = false) {
	const statusDiv = document.getElementById("status");
	statusDiv.textContent = message;
	statusDiv.className = "status " + (isError ? "error" : "success");
}

// Socket.io event handlers
socket.on("connect", () => {
	showStatus("Connected to server");
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
