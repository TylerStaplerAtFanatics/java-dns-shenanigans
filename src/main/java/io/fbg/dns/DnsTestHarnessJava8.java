package io.fbg.dns;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.Security;
import java.util.Arrays;
import java.util.Date;

/**
 * Java 8 compatible DNS Resolution Test Harness
 *
 * Outputs JSON for easy parsing and analysis across versions.
 */
public class DnsTestHarnessJava8 {

    private static final String DEFAULT_HOSTNAME = "www.google.com";
    private static final int DEFAULT_INTERVAL_SECONDS = 2;
    private static final int DEFAULT_DURATION_SECONDS = 15;

    public static void main(String[] args) {
        String hostname = args.length > 0 ? args[0] : getEnvOrDefault("DNS_TARGET_HOST", DEFAULT_HOSTNAME);
        int intervalSeconds = args.length > 1 ? Integer.parseInt(args[1]) :
            Integer.parseInt(getEnvOrDefault("DNS_INTERVAL_SECONDS", String.valueOf(DEFAULT_INTERVAL_SECONDS)));
        int durationSeconds = args.length > 2 ? Integer.parseInt(args[2]) :
            Integer.parseInt(getEnvOrDefault("DNS_DURATION_SECONDS", String.valueOf(DEFAULT_DURATION_SECONDS)));

        // Optionally set security properties programmatically
        maybeSetSecurityProperties();

        // Output configuration as JSON
        printConfigJson();

        // Run DNS test and output results as JSON
        runDnsTest(hostname, intervalSeconds, durationSeconds);
    }

    private static void printConfigJson() {
        String secTtl = Security.getProperty("networkaddress.cache.ttl");
        String secNegTtl = Security.getProperty("networkaddress.cache.negative.ttl");
        String sysTtl = System.getProperty("networkaddress.cache.ttl");
        String sysNegTtl = System.getProperty("networkaddress.cache.negative.ttl");
        String sunTtl = System.getProperty("sun.net.inetaddr.ttl");
        String sunNegTtl = System.getProperty("sun.net.inetaddr.negative.ttl");

        // Calculate effective TTL
        String effectiveTtl;
        String effectiveSource;
        if (secTtl != null && !secTtl.isEmpty()) {
            effectiveTtl = secTtl;
            effectiveSource = "security_property";
        } else if (sunTtl != null && !sunTtl.isEmpty()) {
            effectiveTtl = sunTtl;
            effectiveSource = "sun_net_inetaddr_ttl";
        } else {
            effectiveTtl = "30";
            effectiveSource = "default";
        }

        System.out.println("---CONFIG_START---");
        System.out.println("{");
        System.out.println("  \"java_version\": \"" + System.getProperty("java.version") + "\",");
        System.out.println("  \"java_vendor\": \"" + System.getProperty("java.vendor") + "\",");
        System.out.println("  \"java_runtime_version\": \"" + System.getProperty("java.runtime.version") + "\",");
        System.out.println("  \"security_manager_present\": " + (System.getSecurityManager() != null) + ",");
        System.out.println("  \"properties\": {");
        System.out.println("    \"security_networkaddress_cache_ttl\": " + jsonValue(secTtl) + ",");
        System.out.println("    \"security_networkaddress_cache_negative_ttl\": " + jsonValue(secNegTtl) + ",");
        System.out.println("    \"system_networkaddress_cache_ttl\": " + jsonValue(sysTtl) + ",");
        System.out.println("    \"system_networkaddress_cache_negative_ttl\": " + jsonValue(sysNegTtl) + ",");
        System.out.println("    \"system_sun_net_inetaddr_ttl\": " + jsonValue(sunTtl) + ",");
        System.out.println("    \"system_sun_net_inetaddr_negative_ttl\": " + jsonValue(sunNegTtl));
        System.out.println("  },");
        System.out.println("  \"effective_ttl\": " + effectiveTtl + ",");
        System.out.println("  \"effective_ttl_source\": \"" + effectiveSource + "\"");
        System.out.println("}");
        System.out.println("---CONFIG_END---");
    }

    private static String jsonValue(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static void maybeSetSecurityProperties() {
        String setSecProp = System.getenv("DNS_SET_SECURITY_PROPERTY");
        if (!"true".equalsIgnoreCase(setSecProp)) {
            return;
        }

        String ttl = System.getenv("DNS_CACHE_TTL");
        String negTtl = System.getenv("DNS_CACHE_NEGATIVE_TTL");

        if (ttl != null) {
            Security.setProperty("networkaddress.cache.ttl", ttl);
        }
        if (negTtl != null) {
            Security.setProperty("networkaddress.cache.negative.ttl", negTtl);
        }
    }

    private static void runDnsTest(String hostname, int intervalSeconds, int durationSeconds) {
        long startTime = System.currentTimeMillis();
        long endTime = startTime + (durationSeconds * 1000L);

        System.out.println("---RESULTS_START---");
        System.out.println("[");

        int iteration = 0;
        boolean first = true;

        while (System.currentTimeMillis() < endTime) {
            iteration++;
            long queryStart = System.currentTimeMillis();

            try {
                InetAddress[] addresses = InetAddress.getAllByName(hostname);
                long queryEnd = System.currentTimeMillis();
                long queryDurationMs = queryEnd - queryStart;

                String[] ips = new String[addresses.length];
                for (int i = 0; i < addresses.length; i++) {
                    ips[i] = addresses[i].getHostAddress();
                }

                if (!first) System.out.println(",");
                first = false;
                System.out.print("  {\"iteration\": " + iteration +
                    ", \"timestamp\": " + queryStart +
                    ", \"query_ms\": " + queryDurationMs +
                    ", \"success\": true" +
                    ", \"addresses\": " + Arrays.toString(ips).replace("[", "[\"").replace("]", "\"]").replace(", ", "\", \"") +
                    "}");

            } catch (UnknownHostException e) {
                long queryEnd = System.currentTimeMillis();
                long queryDurationMs = queryEnd - queryStart;

                if (!first) System.out.println(",");
                first = false;
                System.out.print("  {\"iteration\": " + iteration +
                    ", \"timestamp\": " + queryStart +
                    ", \"query_ms\": " + queryDurationMs +
                    ", \"success\": false" +
                    ", \"error\": \"" + e.getMessage() + "\"" +
                    "}");
            }

            try {
                Thread.sleep(intervalSeconds * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        System.out.println();
        System.out.println("]");
        System.out.println("---RESULTS_END---");
    }

    private static String getEnvOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value != null ? value : defaultValue;
    }
}
