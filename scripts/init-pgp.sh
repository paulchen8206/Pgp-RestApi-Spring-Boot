#!/usr/bin/env sh
set -eu

PGP_DIR=/shared/pgp
mkdir -p "$PGP_DIR"

if [ -f "$PGP_DIR/client-private.asc" ] && [ -f "$PGP_DIR/server-private.asc" ]; then
  echo "PGP keys already initialized"
  exit 0
fi

GNUPGHOME="$(mktemp -d)"
export GNUPGHOME
chmod 700 "$GNUPGHOME"

cat > client-key.conf <<EOF
Key-Type: RSA
Key-Length: 2048
Subkey-Type: RSA
Subkey-Length: 2048
Name-Real: pgp-client
Name-Email: client@local
Expire-Date: 0
%no-protection
%commit
EOF

cat > server-key.conf <<EOF
Key-Type: RSA
Key-Length: 2048
Subkey-Type: RSA
Subkey-Length: 2048
Name-Real: mock-pgp-api
Name-Email: server@local
Expire-Date: 0
%no-protection
%commit
EOF

gpg --batch --generate-key client-key.conf
gpg --batch --generate-key server-key.conf

gpg --armor --export-secret-keys client@local > "$PGP_DIR/client-private.asc"
gpg --armor --export client@local > "$PGP_DIR/client-public.asc"
gpg --armor --export-secret-keys server@local > "$PGP_DIR/server-private.asc"
gpg --armor --export server@local > "$PGP_DIR/server-public.asc"

rm -rf "$GNUPGHOME" client-key.conf server-key.conf

echo "PGP keys generated in $PGP_DIR"
