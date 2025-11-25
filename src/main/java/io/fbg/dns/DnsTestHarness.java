package io.fbg.dns;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.Security;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

/**
 * DNS Resolution Test Harness
 *
 * Tests how JVM DNS caching behaves under different configurations:
 * - System property: -Dnetworkaddress.cache.ttl
 * - System property: -Dnetworkaddress.cache.negative.ttl
 * - Security property: networkaddress.cache.ttl (via java.security or Security.setProperty)
 * - Security property: networkaddress.cache.negative.ttl
 *
 * Usage:
 *   java DnsTestHarness <hostname> [interval_seconds] [duration_seconds]
 *
 * Environment variables:
 *   DNS_SET_SECURITY_PROPERTY=true  - Set security properties programmatically
 *   DNS_CACHE_TTL=<seconds>         - Value to set for cache.ttl
 *   DNS_CACHE_NEGATIVE_TTL=<seconds> - Value to set for cache.negative.ttl
 */
public class DnsTestHarness {

    private static final String DEFAULT_HOSTNAME = "kubernetes.default.svc.cluster.local";
    private static final int DEFAULT_INTERVAL_SECONDS = 5;
    private static final int DEFAULT_DURATION_SECONDS = 120;

    public static void main(String[] args) {
        String hostname = args.length > 0 ? args[0] : getEnvOrDefault("DNS_TARGET_HOST", DEFAULT_HOSTNAME);
        int intervalSeconds = args.length > 1 ? Integer.parseInt(args[1]) :
            Integer.parseInt(getEnvOrDefault("DNS_INTERVAL_SECONDS", String.valueOf(DEFAULT_INTERVAL_SECONDS)));
        int durationSeconds = args.length > 2 ? Integer.parseInt(args[2]) :
            Integer.parseInt(getEnvOrDefault("DNS_DURATION_SECONDS", String.valueOf(DEFAULT_DURATION_SECONDS)));

        printBanner();

        // Capture and display DNS configuration BEFORE any modifications
        System.out.println("Configuration BEFORE any programmatic changes:");
        System.out.println("-".repeat(60));
        DnsCacheConfig configBefore = new DnsCacheConfig();
        System.out.println(configBefore);

        // Optionally set security properties programmatically
        maybeSetSecurityProperties();

        // Capture and display DNS configuration AFTER modifications
        System.out.println("\nConfiguration AFTER programmatic changes (if any):");
        System.out.println("-".repeat(60));
        DnsCacheConfig configAfter = new DnsCacheConfig();
        System.out.println(configAfter);

        System.out.println("\n" + "=".repeat(80));
        System.out.println("Starting DNS resolution test");
        System.out.println("Target: " + hostname);
        System.out.println("Interval: " + intervalSeconds + "s");
        System.out.println("Duration: " + durationSeconds + "s");
        System.out.println("=".repeat(80) + "\n");

        runDnsTest(hostname, intervalSeconds, durationSeconds);
    }

    private static void printBanner() {
        System.out.println("""
            ╔════════════════════════════════════════════════════════════════════════════╗
            ║                     JVM DNS Resolution Test Harness                        ║
            ║                                                                            ║
            ║  Purpose: Test how different DNS cache configurations affect resolution   ║
            ╚════════════════════════════════════════════════════════════════════════════╝
            """);
    }

    private static void maybeSetSecurityProperties() {
        String setSecProp = System.getenv("DNS_SET_SECURITY_PROPERTY");
        if (!"true".equalsIgnoreCase(setSecProp)) {
            return;
        }

        System.out.println("Setting security properties programmatically...");

        String ttl = System.getenv("DNS_CACHE_TTL");
        String negTtl = System.getenv("DNS_CACHE_NEGATIVE_TTL");

        if (ttl != null) {
            System.out.println("  Setting networkaddress.cache.ttl = " + ttl);
            Security.setProperty("networkaddress.cache.ttl", ttl);
        }

        if (negTtl != null) {
            System.out.println("  Setting networkaddress.cache.negative.ttl = " + negTtl);
            Security.setProperty("networkaddress.cache.negative.ttl", negTtl);
        }

        System.out.println();
    }

    private static void runDnsTest(String hostname, int intervalSeconds, int durationSeconds) {
        Instant startTime = Instant.now();
        Instant endTime = startTime.plus(Duration.ofSeconds(durationSeconds));

        int iteration = 0;
        String lastAddresses = "";

        while (Instant.now().isBefore(endTime)) {
            iteration++;
            Instant queryStart = Instant.now();

            try {
                InetAddress[] addresses = InetAddress.getAllByName(hostname);
                Instant queryEnd = Instant.now();
                long queryDurationMs = Duration.between(queryStart, queryEnd).toMillis();

                String currentAddresses = Arrays.toString(
                    Arrays.stream(addresses)
                        .map(InetAddress::getHostAddress)
                        .toArray(String[]::new)
                );

                boolean addressesChanged = !currentAddresses.equals(lastAddresses);

                System.out.printf("[%s] Iteration %d: %s (query took %dms)%s%n",
                    Instant.now(),
                    iteration,
                    currentAddresses,
                    queryDurationMs,
                    addressesChanged && iteration > 1 ? " [ADDRESSES CHANGED]" : ""
                );

                lastAddresses = currentAddresses;

            } catch (UnknownHostException e) {
                Instant queryEnd = Instant.now();
                long queryDurationMs = Duration.between(queryStart, queryEnd).toMillis();

                System.out.printf("[%s] Iteration %d: FAILED - %s (query took %dms)%n",
                    Instant.now(),
                    iteration,
                    e.getMessage(),
                    queryDurationMs
                );
            }

            try {
                Thread.sleep(intervalSeconds * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("Test interrupted");
                break;
            }
        }

        System.out.println("\n" + "=".repeat(80));
        System.out.println("Test completed. Total iterations: " + iteration);
        System.out.println("=".repeat(80));
    }

    private static String getEnvOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value != null ? value : defaultValue;
    }
}
