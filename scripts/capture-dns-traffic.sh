#!/bin/bash
#
# Capture DNS traffic while running the test harness
#
# This script runs tcpdump inside the container alongside the Java test
# to capture actual DNS queries being made. This is the ground truth for
# verifying whether the JVM is respecting cache settings.
#
# Usage:
#   ./capture-dns-traffic.sh --dist corretto-21 --method system-property --ttl 5
#
# Output:
#   - Console output from Java test
#   - DNS packet capture summary
#   - Timing analysis of DNS queries

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# Defaults
DISTRIBUTION="corretto-21"
CONFIG_METHOD="system-property"
TTL="5"
TARGET_HOST="host.docker.internal"
INTERVAL_SECONDS=5
DURATION_SECONDS=60
OUTPUT_DIR="${PROJECT_DIR}/results"

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --dist)
            DISTRIBUTION="$2"
            shift 2
            ;;
        --method)
            CONFIG_METHOD="$2"
            shift 2
            ;;
        --ttl)
            TTL="$2"
            shift 2
            ;;
        --target-host)
            TARGET_HOST="$2"
            shift 2
            ;;
        --interval)
            INTERVAL_SECONDS="$2"
            shift 2
            ;;
        --duration)
            DURATION_SECONDS="$2"
            shift 2
            ;;
        --output-dir)
            OUTPUT_DIR="$2"
            shift 2
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

TIMESTAMP=$(date +%Y%m%d-%H%M%S)
TEST_NAME="${DISTRIBUTION}_${CONFIG_METHOD}_ttl${TTL}_${TIMESTAMP}"

mkdir -p "$OUTPUT_DIR"

echo "╔════════════════════════════════════════════════════════════════════════════╗"
echo "║                     DNS Traffic Capture Test                                ║"
echo "╚════════════════════════════════════════════════════════════════════════════╝"
echo ""
echo "Configuration:"
echo "  Distribution: $DISTRIBUTION"
echo "  Config method: $CONFIG_METHOD"
echo "  TTL: $TTL"
echo "  Target host: $TARGET_HOST"
echo "  Output: $OUTPUT_DIR/$TEST_NAME.*"
echo ""

# Build the image
echo "Building Docker image..."
docker build \
    -t "dns-test:${DISTRIBUTION}" \
    -f "${PROJECT_DIR}/docker/${DISTRIBUTION}.Dockerfile" \
    "$PROJECT_DIR" > /dev/null 2>&1

# Build environment variables for the container
docker_env=()
docker_env+=("-e" "DNS_TARGET_HOST=${TARGET_HOST}")
docker_env+=("-e" "DNS_INTERVAL_SECONDS=${INTERVAL_SECONDS}")
docker_env+=("-e" "DNS_DURATION_SECONDS=${DURATION_SECONDS}")

# Build java args based on config method
java_args=""
setup_script=""

case "$CONFIG_METHOD" in
    "system-property")
        java_args="-Dnetworkaddress.cache.ttl=${TTL} -Dnetworkaddress.cache.negative.ttl=${TTL}"
        ;;
    "security-property")
        docker_env+=("-e" "DNS_CACHE_TTL=${TTL}")
        docker_env+=("-e" "DNS_CACHE_NEGATIVE_TTL=${TTL}")
        setup_script='
            JAVA_SECURITY=$(find $JAVA_HOME -name "java.security" 2>/dev/null | head -1)
            if [[ -n "$JAVA_SECURITY" ]]; then
                grep -v "^networkaddress.cache" "$JAVA_SECURITY" > "${JAVA_SECURITY}.tmp" || true
                echo "networkaddress.cache.ttl=${DNS_CACHE_TTL}" >> "${JAVA_SECURITY}.tmp"
                echo "networkaddress.cache.negative.ttl=${DNS_CACHE_NEGATIVE_TTL}" >> "${JAVA_SECURITY}.tmp"
                mv "${JAVA_SECURITY}.tmp" "$JAVA_SECURITY"
            fi
        '
        ;;
    "runtime-property")
        docker_env+=("-e" "DNS_SET_SECURITY_PROPERTY=true")
        docker_env+=("-e" "DNS_CACHE_TTL=${TTL}")
        docker_env+=("-e" "DNS_CACHE_NEGATIVE_TTL=${TTL}")
        ;;
    "no-config")
        ;;
esac

echo "Running test with tcpdump..."
echo "=========================================="
echo ""

# Run the test with tcpdump capturing DNS traffic
# We run tcpdump in the background and the Java test in the foreground
docker run --rm \
    --cap-add=NET_RAW \
    "${docker_env[@]}" \
    "dns-test:${DISTRIBUTION}" \
    bash -c "
        ${setup_script}

        # Start tcpdump in background, capturing DNS traffic
        tcpdump -i any -n 'port 53' -l > /tmp/dns-capture.txt 2>&1 &
        TCPDUMP_PID=\$!

        # Give tcpdump a moment to start
        sleep 1

        # Run the Java test
        java ${java_args} DnsTestHarness

        # Stop tcpdump
        kill \$TCPDUMP_PID 2>/dev/null || true
        sleep 1

        # Print DNS capture summary
        echo ''
        echo '=========================================='
        echo 'DNS Traffic Capture Summary'
        echo '=========================================='
        echo ''

        if [[ -s /tmp/dns-capture.txt ]]; then
            echo 'DNS queries captured:'
            cat /tmp/dns-capture.txt

            echo ''
            echo 'Query timing analysis:'
            echo '----------------------'

            # Extract timestamps and count queries
            query_count=\$(grep -c 'A?' /tmp/dns-capture.txt 2>/dev/null || echo 0)
            echo \"Total DNS A queries: \$query_count\"

            # Calculate intervals between queries
            echo ''
            echo 'Timestamps of DNS queries:'
            grep 'A?' /tmp/dns-capture.txt | cut -d' ' -f1 | head -20
        else
            echo 'No DNS traffic captured (might be cached or using different resolver)'
        fi
    " 2>&1 | tee "${OUTPUT_DIR}/${TEST_NAME}.log"

echo ""
echo "=========================================="
echo "Test complete!"
echo "Log file: ${OUTPUT_DIR}/${TEST_NAME}.log"
echo ""
echo "To analyze DNS query intervals, look for timestamps in the log:"
echo "  grep 'A?' ${OUTPUT_DIR}/${TEST_NAME}.log"
