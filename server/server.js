const express = require("express");
const app = express();
const multer = require("multer");
const path = require("path");
const fs = require("fs");
const os = require("os");

// Function to get local IP addresses
function getLocalIPs() {
	const interfaces = os.networkInterfaces();
	const addresses = [];

	for (const k in interfaces) {
		for (const k2 in interfaces[k]) {
			const address = interfaces[k][k2];
			if (address.family === "IPv4" && !address.internal) {
				addresses.push(address.address);
			}
		}
	}
	return addresses;
}

// Create uploads directory if it doesn't exist
const uploadsDir = path.join(__dirname, "public", "uploads");
if (!fs.existsSync(uploadsDir)) {
	fs.mkdirSync(uploadsDir, { recursive: true });
}

// Configure multer for image uploads
const storage = multer.diskStorage({
	destination: function (req, file, cb) {
		cb(null, uploadsDir);
	},
	filename: function (req, file, cb) {
		cb(null, Date.now() + "-" + file.originalname);
	},
});

const upload = multer({ storage: storage });

// Serve static files from public directory
app.use(express.static("public"));
app.use("/uploads", express.static("public/uploads"));

// Create HTTP server
const server = require("http").createServer(app);
const io = require("socket.io")(server, {
	cors: {
		origin: "*",
		methods: ["GET", "POST"],
	},
});

const PORT = process.env.PORT || 3000;

// Store active rooms and their participants
const rooms = new Map();

// Serve the main page
app.get("/", (req, res) => {
	res.sendFile(path.join(__dirname, "public", "index.html"));
});

// Handle image uploads
app.post("/upload", upload.single("image"), (req, res) => {
	if (!req.file) {
		return res.status(400).send("No file uploaded");
	}

	const imageUrl = `/uploads/${req.file.filename}`;
	console.log("Image uploaded:", imageUrl);

	// Notify all connected clients about the new image
	io.emit("new-image", { url: imageUrl });

	res.json({ success: true, url: imageUrl });
});

// Socket.io connection handling
io.on("connection", (socket) => {
	console.log("A user connected:", socket.id);

	socket.on("join", (data) => {
		const roomId = data.roomId;
		console.log("User", socket.id, "joining room:", roomId);

		// Join the room
		socket.join(roomId);

		// Initialize room if it doesn't exist
		if (!rooms.has(roomId)) {
			rooms.set(roomId, new Set());
		}
		rooms.get(roomId).add(socket.id);

		// Notify other participants in the room
		socket.to(roomId).emit("user-joined", { userId: socket.id });
	});

	// Handle camera selection
	socket.on("selectCamera", (data) => {
		const { roomId, cameraType } = data;
		console.log("Camera selection in room", roomId, ":", cameraType);

		// Broadcast camera selection to all clients in the room
		socket.to(roomId).emit("cameraSelected", {
			cameraType: cameraType,
			userId: socket.id,
		});
	});

	// Handle start capture
	socket.on("startCapture", (data) => {
		const { roomId, cameraType } = data;
		console.log("Start capture in room", roomId, "with camera:", cameraType);

		// Broadcast start capture to all clients in the room
		socket.to(roomId).emit("captureStarted", {
			cameraType: cameraType,
			userId: socket.id,
		});
	});

	// Handle stop capture
	socket.on("stopCapture", (data) => {
		const { roomId } = data;
		console.log("Stop capture in room", roomId);

		// Broadcast stop capture to all clients in the room
		socket.to(roomId).emit("captureStopped", {
			userId: socket.id,
		});
	});

	// Handle WebRTC signaling
	socket.on("offer", (data) => {
		console.log("Received offer from:", socket.id);
		socket.to(data.roomId).emit("offer", data);
	});

	socket.on("screen-offer", (data) => {
		console.log("Received screen offer from:", socket.id);
		socket.to(data.roomId).emit("screen-offer", data);
	});

	socket.on("screen-answer", (data) => {
		console.log("Received screen answer from:", socket.id);
		socket.to(data.roomId).emit("screen-answer", data);
	});

	socket.on("screen-ice-candidate", (data) => {
		console.log("Received screen ICE candidate from:", socket.id);
		socket.to(data.roomId).emit("screen-ice-candidate", data);
	});

	socket.on("join-screen", (data) => {
		const roomId = data.roomId;
		socket.join(roomId);
		console.log("Viewer joined screen sharing room:", roomId);
	});

	socket.on("answer", (data) => {
		console.log("Received answer from:", socket.id);
		socket.to(data.roomId).emit("answer", data);
	});

	socket.on("ice-candidate", (data) => {
		console.log("Received ICE candidate from:", socket.id);
		socket.to(data.roomId).emit("ice-candidate", data);
	});

	// Handle camera commands
	socket.on("cameraCommand", (data) => {
		console.log("Received camera command:", data);

		// Log the command details for debugging
		console.log(
			`Command: ${data.command}, Camera Type: ${
				data.cameraType || "N/A"
			}, Room: ${data.roomId}`
		);

		// Broadcast the command to all clients in the same room
		// This includes the sender, so the Android app will receive it
		io.to(data.roomId).emit("cameraCommand", data);

		// Also emit to all clients for testing
		io.emit("cameraCommand", data);
	});

	// Handle disconnection
	socket.on("disconnect", () => {
		console.log("User disconnected:", socket.id);

		// Remove user from all rooms they were in
		rooms.forEach((participants, roomId) => {
			if (participants.has(socket.id)) {
				participants.delete(socket.id);

				// Notify other participants
				socket.to(roomId).emit("user-left", { userId: socket.id });

				// Remove room if empty
				if (participants.size === 0) {
					rooms.delete(roomId);
				}
			}
		});
	});
});

// Start server
const HOST = "0.0.0.0"; // Listen on all network interfaces
server.listen(PORT, HOST, () => {
	console.log(`Server running on port ${PORT}`);
	console.log("Available on:");
	getLocalIPs().forEach((ip) => {
		console.log(`  http://${ip}:${PORT}`);
	});
});
