#!/bin/bash
# Script to set up TLS certificate for Neo4j Bolt TCP router
# Generates a self-signed cert or retrieves one via certbot (Let's Encrypt)
# Run once on the Dokploy host before deploying the Traefik TCP router

set -e

CERT_DIR="/etc/dokploy/traefik/certs"
BOLT_DOMAIN="${BOLT_DOMAIN:-bolt-octo.mesta.click}"
CERT_FILE="$CERT_DIR/bolt.crt"
KEY_FILE="$CERT_DIR/bolt.key"

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

mkdir -p "$CERT_DIR"

# Check if cert already exists
if [[ -f "$CERT_FILE" ]] && [[ -f "$KEY_FILE" ]]; then
    echo -e "${YELLOW}Certificate already exists at $CERT_FILE${NC}"
    echo "To regenerate, delete $CERT_FILE and $KEY_FILE first."
    openssl x509 -in "$CERT_FILE" -noout -dates
    exit 0
fi

echo -e "${GREEN}Setting up TLS certificate for Neo4j Bolt${NC}"
echo "Domain: $BOLT_DOMAIN"
echo "Cert directory: $CERT_DIR"

# Option 1: Self-signed (for testing; expires in 365 days)
echo ""
echo "1. Self-signed cert (testing/internal)"
echo "2. Let's Encrypt (production; requires DNS challenge setup)"
read -p "Choose [1 or 2]: " choice

case $choice in
    1)
        echo -e "${YELLOW}Generating self-signed certificate...${NC}"
        openssl req -x509 -newkey rsa:2048 -nodes \
            -keyout "$KEY_FILE" \
            -out "$CERT_FILE" \
            -days 365 \
            -subj "/CN=$BOLT_DOMAIN/O=OCTO/C=US"
        
        echo -e "${GREEN}Self-signed cert created:${NC}"
        echo "  Cert: $CERT_FILE"
        echo "  Key:  $KEY_FILE"
        openssl x509 -in "$CERT_FILE" -noout -dates
        
        echo ""
        echo -e "${YELLOW}WARNING: Self-signed certs will trigger browser warnings.${NC}"
        echo "For production, use Let's Encrypt or a commercial CA."
        ;;
    
    2)
        if ! command -v certbot &> /dev/null; then
            echo -e "${RED}certbot not found. Install with: apt-get install certbot${NC}"
            exit 1
        fi
        
        echo -e "${YELLOW}Requesting Let's Encrypt certificate...${NC}"
        echo "Ensure $BOLT_DOMAIN DNS points to this host's public IP."
        read -p "Continue? [y/N]: " confirm
        
        if [[ "$confirm" != "y" ]]; then
            echo "Aborted."
            exit 0
        fi
        
        # Use standalone or DNS challenge
        # For TCP (non-HTTP), we typically use DNS-01 challenge
        certbot certonly --standalone \
            -d "$BOLT_DOMAIN" \
            --non-interactive \
            --agree-tos \
            -m "ops@example.com"
        
        # Copy the cert and key into our managed directory
        CERTBOT_DIR="/etc/letsencrypt/live/$BOLT_DOMAIN"
        sudo cp "$CERTBOT_DIR/fullchain.pem" "$CERT_FILE"
        sudo cp "$CERTBOT_DIR/privkey.pem" "$KEY_FILE"
        sudo chown $(whoami):$(whoami) "$CERT_FILE" "$KEY_FILE"
        
        echo -e "${GREEN}Let's Encrypt cert installed:${NC}"
        echo "  Cert: $CERT_FILE"
        echo "  Key:  $KEY_FILE"
        openssl x509 -in "$CERT_FILE" -noout -dates
        
        echo ""
        echo "Set up cert auto-renewal:"
        echo "  sudo certbot renew --quiet  # test with --dry-run first"
        echo "  Then copy renewed certs to $CERT_DIR after renewal"
        ;;
    
    *)
        echo -e "${RED}Invalid choice.${NC}"
        exit 1
        ;;
esac

echo ""
echo -e "${GREEN}Next steps:${NC}"
echo "1. Copy infra/traefik-bolt-entrypoint.yml to /etc/dokploy/traefik/"
echo "2. Copy infra/traefik-bolt-tcp-router.yml to /etc/dokploy/traefik/dynamic/"
echo "3. Reload Traefik: docker exec dokploy-traefik traefik reload"
echo "4. Verify: openssl s_client -connect $BOLT_DOMAIN:6687"
