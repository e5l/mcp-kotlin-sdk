#!/usr/bin/env bash

../gradlew clean jar && npx @modelcontextprotocol/inspector java -jar ../build/libs/mcp-kotlin-sdk-1.0-SNAPSHOT.jar --server