import * as net from 'net';

const client = new net.Socket();
const PORT = 3000;
const HOST = '127.0.0.1';

client.connect(PORT, HOST, () => {
    console.log('Connected to server');
    client.write('Hello, Server!');
});

client.on('data', (data) => {
    console.log(`Received from server: ${data}`);
    // Close the connection after receiving the echo
    client.end();
});

client.on('close', () => {
    console.log('Connection closed');
});

client.on('error', (err) => {
    console.error('Client error:', err);
});