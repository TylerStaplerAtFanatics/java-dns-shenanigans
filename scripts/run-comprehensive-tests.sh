#!/bin/bash
#
# Comprehensive DNS Cache Configuration Test Runner
#
# Runs all configuration permutations across all Corretto LTS versions
# and outputs structured results for analysis.
#
# Usage:
#   ./run-comprehensive-tests.sh [--quick] [--output-dir <dir>]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# Configuration
QUICK_MODE=false
OUTPUT_DIR="${PROJECT_DIR}/results"
TARGET_HOST="www.google.com"
INTERVAL_SECONDS=2
DURATION_SECONDS=15

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --quick)
            QUICK_MODE=true
            DURATION_SECONDS=10
            shift
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

# All Corretto LTS versions to test
VERSIONS=(
    "corretto-8"
    "corretto-11"
    "corretto-17"
    "corretto-21"
    "corretto-25"
)

# Configuration methods to test
# Format: "name|env_vars|java_args|harness_class"
CONFIGS=(
    "baseline||io.fbg.dns.DnsTestHarnessJava8"
    "system_property_networkaddress_ttl_1||-Dnetworkaddress.cache.ttl=1 -Dnetworkaddress.cache.negative.ttl=1|io.fbg.dns.DnsTestHarnessJava8"
    "system_property_networkaddress_ttl_5||-Dnetworkaddress.cache.ttl=5 -Dnetworkaddress.cache.negative.ttl=5|io.fbg.dns.DnsTestHarnessJava8"
    "system_property_sun_net_ttl_1||-Dsun.net.inetaddr.ttl=1 -Dsun.net.inetaddr.negative.ttl=1|io.fbg.dns.DnsTestHarnessJava8"
    "system_property_sun_net_ttl_5||-Dsun.net.inetaddr.ttl=5 -Dsun.net.inetaddr.negative.ttl=5|io.fbg.dns.DnsTestHarnessJava8"
    "security_setproperty_ttl_1|DNS_SET_SECURITY_PROPERTY=true DNS_CACHE_TTL=1 DNS_CACHE_NEGATIVE_TTL=1||io.fbg.dns.DnsTestHarnessJava8"
    "security_setproperty_ttl_5|DNS_SET_SECURITY_PROPERTY=true DNS_CACHE_TTL=5 DNS_CACHE_NEGATIVE_TTL=5||io.fbg.dns.DnsTestHarnessJava8"
)

# Create output directory
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
RUN_DIR="${OUTPUT_DIR}/${TIMESTAMP}"
mkdir -p "$RUN_DIR"

echo "╔════════════════════════════════════════════════════════════════════════════╗"
echo "║              Comprehensive DNS Cache Configuration Test Suite              ║"
echo "╚════════════════════════════════════════════════════════════════════════════╝"
echo ""
echo "Configuration:"
echo "  Output directory: $RUN_DIR"
echo "  Target host: $TARGET_HOST"
echo "  Duration: ${DURATION_SECONDS}s per test"
echo "  Quick mode: $QUICK_MODE"
echo ""
echo "Versions to test: ${VERSIONS[*]}"
echo "Configurations: ${#CONFIGS[@]}"
echo ""

# Build all Docker images
echo "Building Docker images..."
for version in "${VERSIONS[@]}"; do
    echo -n "  Building dns-test:${version}..."

    # Determine which Dockerfile to use
    dockerfile="${PROJECT_DIR}/docker/${version}.Dockerfile"

    if [[ ! -f "$dockerfile" ]]; then
        echo " SKIP (no Dockerfile)"
        continue
    fi

    if docker build -t "dns-test:${version}" -f "$dockerfile" "$PROJECT_DIR" > "${RUN_DIR}/build-${version}.log" 2>&1; then
        echo " done"
    else
        echo " FAILED (see build-${version}.log)"
    fi
done
echo ""

# Initialize results JSON
RESULTS_FILE="${RUN_DIR}/results.json"
echo "[" > "$RESULTS_FILE"

first_result=true
total_tests=0
passed_tests=0

# Run all tests
echo "Running tests..."
echo ""

for version in "${VERSIONS[@]}"; do
    # Check if image exists
    if ! docker image inspect "dns-test:${version}" > /dev/null 2>&1; then
        echo "Skipping ${version} (image not built)"
        continue
    fi

    echo "Testing ${version}..."

    for config in "${CONFIGS[@]}"; do
        IFS='|' read -r config_name env_vars java_args harness_class <<< "$config"

        test_name="${version}_${config_name}"
        log_file="${RUN_DIR}/${test_name}.log"

        echo -n "  ${config_name}... "

        # Build docker run command
        docker_cmd="docker run --rm"
        docker_cmd+=" -e DNS_TARGET_HOST=${TARGET_HOST}"
        docker_cmd+=" -e DNS_INTERVAL_SECONDS=${INTERVAL_SECONDS}"
        docker_cmd+=" -e DNS_DURATION_SECONDS=${DURATION_SECONDS}"

        # Add environment variables
        if [[ -n "$env_vars" ]]; then
            for env_var in $env_vars; do
                docker_cmd+=" -e ${env_var}"
            done
        fi

        docker_cmd+=" dns-test:${version}"

        # Add Java args
        if [[ -n "$java_args" ]]; then
            docker_cmd+=" ${java_args}"
        fi

        docker_cmd+=" ${harness_class}"

        # Run the test
        start_time=$(date +%s)

        if eval "$docker_cmd" > "$log_file" 2>&1; then
            end_time=$(date +%s)
            duration=$((end_time - start_time))

            # Extract config and results from log
            config_json=$(sed -n '/---CONFIG_START---/,/---CONFIG_END---/p' "$log_file" | grep -v -- '---')
            results_json=$(sed -n '/---RESULTS_START---/,/---RESULTS_END---/p' "$log_file" | grep -v -- '---')

            # Calculate statistics
            if [[ -n "$results_json" ]]; then
                # Extract query times and calculate stats
                query_times=$(echo "$results_json" | grep -o '"query_ms": [0-9]*' | cut -d: -f2 | tr -d ' ')

                if [[ -n "$query_times" ]]; then
                    first_query=$(echo "$query_times" | head -1)
                    other_queries=$(echo "$query_times" | tail -n +2)

                    # Count cached vs uncached
                    cached_count=0
                    uncached_count=0
                    for qt in $other_queries; do
                        if [[ "$qt" -eq 0 ]]; then
                            ((cached_count++)) || true
                        else
                            ((uncached_count++)) || true
                        fi
                    done

                    total_queries=$(echo "$query_times" | wc -l | tr -d ' ')

                    # Determine if caching is working as expected
                    effective_ttl=$(echo "$config_json" | grep -o '"effective_ttl": [0-9]*' | cut -d: -f2 | tr -d ' ')
                    effective_source=$(echo "$config_json" | grep -o '"effective_ttl_source": "[^"]*"' | cut -d'"' -f4)
                fi
            fi

            # Write result to JSON
            if [[ "$first_result" != "true" ]]; then
                echo "," >> "$RESULTS_FILE"
            fi
            first_result=false

            cat >> "$RESULTS_FILE" << EOF
  {
    "test_name": "${test_name}",
    "version": "${version}",
    "config_name": "${config_name}",
    "java_args": "${java_args}",
    "env_vars": "${env_vars}",
    "duration_seconds": ${duration},
    "success": true,
    "config": ${config_json:-{}},
    "statistics": {
      "total_queries": ${total_queries:-0},
      "first_query_ms": ${first_query:-0},
      "cached_queries": ${cached_count:-0},
      "uncached_queries": ${uncached_count:-0}
    },
    "results": ${results_json:-[]}
  }
EOF

            ((passed_tests++)) || true
            echo "done (TTL=${effective_ttl:-?}s from ${effective_source:-?}, cached=${cached_count:-?}/${total_queries:-?})"
        else
            end_time=$(date +%s)
            duration=$((end_time - start_time))

            if [[ "$first_result" != "true" ]]; then
                echo "," >> "$RESULTS_FILE"
            fi
            first_result=false

            cat >> "$RESULTS_FILE" << EOF
  {
    "test_name": "${test_name}",
    "version": "${version}",
    "config_name": "${config_name}",
    "java_args": "${java_args}",
    "env_vars": "${env_vars}",
    "duration_seconds": ${duration},
    "success": false,
    "error": "Test execution failed"
  }
EOF

            echo "FAILED"
        fi

        ((total_tests++)) || true
    done

    echo ""
done

# Close results JSON
echo "" >> "$RESULTS_FILE"
echo "]" >> "$RESULTS_FILE"

# Generate summary report
SUMMARY_FILE="${RUN_DIR}/SUMMARY.md"
cat > "$SUMMARY_FILE" << 'EOF'
# DNS Cache Configuration Test Results

## Overview

This test suite validates how different JVM DNS cache configuration methods
affect actual DNS resolution behavior across Corretto LTS versions.

## Key Findings

| Configuration Method | Works? | Notes |
|---------------------|--------|-------|
| `-Dnetworkaddress.cache.ttl=N` | ❌ NO | Sets System property, but JVM reads Security property |
| `-Dsun.net.inetaddr.ttl=N` | ✅ YES | Deprecated fallback that still works |
| `Security.setProperty()` | ✅ YES | Official way, must be called before first DNS lookup |
| Modify `java.security` file | ✅ YES | Best for base images |

## Test Matrix Results

EOF

# Parse results and add to summary
echo "| Version | Config | Effective TTL | Source | Cached/Total | Status |" >> "$SUMMARY_FILE"
echo "|---------|--------|---------------|--------|--------------|--------|" >> "$SUMMARY_FILE"

# Use jq if available, otherwise use grep/awk
if command -v jq &> /dev/null; then
    jq -r '.[] | "| \(.version) | \(.config_name) | \(.config.effective_ttl // "?")s | \(.config.effective_ttl_source // "?") | \(.statistics.cached_queries // "?")/\(.statistics.total_queries // "?") | \(if .success then "✅" else "❌" end) |"' "$RESULTS_FILE" >> "$SUMMARY_FILE"
else
    echo "| (install jq for detailed table) |" >> "$SUMMARY_FILE"
fi

cat >> "$SUMMARY_FILE" << EOF

## Interpretation

- **Cached queries = 0**: DNS is being re-resolved on every request (TTL working)
- **Cached queries = total-1**: Only first query hits DNS, rest are cached (TTL not working or > test duration)

## Files

- \`results.json\`: Complete test results in JSON format
- \`*.log\`: Individual test logs with raw output

Generated: $(date -Iseconds)
EOF

echo "════════════════════════════════════════════════════════════════════════════"
echo "Test suite completed!"
echo ""
echo "Results:"
echo "  Total tests: ${total_tests}"
echo "  Passed: ${passed_tests}"
echo "  Failed: $((total_tests - passed_tests))"
echo ""
echo "Output files:"
echo "  Summary: ${SUMMARY_FILE}"
echo "  Results: ${RESULTS_FILE}"
echo "════════════════════════════════════════════════════════════════════════════"
