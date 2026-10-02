#!/usr/bin/env bash
# One-command burst test.   Usage: ./burst.sh <BASE_URL>      e.g. ./burst.sh https://bookmyseat.onrender.com
# Needs only a JDK 21 (no build step). Tunables: ADMIN_KEY USERS REQUESTS SEATS HOT STORM_USERS CONCURRENCY
set -euo pipefail
cd "$(dirname "$0")"
BASE_URL="${1:-${BASE_URL:-http://localhost:8080}}"
# shellcheck disable=SC2086
exec java ${BURST_JAVA_OPTS:-} burst/Burst.java "$BASE_URL"
