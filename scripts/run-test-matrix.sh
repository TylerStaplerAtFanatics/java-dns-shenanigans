#!/bin/bash
#
# DNS Test Matrix Runner
#
# Runs the DNS test harness across different configurations:
# - Java distributions (Corretto 21, 25, Temurin 21, 25)
# - Configuration methods:
#   - System property via -D flag
#   - Security property via java.security file modification
#   - Security property via Security.setProperty() at runtime
# - TTL values (0, 1, 5, 30, -1/default)
#
# Usage:
#   ./run-test-matrix.sh [--quick] [--distribution <name>] [--output-dir <dir>]
#
# Options:
#   --quick           Run a quick test (30s duration, 5s interval)
#   --distribution    Test only specific distribution (corretto-21, corretto-25, temurin-21, temurin-25)
#   --output-dir      Directory to store results (default: ./results)
#   --target-host     DNS hostname to resolve (default: host.docker.internal for local testing)
#   --capture-dns     Capture DNS packets with tcpdump (requires root/CAP_NET_RAW)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# Defaults
QUICK_MODE=false
SPECIFIC_DISTRIBUTION=""
OUTPUT_DIR="${PROJECT_DIR}/results"
TARGET_HOST="host.docker.internal"
CAPTURE_DNS=false
INTERVAL_SECONDS=5
DURATION_SECONDS=120

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --quick)
            QUICK_MODE=true
            DURATION_SECONDS=30
            shift
            ;;
        --distribution)
            SPECIFIC_DISTRIBUTION="$2"
            shift 2
            ;;
        --output-dir)
            OUTPUT_DIR="$2"
            shift 2
            ;;
        --target-host)
            TARGET_HOST="$2"
            shift 2
            ;;
        --capture-dns)
            CAPTURE_DNS=true
            shift
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

# Distributions to test
if [[ -n "$SPECIFIC_DISTRIBUTION" ]]; then
    DISTRIBUTIONS=("$SPECIFIC_DISTRIBUTION")
else
    DISTRIBUTIONS=(
        "corretto-21"
        "corretto-25"
        "temurin-21"
        "temurin-25"
    )
fi

# Configuration methods
CONFIG_METHODS=(
    "system-property"      # -Dnetworkaddress.cache.ttl
    "security-property"    # Modify java.security file
    "runtime-property"     # Security.setProperty() at runtime
    "no-config"            # Baseline - no configuration
)

# TTL values to test
TTL_VALUES=(
    "0"     # No caching
    "1"     # 1 second
    "5"     # 5 seconds
    "30"    # 30 seconds (common default)
    "-1"    # Default/infinite (when security manager present)
)

# Create output directory
mkdir -p "$OUTPUT_DIR"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
RUN_DIR="${OUTPUT_DIR}/${TIMESTAMP}"
mkdir -p "$RUN_DIR"

echo "╔════════════════════════════════════════════════════════════════════════════╗"
echo "║                     DNS Test Matrix Runner                                  ║"
echo "╚════════════════════════════════════════════════════════════════════════════╝"
echo ""
echo "Configuration:"
echo "  Output directory: $RUN_DIR"
echo "  Target host: $TARGET_HOST"
echo "  Interval: ${INTERVAL_SECONDS}s"
echo "  Duration: ${DURATION_SECONDS}s"
echo "  Quick mode: $QUICK_MODE"
echo "  Capture DNS: $CAPTURE_DNS"
echo ""
echo "Test matrix:"
echo "  Distributions: ${DISTRIBUTIONS[*]}"
echo "  Config methods: ${CONFIG_METHODS[*]}"
echo "  TTL values: ${TTL_VALUES[*]}"
echo ""

# Build all Docker images first
echo "Building Docker images..."
for dist in "${DISTRIBUTIONS[@]}"; do
    echo "  Building dns-test:${dist}..."
    docker build \
        -t "dns-test:${dist}" \
        -f "${PROJECT_DIR}/docker/${dist}.Dockerfile" \
        "$PROJECT_DIR" \
        > "${RUN_DIR}/build-${dist}.log" 2>&1
done
echo "  Done!"
echo ""

# Function to run a single test
run_test() {
    local dist="$1"
    local config_method="$2"
    local ttl="$3"

    local test_name="${dist}_${config_method}_ttl${ttl}"
    local log_file="${RUN_DIR}/${test_name}.log"
    local pcap_file="${RUN_DIR}/${test_name}.pcap"

    echo -n "  Testing ${test_name}... "

    # Build the docker run command based on config method
    local docker_args=()
    docker_args+=("-e" "DNS_TARGET_HOST=${TARGET_HOST}")
    docker_args+=("-e" "DNS_INTERVAL_SECONDS=${INTERVAL_SECONDS}")
    docker_args+=("-e" "DNS_DURATION_SECONDS=${DURATION_SECONDS}")

    local java_args=()

    case "$config_method" in
        "system-property")
            # Set via -D system property
            java_args+=("-Dnetworkaddress.cache.ttl=${ttl}")
            java_args+=("-Dnetworkaddress.cache.negative.ttl=${ttl}")
            ;;
        "security-property")
            # We'll create a custom java.security file
            # This requires modifying the container's java.security file
            docker_args+=("-e" "MODIFY_JAVA_SECURITY=true")
            docker_args+=("-e" "DNS_CACHE_TTL=${ttl}")
            docker_args+=("-e" "DNS_CACHE_NEGATIVE_TTL=${ttl}")
            ;;
        "runtime-property")
            # Set via Security.setProperty() at runtime
            docker_args+=("-e" "DNS_SET_SECURITY_PROPERTY=true")
            docker_args+=("-e" "DNS_CACHE_TTL=${ttl}")
            docker_args+=("-e" "DNS_CACHE_NEGATIVE_TTL=${ttl}")
            ;;
        "no-config")
            # Baseline - no configuration
            ;;
    esac

    # Add DNS capture if requested
    if [[ "$CAPTURE_DNS" == "true" ]]; then
        docker_args+=("--cap-add=NET_RAW")
    fi

    # Run the test
    {
        echo "Test: ${test_name}"
        echo "Distribution: ${dist}"
        echo "Config method: ${config_method}"
        echo "TTL: ${ttl}"
        echo "Started: $(date -Iseconds)"
        echo "=========================================="
        echo ""

        # For security-property method, we need a wrapper script
        if [[ "$config_method" == "security-property" ]]; then
            docker run --rm \
                "${docker_args[@]}" \
                "dns-test:${dist}" \
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
                        echo "Modified java.security:"
                        grep networkaddress "$JAVA_SECURITY"
                    fi
                    java DnsTestHarness
                '
        else
            docker run --rm \
                "${docker_args[@]}" \
                "dns-test:${dist}" \
                "${java_args[@]}" \
                DnsTestHarness
        fi

        echo ""
        echo "=========================================="
        echo "Finished: $(date -Iseconds)"
    } > "$log_file" 2>&1

    echo "done (see ${log_file##*/})"
}

# Run the test matrix
echo "Running test matrix..."
echo ""

total_tests=0
for dist in "${DISTRIBUTIONS[@]}"; do
    echo "Distribution: ${dist}"
    for config_method in "${CONFIG_METHODS[@]}"; do
        if [[ "$config_method" == "no-config" ]]; then
            # Only run once for no-config (TTL value doesn't matter)
            run_test "$dist" "$config_method" "default"
            ((total_tests++))
        else
            for ttl in "${TTL_VALUES[@]}"; do
                run_test "$dist" "$config_method" "$ttl"
                ((total_tests++))
            done
        fi
    done
    echo ""
done

echo "=========================================="
echo "Test matrix completed!"
echo "Total tests: ${total_tests}"
echo "Results directory: ${RUN_DIR}"
echo ""
echo "To analyze results:"
echo "  grep -h 'query took' ${RUN_DIR}/*.log | sort -t'(' -k2 -n"
echo ""
echo "To find configuration issues:"
echo "  grep -l 'not set' ${RUN_DIR}/*.log"
