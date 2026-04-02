#!/usr/bin/env sh
set -eu

AWS_ENDPOINT="${AWS_ENDPOINT:-http://localstack:4566}"
AWS_REGION="${AWS_REGION:-us-east-1}"
TARGET_DIR="${TARGET_DIR:-/shared/pgp}"

fetch_param_to_file() {
  param_name="$1"
  file_path="$2"

  aws --endpoint-url "$AWS_ENDPOINT" --region "$AWS_REGION" ssm get-parameter \
    --name "$param_name" \
    --with-decryption \
    --query "Parameter.Value" \
    --output text | base64 -d > "$file_path"
}

mkdir -p "$TARGET_DIR"

fetch_param_to_file "/enterprise/pgp/client-public.asc" "$TARGET_DIR/client-public.asc"
fetch_param_to_file "/enterprise/pgp/client-private.asc" "$TARGET_DIR/client-private.asc"
fetch_param_to_file "/enterprise/pgp/server-public.asc" "$TARGET_DIR/server-public.asc"
fetch_param_to_file "/enterprise/pgp/server-private.asc" "$TARGET_DIR/server-private.asc"

echo "Enterprise PGP artifacts synced from LocalStack to $TARGET_DIR"
