#! /usr/bin/env node

import * as net from 'net';
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { Readable, Writable } from 'stream';
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
    console.log("ListToolsRequest");
    return {
        tools: []
    }
});

async function handleConnection(stdin: Readable, stdout: Writable) {
    const transport = new StdioServerTransport(stdin, stdout);
    await mcpServer.connect(transport);
}

const server = net.createServer((socket) => {
    console.log('Client connected');

    const stdin = new Readable();
    const stdout = new Writable();

    // Pipe socket data to stdin
    socket.on('data', (chunk) => {
        console.log("Socket data", chunk);
        stdin.push(chunk);
    });

    socket.on('end', () => {
        stdin.push(null);
        console.log('Client disconnected');
    });

    // Write stdout to socket
    stdout._write = (chunk, encoding, callback) => {
        console.log("Socket data (string):", chunk.toString());
        socket.write(chunk, encoding, callback);
    };

    socket.on('error', (err) => {
        console.error('Socket error:', err);
    });

    handleConnection(stdin, stdout);
});

server.listen(PORT, () => {
    console.log(`TCP Echo Server listening on port ${PORT}`);
});

server.on('error', (err) => {
    console.error('Server error:', err);
});