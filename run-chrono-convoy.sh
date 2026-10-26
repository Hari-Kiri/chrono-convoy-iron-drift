#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="${ROOT_DIR}/backend"
SERVER_BIN="${BACKEND_DIR}/bin/chrono-convoy-server"
SERVER_LOG="${BACKEND_DIR}/server.log"
SERVER_PID_FILE="${BACKEND_DIR}/server.pid"
MIGRATION_SQL="${BACKEND_DIR}/migrations/001_init_ledger.sql"
CLIENT_JAR="${ROOT_DIR}/client/build/libs/chrono-convoy-desktop-1.0.0.jar"
HEALTH_URL="http://127.0.0.1:8080/healthz"
RELEASE_STATUS_URL="http://127.0.0.1:8080/api/v1/release/status"

echo "[Chrono-Convoy v1.0.0] Verifying PostgreSQL 17 and chrono_convoy schema..."
if ! pg_isready -q 2>/dev/null; then
  echo "[Chrono-Convoy v1.0.0] Starting local PostgreSQL service..."
  sudo service postgresql start || service postgresql start || true
fi

psql -U box -d postgres -tc "SELECT 1 FROM pg_database WHERE datname = 'chrono_convoy'" | grep -q 1 || \
  psql -U box -d postgres -c "CREATE DATABASE chrono_convoy;"

if [[ -f "${MIGRATION_SQL}" ]]; then
  psql -U box -d chrono_convoy -q -f "${MIGRATION_SQL}" >/dev/null 2>&1
fi

if [[ ! -x "${SERVER_BIN}" ]]; then
  echo "[Chrono-Convoy v1.0.0] Building backend binary ${SERVER_BIN}..."
  (cd "${BACKEND_DIR}" && go build -o "${SERVER_BIN}" ./cmd/server)
fi

if ! curl -fsS "${RELEASE_STATUS_URL}" >/dev/null 2>&1; then
  echo "[Chrono-Convoy v1.0.0] Starting chrono-convoy-server on 127.0.0.1:8080..."
  pkill -f "chrono-convoy-server" 2>/dev/null || true
  sleep 0.2
  nohup "${SERVER_BIN}" >>"${SERVER_LOG}" 2>&1 &
  SERVER_PID=$!
  echo "${SERVER_PID}" > "${SERVER_PID_FILE}"

  for i in $(seq 1 40); do
    if curl -fsS "${RELEASE_STATUS_URL}" >/dev/null 2>&1; then
      echo "[Chrono-Convoy v1.0.0] Backend online (PID ${SERVER_PID})."
      break
    fi
    sleep 0.25
  done
else
  echo "[Chrono-Convoy v1.0.0] Backend already listening on ${RELEASE_STATUS_URL}."
fi

if ! curl -fsS "${HEALTH_URL}" >/dev/null 2>&1; then
  echo "[Chrono-Convoy v1.0.0] ERROR: Backend failed to respond on ${HEALTH_URL}" >&2
  exit 1
fi

if [[ "${1:-}" == "--server-only" || "${1:-}" == "--check" ]]; then
  echo "[Chrono-Convoy v1.0.0] Release Status Check (${RELEASE_STATUS_URL}):"
  curl -fsS "${RELEASE_STATUS_URL}"
  echo ""
  exit 0
fi

if [[ ! -f "${CLIENT_JAR}" ]]; then
  echo "[Chrono-Convoy v1.0.0] Building desktop fat JAR ${CLIENT_JAR}..."
  (cd "${ROOT_DIR}/client" && gradle desktopJar --no-daemon)
fi

echo "[Chrono-Convoy v1.0.0] Launching desktop client..."
exec java -jar "${CLIENT_JAR}" "$@"
