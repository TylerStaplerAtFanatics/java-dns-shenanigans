package io.fbg.dns;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.Security;

/**
 * Inspects the actual InetAddressCachePolicy to see what TTL values the JVM is using.
 *
 * The InetAddressCachePolicy class reads the TTL from:
 * 1. Security.getProperty("networkaddress.cache.ttl")
 * 2. If null, System.getProperty("sun.net.inetaddr.ttl")
 *
 * BUT - these values are cached at class initialization time!
 * So if Security.setProperty is called AFTER InetAddress is first used,
 * it may not take effect.
 */
public class InetAddressCachePolicyInspector {

    public static void main(String[] args) throws Exception {
        System.out.println("InetAddressCachePolicy Inspector");
        System.out.println("=".repeat(60));
        System.out.println();

        // Print what we can read directly
        System.out.println("Direct property access:");
        System.out.println("  Security.getProperty(networkaddress.cache.ttl): " +
            Security.getProperty("networkaddress.cache.ttl"));
        System.out.println("  Security.getProperty(networkaddress.cache.negative.ttl): " +
            Security.getProperty("networkaddress.cache.negative.ttl"));
        System.out.println("  System.getProperty(networkaddress.cache.ttl): " +
            System.getProperty("networkaddress.cache.ttl"));
        System.out.println("  System.getProperty(sun.net.inetaddr.ttl): " +
            System.getProperty("sun.net.inetaddr.ttl"));
        System.out.println();

        // Try to access InetAddressCachePolicy via reflection
        try {
            Class<?> policyClass = Class.forName("sun.net.InetAddressCachePolicy");

            // Try to get the cached TTL value
            Method getMethod = policyClass.getMethod("get");
            Method getNegativeMethod = policyClass.getMethod("getNegative");

            int ttl = (int) getMethod.invoke(null);
            int negativeTtl = (int) getNegativeMethod.invoke(null);

            System.out.println("InetAddressCachePolicy (via reflection):");
            System.out.println("  get() = " + ttl + interpretTtl(ttl));
            System.out.println("  getNegative() = " + negativeTtl + interpretTtl(negativeTtl));
            System.out.println();

            // Try to read the internal fields
            try {
                Field cachePolicy = policyClass.getDeclaredField("cachePolicy");
                cachePolicy.setAccessible(true);
                System.out.println("  Internal cachePolicy field: " + cachePolicy.get(null));
            } catch (Exception e) {
                System.out.println("  Could not read internal cachePolicy field: " + e.getMessage());
            }

            try {
                Field negativeCachePolicy = policyClass.getDeclaredField("negativeCachePolicy");
                negativeCachePolicy.setAccessible(true);
                System.out.println("  Internal negativeCachePolicy field: " + negativeCachePolicy.get(null));
            } catch (Exception e) {
                System.out.println("  Could not read internal negativeCachePolicy field: " + e.getMessage());
            }

        } catch (ClassNotFoundException e) {
            System.out.println("Could not find InetAddressCachePolicy class");
            System.out.println("This might be due to module restrictions in Java 9+");
        } catch (Exception e) {
            System.out.println("Error accessing InetAddressCachePolicy: " + e);
            e.printStackTrace();
        }

        System.out.println();
        System.out.println("=".repeat(60));
        System.out.println("Note: InetAddressCachePolicy caches TTL at class load time.");
        System.out.println("If Security.setProperty is called AFTER any DNS lookup,");
        System.out.println("the new value may not take effect!");
    }

    private static String interpretTtl(int ttl) {
        if (ttl == -1) {
            return " (cache forever)";
        } else if (ttl == 0) {
            return " (no caching)";
        } else {
            return " seconds";
        }
    }
}
