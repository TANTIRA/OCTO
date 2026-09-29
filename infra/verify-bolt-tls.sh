#!/bin/bash
# Verification script: test the TLS-terminated Bolt route
# Run from local machine after Traefik is configured

set -e

BOLT_DOMAIN="${BOLT_DOMAIN:-bolt-octo.mesta.click}"
BOLT_PORT="${BOLT_PORT:-6687}"
NEO4J_USER="${NEO4J_USER:-neo4j}"
NEO4J_PASSWORD="${NEO4J_PASSWORD}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

if [[ -z "$NEO4J_PASSWORD" ]]; then
    echo -e "${RED}Error: NEO4J_PASSWORD not set${NC}"
    exit 1
fi

echo -e "${GREEN}Testing TLS Bolt router: $BOLT_DOMAIN:$BOLT_PORT${NC}"
echo ""

# Test 1: TLS connectivity
echo "1. Testing TLS handshake..."
if echo | openssl s_client -connect "$BOLT_DOMAIN:$BOLT_PORT" 2>/dev/null | grep -q "Verify return code"; then
    echo -e "${GREEN}✓ TLS handshake successful${NC}"
else
    echo -e "${RED}✗ TLS handshake failed${NC}"
    echo "  Try: openssl s_client -connect $BOLT_DOMAIN:$BOLT_PORT -showcerts"
    exit 1
fi

echo ""

# Test 2: Bolt protocol + auth (requires cypher-shell)
echo "2. Testing Bolt connection + authentication..."
if ! command -v cypher-shell &> /dev/null; then
    echo -e "${YELLOW}⊘ cypher-shell not found (skipping auth test)${NC}"
    echo "  Install: docker run -it --rm neo4j cypher-shell --help"
else
    if cypher-shell -a "bolt+s://$BOLT_DOMAIN:$BOLT_PORT" \
        -u "$NEO4J_USER" -p "$NEO4J_PASSWORD" \
        "RETURN 'Neo4j Bolt TLS working' as message" 2>/dev/null; then
        echo -e "${GREEN}✓ Bolt auth successful${NC}"
    else
        echo -e "${RED}✗ Bolt auth failed${NC}"
        exit 1
    fi
fi

echo ""

# Test 3: Certificate details
echo "3. Certificate details:"
echo | openssl s_client -connect "$BOLT_DOMAIN:$BOLT_PORT" 2>/dev/null | \
    openssl x509 -noout -text | grep -E "Subject:|Issuer:|Not Before|Not After"

echo ""
echo -e "${GREEN}✓ All tests passed${NC}"
echo ""
echo "Next: Open Neo4j Browser at https://neo4j-octo.mesta.click"
echo "      Connect to bolt+s://$BOLT_DOMAIN:$BOLT_PORT"
