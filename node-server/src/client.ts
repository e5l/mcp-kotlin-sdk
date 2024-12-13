#! /usr/bin/env node

import * as net from 'net';
import {Readable, Writable} from "stream";

const client = new net.Socket();
const PORT = 3000;
const HOST = '127.0.0.1';


async function startClient(input, output) {

}

const input = new Readable()
const output = new Writable()

client.connect(PORT, HOST, () => {
    console.log('Connected to server');

    output._write = (chunk, encoding, callback) => {
        console.log(chunk.toString());
        input.push(chunk);
        callback();
    }

    startClient(input, output);
});


client.on('data', (data) => {
    input.push(data);
});

client.on('close', () => {
    input.push(null);
});

client.on('error', (err) => {
    console.error('Client error:', err);
});

input.push("Hello");