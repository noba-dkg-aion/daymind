#!/usr/bin/env bash
set -euo pipefail

services=(
  "redis-server"
  "daymind-api"
  "daymind-fava"
)

status=0

# Wait for services to be ready (give them time to start)
echo "Waiting for services to initialize..."
sleep 5

# Check each service with retry logic
wait_for_service() {
  local service="$1"
  local max_attempts=20
  local attempt=1
  
  while [ $attempt -le $max_attempts ]; do
    if systemctl is-active --quiet "$service"; then
      return 0
    fi
    echo "Waiting for $service to become active (attempt $attempt/$max_attempts)..."
    sleep 2
    attempt=$((attempt + 1))
  done
  return 1
}

echo "Service health summary:"
for service in "${services[@]}"; do
  if wait_for_service "$service"; then
    echo "✅ $service is active"
  else
    echo "❌ $service is NOT active after waiting"
    journalctl -u "$service" -n 80 --no-pager || true
    status=1
  fi
done

APP_PORT="${APP_PORT:-8000}"
API_KEY_HEADER="${HEALTHCHECK_API_KEY:-}"
if [ -z "$API_KEY_HEADER" ] && [ -n "${API_KEY:-}" ]; then
  API_KEY_HEADER="${API_KEY}"
fi
if [ -z "$API_KEY_HEADER" ] && [ -n "${API_KEYS:-}" ]; then
  API_KEY_HEADER="${API_KEYS%%,*}"
fi
echo "Checking HTTP endpoints on port ${APP_PORT}"

# Retry logic for HTTP endpoints
check_endpoint() {
  local url="$1"
  local label="$2"
  local max_attempts=10
  local attempt=1
  local curl_opts=("-fsS")
  if [ -n "$API_KEY_HEADER" ]; then
    curl_opts+=("-H" "X-API-Key: ${API_KEY_HEADER}")
  fi
  
  while [ $attempt -le $max_attempts ]; do
    if curl "${curl_opts[@]}" "$url" >/dev/null 2>&1; then
      echo "✅ $label responded"
      return 0
    fi
    echo "Retrying $label (attempt $attempt/$max_attempts)..."
    sleep 2
    attempt=$((attempt + 1))
  done
  echo "❌ $label failed after $max_attempts attempts"
  return 1
}

if check_endpoint "http://127.0.0.1:${APP_PORT}/healthz" "/healthz"; then
  :
else
  status=1
fi

if check_endpoint "http://127.0.0.1:${APP_PORT}/metrics" "/metrics"; then
  :
else
  echo "⚠️  /metrics not available (non-fatal)"
fi

exit "$status"
