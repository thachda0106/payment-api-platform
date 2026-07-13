#!/bin/bash
# ============================================================================
# generate-kafka-certs.sh — one-time TLS certificate generation for SASL_SSL.
# ============================================================================
# Creates a CA + broker cert + client cert (dev only — production uses a
# managed PKI like cert-manager or AWS ACM). Idempotent — skips if files exist.
# ============================================================================
set -euo pipefail
DIR="$(cd "$(dirname "$0")/../shared/config/kafka" && pwd)"
mkdir -p "$DIR/tls"

KSPASS=changeit
VALIDITY=3650

gen() {
  local base="$DIR/tls/$1"
  if [ -f "${base}.p12" ]; then echo "  $1 keystore exists — skipping"; return; fi
  keytool -genkey -alias "$1" -keyalg RSA -keysize 2048 -keystore "${base}.p12" \
    -storetype PKCS12 -storepass "$KSPASS" -keypass "$KSPASS" \
    -dname "CN=$1,OU=PaymentAPI,O=PaymentPlatform,C=US" -validity "$VALIDITY" -noprompt
  keytool -exportcert -alias "$1" -keystore "${base}.p12" -storepass "$KSPASS" \
    -file "${base}.crt" -rfc -noprompt
  echo "  generated $1 keystore + cert"
}

echo "Generating CA..."
ca="$DIR/tls/ca"
if [ ! -f "${ca}.p12" ]; then
  openssl req -new -x509 -keyout "${ca}.key" -out "${ca}.crt" -days "$VALIDITY" \
    -subj "/CN=CA/O=PaymentAPI/C=US" -nodes -noout 2>/dev/null
  openssl pkcs12 -export -out "${ca}.p12" -inkey "${ca}.key" -in "${ca}.crt" \
    -passout pass:"$KSPASS" 2>/dev/null
  echo "  CA generated"
fi

echo "Generating broker & client keystores..."
gen broker
gen client

# Create truststore (trusts the CA)
trust="$DIR/tls/truststore.p12"
if [ ! -f "$trust" ]; then
  keytool -importcert -alias ca -file "${ca}.crt" -keystore "$trust" \
    -storetype PKCS12 -storepass "$KSPASS" -noprompt 2>/dev/null
  echo "  truststore created"
fi

# SCRAM user for SASL_SSL (dev-only creds; prod uses Vault/CI secrets)
echo ""
echo "SCRAM user setup (run INSIDE the kafka container after first start):"
echo "  docker exec payment-kafka \\"
echo "    kafka-configs --bootstrap-server localhost:9092 --command-config /etc/kafka/client-sasl.properties \\"
echo "      --alter --add-config 'SCRAM-SHA-256=[iterations=8192,password=svc-sasl-ssl-secret]' \\"
echo "      --entity-type users --entity-name svc"
echo ""
echo "Certs written to $DIR/tls/"
