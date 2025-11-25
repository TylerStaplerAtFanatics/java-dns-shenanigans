package io.fbg.dns;

import java.security.Security;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Captures DNS cache configuration from all relevant sources.
 *
 * The JVM has multiple ways to configure DNS caching, and they interact in complex ways:
 *
 * 1. Security Properties (java.security file or Security.setProperty()):
 *    - networkaddress.cache.ttl
 *    - networkaddress.cache.negative.ttl
 *    These are the OFFICIAL way to configure DNS caching.
 *
 * 2. System Properties (-D flags):
 *    - sun.net.inetaddr.ttl (deprecated, but may still work)
 *    - sun.net.inetaddr.negative.ttl (deprecated)
 *    - networkaddress.cache.ttl (NOT officially supported via -D, despite common belief)
 *
 * 3. Security Manager presence affects default behavior:
 *    - With security manager: default TTL is -1 (cache forever)
 *    - Without security manager: default TTL is 30 seconds
 *
 * This class captures all these values to help debug configuration issues.
 */
public class DnsCacheConfig {

    // Security Properties (the official way)
    private final String securityCacheTtl;
    private final String securityCacheNegativeTtl;

    // System Properties (commonly attempted, but may not work)
    private final String systemCacheTtl;
    private final String systemCacheNegativeTtl;
    private final String sunNetTtl;
    private final String sunNetNegativeTtl;

    // Environment
    private final boolean securityManagerPresent;
    private final String javaVersion;
    private final String javaVendor;
    private final String javaRuntimeVersion;

    public DnsCacheConfig() {
        // Capture Security Properties
        this.securityCacheTtl = Security.getProperty("networkaddress.cache.ttl");
        this.securityCacheNegativeTtl = Security.getProperty("networkaddress.cache.negative.ttl");

        // Capture System Properties
        this.systemCacheTtl = System.getProperty("networkaddress.cache.ttl");
        this.systemCacheNegativeTtl = System.getProperty("networkaddress.cache.negative.ttl");
        this.sunNetTtl = System.getProperty("sun.net.inetaddr.ttl");
        this.sunNetNegativeTtl = System.getProperty("sun.net.inetaddr.negative.ttl");

        // Capture environment
        this.securityManagerPresent = System.getSecurityManager() != null;
        this.javaVersion = System.getProperty("java.version");
        this.javaVendor = System.getProperty("java.vendor");
        this.javaRuntimeVersion = System.getProperty("java.runtime.version");
    }

    /**
     * Returns the effective TTL that the JVM should be using.
     * Note: This is our best guess based on documentation, but actual behavior may differ!
     */
    public String getEffectiveTtl() {
        // Security property takes precedence
        if (securityCacheTtl != null && !securityCacheTtl.isEmpty()) {
            return securityCacheTtl + " (from Security.getProperty)";
        }

        // Deprecated sun.net property might work on older JVMs
        if (sunNetTtl != null && !sunNetTtl.isEmpty()) {
            return sunNetTtl + " (from sun.net.inetaddr.ttl - DEPRECATED)";
        }

        // Default depends on security manager
        if (securityManagerPresent) {
            return "-1 (default with SecurityManager - cache forever)";
        } else {
            return "30 (default without SecurityManager)";
        }
    }

    /**
     * Returns the effective negative TTL that the JVM should be using.
     */
    public String getEffectiveNegativeTtl() {
        if (securityCacheNegativeTtl != null && !securityCacheNegativeTtl.isEmpty()) {
            return securityCacheNegativeTtl + " (from Security.getProperty)";
        }

        if (sunNetNegativeTtl != null && !sunNetNegativeTtl.isEmpty()) {
            return sunNetNegativeTtl + " (from sun.net.inetaddr.negative.ttl - DEPRECATED)";
        }

        return "10 (default)";
    }

    /**
     * Checks if -D system property was used (which may NOT work for networkaddress.cache.ttl)
     */
    public boolean isUsingSystemPropertyForCacheTtl() {
        return systemCacheTtl != null && !systemCacheTtl.isEmpty();
    }

    /**
     * Returns a map of all configuration values for structured output.
     */
    public Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>();

        map.put("java.version", javaVersion);
        map.put("java.vendor", javaVendor);
        map.put("java.runtime.version", javaRuntimeVersion);
        map.put("securityManagerPresent", String.valueOf(securityManagerPresent));

        map.put("Security.getProperty(networkaddress.cache.ttl)", valueOrNull(securityCacheTtl));
        map.put("Security.getProperty(networkaddress.cache.negative.ttl)", valueOrNull(securityCacheNegativeTtl));

        map.put("System.getProperty(networkaddress.cache.ttl)", valueOrNull(systemCacheTtl));
        map.put("System.getProperty(networkaddress.cache.negative.ttl)", valueOrNull(systemCacheNegativeTtl));
        map.put("System.getProperty(sun.net.inetaddr.ttl)", valueOrNull(sunNetTtl));
        map.put("System.getProperty(sun.net.inetaddr.negative.ttl)", valueOrNull(sunNetNegativeTtl));

        map.put("effective.ttl", getEffectiveTtl());
        map.put("effective.negative.ttl", getEffectiveNegativeTtl());

        return map;
    }

    private String valueOrNull(String value) {
        return value != null ? value : "<null>";
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("DNS Cache Configuration Snapshot\n");
        sb.append("=".repeat(60)).append("\n\n");

        sb.append("JVM Information:\n");
        sb.append("-".repeat(40)).append("\n");
        sb.append("  java.version: ").append(javaVersion).append("\n");
        sb.append("  java.vendor: ").append(javaVendor).append("\n");
        sb.append("  java.runtime.version: ").append(javaRuntimeVersion).append("\n");
        sb.append("  SecurityManager present: ").append(securityManagerPresent).append("\n");
        sb.append("\n");

        sb.append("Security Properties (java.security / Security.setProperty):\n");
        sb.append("-".repeat(40)).append("\n");
        sb.append("  networkaddress.cache.ttl: ").append(valueOrNull(securityCacheTtl)).append("\n");
        sb.append("  networkaddress.cache.negative.ttl: ").append(valueOrNull(securityCacheNegativeTtl)).append("\n");
        sb.append("\n");

        sb.append("System Properties (-D flags):\n");
        sb.append("-".repeat(40)).append("\n");
        sb.append("  networkaddress.cache.ttl: ").append(valueOrNull(systemCacheTtl));
        if (systemCacheTtl != null) {
            sb.append(" ⚠️ WARNING: May NOT work! Use Security.setProperty instead");
        }
        sb.append("\n");
        sb.append("  networkaddress.cache.negative.ttl: ").append(valueOrNull(systemCacheNegativeTtl)).append("\n");
        sb.append("  sun.net.inetaddr.ttl: ").append(valueOrNull(sunNetTtl));
        if (sunNetTtl != null) {
            sb.append(" (deprecated)");
        }
        sb.append("\n");
        sb.append("  sun.net.inetaddr.negative.ttl: ").append(valueOrNull(sunNetNegativeTtl)).append("\n");
        sb.append("\n");

        sb.append("Effective Values (best guess):\n");
        sb.append("-".repeat(40)).append("\n");
        sb.append("  TTL: ").append(getEffectiveTtl()).append("\n");
        sb.append("  Negative TTL: ").append(getEffectiveNegativeTtl()).append("\n");

        return sb.toString();
    }

    // Getters for programmatic access
    public String getSecurityCacheTtl() { return securityCacheTtl; }
    public String getSecurityCacheNegativeTtl() { return securityCacheNegativeTtl; }
    public String getSystemCacheTtl() { return systemCacheTtl; }
    public String getSystemCacheNegativeTtl() { return systemCacheNegativeTtl; }
    public String getSunNetTtl() { return sunNetTtl; }
    public String getSunNetNegativeTtl() { return sunNetNegativeTtl; }
    public boolean isSecurityManagerPresent() { return securityManagerPresent; }
    public String getJavaVersion() { return javaVersion; }
    public String getJavaVendor() { return javaVendor; }
    public String getJavaRuntimeVersion() { return javaRuntimeVersion; }
}
