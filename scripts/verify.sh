#!/bin/sh
set -eu

BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$BASE_DIR"
./mvnw --batch-mode --no-transfer-progress clean verify
(
    cd frontend
    npm ci
    npm run build
)
