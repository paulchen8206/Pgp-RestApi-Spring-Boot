#!/usr/bin/env sh
set -eu

AWS_ENDPOINT="${AWS_ENDPOINT:-http://localstack:4566}"
AWS_REGION="${AWS_REGION:-us-east-1}"
MATERIALS_ROOT="${MATERIALS_ROOT:-/materials}"

put_file_as_secure_param() {
  param_name="$1"
  file_path="$2"
  encoded_value=$(base64 "$file_path" | tr -d '\n')

  aws --endpoint-url "$AWS_ENDPOINT" --region "$AWS_REGION" ssm put-parameter \
    --name "$param_name" \
    --type SecureString \
    --overwrite \
    --value "$encoded_value" >/dev/null
}

aws --endpoint-url "$AWS_ENDPOINT" --region "$AWS_REGION" sts get-caller-identity >/dev/null 2>&1 || true

put_file_as_secure_param "/enterprise/pki/ca.crt" "$MATERIALS_ROOT/certs/ca.crt"
put_file_as_secure_param "/enterprise/pki/server.crt" "$MATERIALS_ROOT/certs/server.crt"
put_file_as_secure_param "/enterprise/pki/server.key" "$MATERIALS_ROOT/certs/server.key"
put_file_as_secure_param "/enterprise/pki/client.crt" "$MATERIALS_ROOT/certs/client.crt"
put_file_as_secure_param "/enterprise/pki/client.key" "$MATERIALS_ROOT/certs/client.key"
put_file_as_secure_param "/enterprise/pki/client.p12" "$MATERIALS_ROOT/certs/client.p12"
put_file_as_secure_param "/enterprise/pki/truststore.jks" "$MATERIALS_ROOT/certs/truststore.jks"

put_file_as_secure_param "/enterprise/pgp/client-public.asc" "$MATERIALS_ROOT/pgp/client-public.asc"
put_file_as_secure_param "/enterprise/pgp/client-private.asc" "$MATERIALS_ROOT/pgp/client-private.asc"
put_file_as_secure_param "/enterprise/pgp/server-public.asc" "$MATERIALS_ROOT/pgp/server-public.asc"
put_file_as_secure_param "/enterprise/pgp/server-private.asc" "$MATERIALS_ROOT/pgp/server-private.asc"

echo "LocalStack SSM seeded with enterprise PKI and PGP paths"
