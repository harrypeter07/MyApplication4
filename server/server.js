const express = require("express");
const app = express();
const http = require("http").createServer(app);
const io = require("socket.io")(http, {
	cors: {
		origin: "*",
		methods: ["GET", "POST"],
	},
});

const PORT = process.env.PORT || 3000;

// Store active rooms and their participants
const rooms = new Map();

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

	// Handle WebRTC signaling
	socket.on("offer", (data) => {
		console.log("Received offer from:", socket.id);
		socket.to(data.roomId).emit("offer", data);
	});

	socket.on("answer", (data) => {
		console.log("Received answer from:", socket.id);
		socket.to(data.roomId).emit("answer", data);
	});

	socket.on("ice-candidate", (data) => {
		console.log("Received ICE candidate from:", socket.id);
		socket.to(data.roomId).emit("ice-candidate", data);
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
http.listen(PORT, () => {
	console.log(`Signaling server running on port ${PORT}`);
});
