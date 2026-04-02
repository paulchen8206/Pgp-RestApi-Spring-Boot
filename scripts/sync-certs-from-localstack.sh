#!/usr/bin/env sh
set -eu

AWS_ENDPOINT="${AWS_ENDPOINT:-http://localstack:4566}"
AWS_REGION="${AWS_REGION:-us-east-1}"
TARGET_DIR="${TARGET_DIR:-/shared/certs}"

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

fetch_param_to_file "/enterprise/pki/ca.crt" "$TARGET_DIR/ca.crt"
fetch_param_to_file "/enterprise/pki/server.crt" "$TARGET_DIR/server.crt"
fetch_param_to_file "/enterprise/pki/server.key" "$TARGET_DIR/server.key"
fetch_param_to_file "/enterprise/pki/client.crt" "$TARGET_DIR/client.crt"
fetch_param_to_file "/enterprise/pki/client.key" "$TARGET_DIR/client.key"
fetch_param_to_file "/enterprise/pki/client.p12" "$TARGET_DIR/client.p12"
fetch_param_to_file "/enterprise/pki/truststore.jks" "$TARGET_DIR/truststore.jks"

echo "Enterprise cert artifacts synced from LocalStack to $TARGET_DIR"
