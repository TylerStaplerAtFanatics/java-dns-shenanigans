.PHONY: help build test test-quick test-local test-docker clean docker-build run

# Default target
help:
	@echo "DNS Cache Configuration Test Harness"
	@echo ""
	@echo "Usage:"
	@echo "  make build          - Build the Java project"
	@echo "  make test           - Run full test matrix (local)"
	@echo "  make test-quick     - Run quick test matrix (shorter duration)"
	@echo "  make test-local     - Run tests in local JVM only"
	@echo "  make test-docker    - Run tests in Docker containers"
	@echo "  make docker-build   - Build all Docker images"
	@echo "  make run            - Run simple DNS test (for debugging)"
	@echo "  make clean          - Clean build artifacts"
	@echo ""
	@echo "Environment Variables:"
	@echo "  DNS_TARGET_HOST     - Hostname to resolve (default: www.google.com)"
	@echo "  DNS_TTL             - TTL value to test (default: 5)"
	@echo ""
	@echo "Examples:"
	@echo "  make run DNS_TARGET_HOST=kubernetes.default.svc.cluster.local"
	@echo "  make test-quick"
	@echo "  make test-docker"

# Build the project
build:
	./gradlew build -x test

# Run full test matrix
test: build
	./gradlew runTestMatrix

# Run quick test matrix
test-quick: build
	./gradlew runQuickTestMatrix

# Run local tests only
test-local: build
	./gradlew runTestMatrix --args="--local"

# Run Docker tests only
test-docker: docker-build
	./gradlew runTestMatrix --args="--docker"

# Build all Docker images
docker-build: build
	docker build -t dns-test:corretto-21 -f docker/corretto-21.Dockerfile .
	docker build -t dns-test:corretto-25 -f docker/corretto-25.Dockerfile .
	docker build -t dns-test:temurin-21 -f docker/temurin-21.Dockerfile .
	docker build -t dns-test:temurin-25 -f docker/temurin-25.Dockerfile .

# Run simple DNS test (good for debugging)
run: build
	./gradlew runDnsTest --args="$(DNS_TARGET_HOST) 5 30"

# Run with system property
run-system-property: build
	./gradlew runDnsTest -Ddns.jvmArgs="-Dnetworkaddress.cache.ttl=$(DNS_TTL)"

# Run with Security.setProperty
run-security-property: build
	DNS_SET_SECURITY_PROPERTY=true DNS_CACHE_TTL=$(DNS_TTL) ./gradlew runDnsTest

# Clean build artifacts
clean:
	./gradlew clean
	rm -rf results/

# === Docker-based testing ===

# Run a specific configuration in Docker
# Usage: make docker-run DIST=corretto-21 METHOD=system-property TTL=5
docker-run:
	@DIST=$${DIST:-corretto-21}; \
	METHOD=$${METHOD:-no-config}; \
	TTL=$${TTL:-30}; \
	HOST=$${DNS_TARGET_HOST:-host.docker.internal}; \
	./scripts/run-single-test.sh --dist $$DIST --method $$METHOD --ttl $$TTL --target-host $$HOST

# Run with tcpdump capture
docker-capture:
	@DIST=$${DIST:-corretto-21}; \
	METHOD=$${METHOD:-system-property}; \
	TTL=$${TTL:-5}; \
	HOST=$${DNS_TARGET_HOST:-host.docker.internal}; \
	./scripts/capture-dns-traffic.sh --dist $$DIST --method $$METHOD --ttl $$TTL --target-host $$HOST

# Quick verification tests
verify-system-property: docker-build
	@echo "Testing -Dnetworkaddress.cache.ttl=5 on Corretto 21..."
	docker run --rm \
		-e DNS_TARGET_HOST=host.docker.internal \
		-e DNS_INTERVAL_SECONDS=5 \
		-e DNS_DURATION_SECONDS=30 \
		dns-test:corretto-21 \
		-Dnetworkaddress.cache.ttl=5 \
		DnsTestHarness

verify-security-setproperty: docker-build
	@echo "Testing Security.setProperty with ttl=5 on Corretto 21..."
	docker run --rm \
		-e DNS_TARGET_HOST=host.docker.internal \
		-e DNS_INTERVAL_SECONDS=5 \
		-e DNS_DURATION_SECONDS=30 \
		-e DNS_SET_SECURITY_PROPERTY=true \
		-e DNS_CACHE_TTL=5 \
		dns-test:corretto-21 \
		DnsTestHarness

# Default values
DNS_TARGET_HOST ?= www.google.com
DNS_TTL ?= 5
