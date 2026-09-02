#!/usr/bin/env bash
set -euo pipefail
APP_ID="${1:-}"
if [[ -z "$APP_ID" ]]; then
  echo "applicationId is required"; exit 1
fi
if [[ ! "$APP_ID" =~ ^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+$ ]]; then
  echo "Invalid applicationId: $APP_ID"; exit 1
fi
echo "Valid applicationId: $APP_ID"
