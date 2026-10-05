#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8888}"
FAILED=0

echo "Running smoke tests against $BASE_URL..."

check() {
  local label="$1"
  local path="$2"
  echo "  Testing $path..."
  if curl --retry 5 --retry-connrefused --retry-delay 1 -fsS "$BASE_URL$path" >/dev/null; then
    echo "    OK: $label"
  else
    echo "    FAIL: $label"
    FAILED=1
  fi
}

check "liveness endpoint responding" "/api/health/simple"
check "auth status endpoint responding" "/api/auth/status"
check "static frontend serving" "/"
check "vendor config endpoint responding" "/api/config/vendors"

if [[ "$FAILED" -eq 0 ]]; then
  echo "All smoke tests passed."
  exit 0
fi

echo "Some smoke tests failed."
exit 1
