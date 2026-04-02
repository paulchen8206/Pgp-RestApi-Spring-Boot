#!/usr/bin/env sh
set -eu

CERT_DIR=/shared/certs
mkdir -p "$CERT_DIR"

if [ -f "$CERT_DIR/client.p12" ] && [ -f "$CERT_DIR/truststore.jks" ]; then
  echo "Certificates already initialized"
  exit 0
fi

cd "$CERT_DIR"

openssl genrsa -out ca.key 2048
openssl req -x509 -new -nodes -key ca.key -sha256 -days 3650 \
  -subj "/CN=MockPGP-CA" -out ca.crt

openssl genrsa -out server.key 2048
openssl req -new -key server.key -subj "/CN=mock-pgp-api" -out server.csr
cat > server.ext <<EOF
subjectAltName=DNS:mock-pgp-api,DNS:localhost
EOF
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out server.crt -days 3650 -sha256 \
  -extfile server.ext

openssl genrsa -out client.key 2048
openssl req -new -key client.key -subj "/CN=pgp-client" -out client.csr
openssl x509 -req -in client.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out client.crt -days 3650 -sha256

openssl pkcs12 -export \
  -in client.crt \
  -inkey client.key \
  -name client \
  -out client.p12 \
  -passout pass:changeit

keytool -importcert -noprompt \
  -alias mock-ca \
  -file ca.crt \
  -keystore truststore.jks \
  -storepass changeit

rm -f server.ext

echo "Certificates generated in $CERT_DIR"
