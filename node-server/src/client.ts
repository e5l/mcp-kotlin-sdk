#!/usr/bin/env node

import * as net from 'net';
import { Readable, Writable } from 'stream';

const client = new net.Socket();
const PORT = 3000;
const HOST = '127.0.0.1';

// If startClient is supposed to be asynchronous and do something later,
// keep async. Otherwise, remove async if not needed.
function startClient(input: Readable, output: Writable) {
    // TODO: Implement client logic here if needed
    // For now, it's just a placeholder.
}

const input = new Readable({
    read() {
        // No-op: We'll push data into this stream externally.
    }
});

const output = new Writable({
    write(chunk, encoding, callback) {
        console.log('Writing to output (and pushing to input):', chunk.toString('utf-8'));
        input.push(chunk);
        callback();
    }
});

client.connect(PORT, HOST, () => {
    console.log('Connected to server');
    startClient(input, output);
});

client.on('data', (data) => {
    // When we receive data from the server socket, push it into `input`.
    input.push(data);
});

client.on('close', () => {
    input.push(null); // Signal the end of the input stream
    console.log('Connection closed by server');
});

client.on('error', (err) => {
    console.error('Client error:', err);
});

// Push initial data into `input`. If no reader is attached, this might not be visible.
// Consider adding `input.on('data', ...)` somewhere else if you want to see this data.
input.push("Hello");