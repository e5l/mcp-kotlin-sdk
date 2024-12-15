#!/usr/bin/env node

import * as net from 'net';
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { Readable, Writable, WritableOptions } from 'stream';
import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { ListToolsRequestSchema } from '@modelcontextprotocol/sdk/types.js';

const PORT = 3000;

const mcpServer = new Server(
    {
        name: "jetbrains/proxy",
        version: "0.1.0",
    },
    {
        capabilities: {
            tools: {
                listChanged: true,
            },
            resources: {},
        },
    },
);

mcpServer.setRequestHandler(ListToolsRequestSchema, async () => {
    console.log("ListToolsRequest received");
    return {
        tools: []
    };
});

async function handleConnection(stdin: Readable, stdout: Writable) {
    const transport = new StdioServerTransport(stdin, stdout);
    try {
        await mcpServer.connect(transport);
    } catch (err) {
        console.error('Error connecting MCP server:', err);
    }
}

// A Writable stream class that writes data back to the socket
class SocketWritable extends Writable {
    private socket: net.Socket;

    constructor(socket: net.Socket, options?: WritableOptions) {
        super(options);
        this.socket = socket;
    }

    _write(chunk: any, encoding: BufferEncoding, callback: (error?: Error | null) => void) {
        console.log("Socket write:", chunk.toString());
        this.socket.write(chunk, encoding, callback);
    }
}

const server = net.createServer((socket) => {
    console.log('Client connected');

    // Create a Readable stream that we will push data into from the socket
    const stdin = new Readable({
        read() {
            // We'll use stdin.push() from socket data events
        },
    });

    // Create a Writable that writes back to the socket
    const stdout = new SocketWritable(socket);

    socket.on('data', (chunk) => {
        const decoded = chunk.toString("utf-8");
        console.log("Socket data:", decoded);
        // Push the raw buffer into stdin so the server can read it
        stdin.push(chunk);
    });

    socket.on('end', () => {
        stdin.push(null); // Signal EOF to the stdin stream
        console.log('Client disconnected');
    });

    socket.on('error', (err) => {
        console.error('Socket error:', err);
    });

    handleConnection(stdin, stdout);
});

server.listen(PORT, () => {
    console.log(`MCP proxy server listening on port ${PORT}`);
});

server.on('error', (err) => {
    console.error('Server error:', err);
});