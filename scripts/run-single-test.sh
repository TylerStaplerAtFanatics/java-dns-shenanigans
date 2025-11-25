#!/bin/bash
#
# Run a single DNS test with specific configuration
#
# Usage:
#   ./run-single-test.sh --dist corretto-21 --method system-property --ttl 5
#   ./run-single-test.sh --dist corretto-21 --method security-property --ttl 1
#   ./run-single-test.sh --dist corretto-21 --method runtime-property --ttl 0
#   ./run-single-test.sh --dist corretto-21 --method no-config
#
# Options:
#   --dist             Distribution (corretto-21, corretto-25, temurin-21, temurin-25)
#   --method           Config method (system-property, security-property, runtime-property, no-config)
#   --ttl              TTL value in seconds (0, 1, 5, 30, -1)
#   --target-host      DNS hostname to resolve
#   --interval         Interval between DNS queries in seconds (default: 5)
#   --duration         Test duration in seconds (default: 60)
#   --capture-dns      Capture DNS packets in background with tcpdump
#   --interactive      Run interactively (shows output in real-time)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# Defaults
DISTRIBUTION="corretto-21"
CONFIG_METHOD="no-config"
TTL="30"
TARGET_HOST="host.docker.internal"
INTERVAL_SECONDS=5
DURATION_SECONDS=60
CAPTURE_DNS=false
INTERACTIVE=true

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
        --capture-dns)
            CAPTURE_DNS=true
            shift
            ;;
        --interactive)
            INTERACTIVE=true
            shift
            ;;
        --no-interactive)
            INTERACTIVE=false
            shift
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

echo "╔════════════════════════════════════════════════════════════════════════════╗"
echo "║                     Single DNS Test Runner                                  ║"
echo "╚════════════════════════════════════════════════════════════════════════════╝"
echo ""
echo "Configuration:"
echo "  Distribution: $DISTRIBUTION"
echo "  Config method: $CONFIG_METHOD"
echo "  TTL: $TTL"
echo "  Target host: $TARGET_HOST"
echo "  Interval: ${INTERVAL_SECONDS}s"
echo "  Duration: ${DURATION_SECONDS}s"
echo "  Capture DNS: $CAPTURE_DNS"
echo ""

# Build the image
echo "Building Docker image dns-test:${DISTRIBUTION}..."
docker build \
    -t "dns-test:${DISTRIBUTION}" \
    -f "${PROJECT_DIR}/docker/${DISTRIBUTION}.Dockerfile" \
    "$PROJECT_DIR"
echo ""

# Build the docker run command
docker_args=()
docker_args+=("-e" "DNS_TARGET_HOST=${TARGET_HOST}")
docker_args+=("-e" "DNS_INTERVAL_SECONDS=${INTERVAL_SECONDS}")
docker_args+=("-e" "DNS_DURATION_SECONDS=${DURATION_SECONDS}")

java_args=()

case "$CONFIG_METHOD" in
    "system-property")
        echo "Using -D system property to set TTL=${TTL}"
        java_args+=("-Dnetworkaddress.cache.ttl=${TTL}")
        java_args+=("-Dnetworkaddress.cache.negative.ttl=${TTL}")
        ;;
    "security-property")
        echo "Will modify java.security file to set TTL=${TTL}"
        docker_args+=("-e" "MODIFY_JAVA_SECURITY=true")
        docker_args+=("-e" "DNS_CACHE_TTL=${TTL}")
        docker_args+=("-e" "DNS_CACHE_NEGATIVE_TTL=${TTL}")
        ;;
    "runtime-property")
        echo "Will use Security.setProperty() at runtime to set TTL=${TTL}"
        docker_args+=("-e" "DNS_SET_SECURITY_PROPERTY=true")
        docker_args+=("-e" "DNS_CACHE_TTL=${TTL}")
        docker_args+=("-e" "DNS_CACHE_NEGATIVE_TTL=${TTL}")
        ;;
    "no-config")
        echo "Running with default configuration (no TTL override)"
        ;;
    *)
        echo "Unknown config method: $CONFIG_METHOD"
        exit 1
        ;;
esac

if [[ "$CAPTURE_DNS" == "true" ]]; then
    docker_args+=("--cap-add=NET_RAW")
fi

if [[ "$INTERACTIVE" == "true" ]]; then
    docker_args+=("-it")
fi

echo ""
echo "Running test..."
echo "=========================================="

# For security-property method, we need a wrapper script
if [[ "$CONFIG_METHOD" == "security-property" ]]; then
    docker run --rm \
        "${docker_args[@]}" \
        "dns-test:${DISTRIBUTION}" \
        bash -c '
            # Find java.security file
            JAVA_SECURITY=$(find $JAVA_HOME -name "java.security" 2>/dev/null | head -1)
            if [[ -n "$JAVA_SECURITY" ]]; then
                echo "Modifying $JAVA_SECURITY"
                # Backup and modify
                cp "$JAVA_SECURITY" "${JAVA_SECURITY}.bak"
                # Remove existing entries and add new ones
                grep -v "^networkaddress.cache" "$JAVA_SECURITY" > "${JAVA_SECURITY}.tmp" || true
                echo "networkaddress.cache.ttl=${DNS_CACHE_TTL}" >> "${JAVA_SECURITY}.tmp"
                echo "networkaddress.cache.negative.ttl=${DNS_CACHE_NEGATIVE_TTL}" >> "${JAVA_SECURITY}.tmp"
                mv "${JAVA_SECURITY}.tmp" "$JAVA_SECURITY"
                echo ""
                echo "Modified java.security (showing networkaddress entries):"
                grep networkaddress "$JAVA_SECURITY"
                echo ""
            fi
            java DnsTestHarness
        '
else
    docker run --rm \
        "${docker_args[@]}" \
        "dns-test:${DISTRIBUTION}" \
        "${java_args[@]}" \
        DnsTestHarness
fi
