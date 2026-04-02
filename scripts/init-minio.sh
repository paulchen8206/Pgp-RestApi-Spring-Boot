#!/usr/bin/env sh
set -eu

MINIO_ALIAS="local"
MINIO_ENDPOINT="${MINIO_ENDPOINT:-http://minio:9000}"
MINIO_ACCESS_KEY="${MINIO_ACCESS_KEY:-minioadmin}"
MINIO_SECRET_KEY="${MINIO_SECRET_KEY:-minioadmin}"
MINIO_BUCKET="${MINIO_BUCKET:-employee-data}"

mc alias set "$MINIO_ALIAS" "$MINIO_ENDPOINT" "$MINIO_ACCESS_KEY" "$MINIO_SECRET_KEY"
mc mb --ignore-existing "$MINIO_ALIAS/$MINIO_BUCKET"
mc cp /seed/employees.json "$MINIO_ALIAS/$MINIO_BUCKET/employees.json"

echo "MinIO bucket $MINIO_BUCKET seeded with employees.json"
