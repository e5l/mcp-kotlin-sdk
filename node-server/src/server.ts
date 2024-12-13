import * as net from 'net';

const PORT = 3000;

const server = net.createServer((socket) => {
    console.log('Client connected');

    socket.on('data', (data) => {
        console.log(`Received: ${data}`);
        // Echo the data back to the client
        socket.write(data);
    });

    socket.on('end', () => {
        console.log('Client disconnected');
    });

    socket.on('error', (err) => {
        console.error('Socket error:', err);
    });
});

server.listen(PORT, () => {
    console.log(`TCP Echo Server listening on port ${PORT}`);
});

server.on('error', (err) => {
    console.error('Server error:', err);
});