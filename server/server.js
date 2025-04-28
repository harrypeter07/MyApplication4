const express = require("express");
const app = express();
const multer = require("multer");
const path = require("path");
const fs = require("fs");
const os = require("os");
const cors = require("cors");
const http = require("http");
const { Server } = require("socket.io");

console.log("Server starting...");

// Create HTTP server first
const server = http.createServer(app);

// Enable CORS for all routes
app.use(
	cors({
		origin: "*",
		methods: ["GET", "POST", "OPTIONS"],
		credentials: true,
		allowedHeaders: ["Content-Type", "Authorization"],
	})
);
console.log("CORS middleware added");

// Initialize Socket.IO with CORS configuration
const io = new Server(server, {
	cors: {
		origin: "*",
		methods: ["GET", "POST", "OPTIONS"],
		credentials: true,
		allowedHeaders: ["Content-Type", "Authorization"],
	},
	allowEIO3: true,
	pingTimeout: 60000,
	pingInterval: 25000,
	transports: ["websocket", "polling"],
	path: "/socket.io/",
});
console.log("Socket.IO initialized");

// Function to get local IP addresses
function getLocalIPs() {
	console.log("Getting local IPs...");
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
	console.log("Local IPs found:", addresses);
	return addresses;
}

// Create uploads directory if it doesn't exist
const uploadsDir = path.join(__dirname, "uploads");
console.log("Uploads directory path:", uploadsDir);

try {
	if (!fs.existsSync(uploadsDir)) {
		console.log("Creating uploads directory...");
		fs.mkdirSync(uploadsDir, { recursive: true });
		console.log("Uploads directory created successfully");
	} else {
		console.log("Uploads directory already exists");
	}
} catch (error) {
	console.error("Error creating uploads directory:", error);
}

// Configure multer for image uploads
const storage = multer.diskStorage({
	destination: function (req, file, cb) {
		console.log("Multer destination called for file:", file.originalname);
		cb(null, uploadsDir);
	},
	filename: function (req, file, cb) {
		const filename = Date.now() + "-" + file.originalname;
		console.log("Generated filename:", filename);
		cb(null, filename);
	},
});

const upload = multer({ storage: storage });
console.log("Multer configured");

// Serve static files from public directory
app.use(express.static(path.join(__dirname, "public")));
app.use("/uploads", express.static(uploadsDir));
console.log("Static file serving configured");

// Store active rooms and their participants
const rooms = new Map();
console.log("Rooms Map initialized");

// Handle image uploads
app.post("/upload", upload.single("image"), (req, res) => {
	console.log("POST /upload request received");
	console.log("Request body:", req.body);
	console.log("Request file:", req.file);

	if (!req.file) {
		console.log("No file uploaded");
		return res.status(400).send("No file uploaded");
	}

	const imageUrl = `/uploads/${req.file.filename}`;
	console.log("Image uploaded:", imageUrl);

	try {
		// Notify all connected clients about the new image
		io.emit("new-image", { url: imageUrl });
		console.log("New image notification sent to all clients");
		res.json({ success: true, url: imageUrl });
	} catch (error) {
		console.error("Error handling upload:", error);
		res.status(500).json({ success: false, error: error.message });
	}
});

// Health check endpoint
app.get("/health", (req, res) => {
	res.json({ status: "ok", time: new Date().toISOString() });
});

// Socket.io connection handling
io.on("connection", (socket) => {
	console.log("New socket connection:", socket.id);

	// Log all socket events
	socket.onAny((event, ...args) => {
		console.log(`[Socket Event] ${event}:`, args);
	});

	socket.on("join", (data) => {
		const roomId = data.roomId;
		console.log("User", socket.id, "joining room:", roomId);

		try {
			socket.join(roomId);
			if (!rooms.has(roomId)) {
				rooms.set(roomId, new Set());
			}
			rooms.get(roomId).add(socket.id);
			socket.to(roomId).emit("user-joined", { userId: socket.id });
		} catch (error) {
			console.error("Error in join handler:", error);
		}
	});

	socket.on("cameraCommand", (data) => {
		const roomId = data.roomId;
		socket.to(roomId).emit("cameraCommand", data);
	});

	socket.on("startScreenShare", (data) => {
		const roomId = data.roomId;
		console.log("Starting screen share in room:", roomId);
		socket.to(roomId).emit("screenShareStarted", { userId: socket.id });
	});

	socket.on("stopScreenShare", (data) => {
		const roomId = data.roomId;
		console.log("Stopping screen share in room:", roomId);
		socket.to(roomId).emit("screenShareStopped", { userId: socket.id });
	});

	socket.on("disconnect", () => {
		console.log("User disconnected:", socket.id);
		rooms.forEach((participants, roomId) => {
			if (participants.has(socket.id)) {
				participants.delete(socket.id);
				socket.to(roomId).emit("user-left", { userId: socket.id });
				if (participants.size === 0) {
					rooms.delete(roomId);
				}
			}
		});
	});
});

// Start server
const PORT = process.env.PORT || 3000;
const HOST = process.env.HOST || "0.0.0.0";
server.listen(PORT, HOST, () => {
	console.log(`Server running on port ${PORT}`);
	console.log("Available on:");
	getLocalIPs().forEach((ip) => {
		console.log(`  http://${ip}:${PORT}`);
	});
});

// Vercel serverless function handler
module.exports = (req, res) => {
	if (req.url.startsWith("/socket.io/")) {
		// Handle Socket.IO requests
		server.emit("request", req, res);
	} else {
		// Handle regular HTTP requests
		app(req, res);
	}
};
