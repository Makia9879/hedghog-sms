#!/bin/sh
# PROTOTYPE — one command. Serves only the throwaway UI demo, not the Android app.
set -eu
cd "$(dirname "$0")"
NAME=hedghog-sms-ui-demo
docker build -t "$NAME" .
docker rm -f "$NAME" >/dev/null 2>&1 || true
docker run -d --name "$NAME" -p 8787:80 "$NAME"
echo "http://127.0.0.1:8787"
