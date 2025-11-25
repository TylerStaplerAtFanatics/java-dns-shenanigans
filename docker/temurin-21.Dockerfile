# Eclipse Temurin 21 - Alternative distribution for comparison
FROM eclipse-temurin:21

# Install tcpdump for DNS packet capture
RUN apt-get update && apt-get install -y tcpdump iproute2 dnsutils && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy all Java source files
COPY src/main/java/io/fbg/dns/*.java /app/io/fbg/dns/

# Compile the test harness
RUN javac io/fbg/dns/*.java

# Default command - can be overridden
ENTRYPOINT ["java", "-cp", "/app"]
CMD ["io.fbg.dns.DnsTestHarness"]
