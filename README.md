# Java DNS Cache Configuration Test Harness

Test harness to investigate JVM DNS caching behavior across different configuration methods and Java versions.

## TL;DR

**`-Dnetworkaddress.cache.ttl` does NOT work.** It has never worked across any Java version (8-25).

The JVM reads `networkaddress.cache.ttl` as a **Security property**, not a System property. The `-D` flag sets the wrong property type.

## Test Results

Tested across **all Amazon Corretto LTS versions (8, 11, 17, 21, 25)**:

| Configuration Method | Works? | Notes |
|---------------------|--------|-------|
| `-Dnetworkaddress.cache.ttl=N` | ❌ NO | Sets System property, but JVM reads Security property |
| `-Dsun.net.inetaddr.ttl=N` | ✅ YES | Deprecated fallback that still works |
| `Security.setProperty()` | ✅ YES | Official way, must be called before first DNS lookup |
| Modify `java.security` file | ✅ YES | Best for base images |

### Detailed Results

With TTL=1s configured, we measured how many DNS queries were served from cache:

| Version | `-Dnetworkaddress.cache.ttl=1` | `-Dsun.net.inetaddr.ttl=1` | `Security.setProperty(ttl=1)` |
|---------|-------------------------------|---------------------------|------------------------------|
| Corretto 8 | 3/5 cached ❌ (ignored) | 0/5 cached ✅ | 0/5 cached ✅ |
| Corretto 11 | 4/5 cached ❌ (ignored) | 0/5 cached ✅ | 0/5 cached ✅ |
| Corretto 17 | 3/5 cached ❌ (ignored) | 0/5 cached ✅ | 0/5 cached ✅ |
| Corretto 21 | 3/5 cached ❌ (ignored) | 0/5 cached ✅ | 0/5 cached ✅ |
| Corretto 25 | 3/5 cached ❌ (ignored) | 0/5 cached ✅ | 0/5 cached ✅ |

**0/5 cached = DNS is being re-resolved (TTL working)**
**3-4/5 cached = DNS is cached for 30s (TTL ignored)**

## Root Cause

The JVM's `InetAddressCachePolicy` reads TTL values in this order:

1. `Security.getProperty("networkaddress.cache.ttl")` — **NOT** `System.getProperty()`!
2. `System.getProperty("sun.net.inetaddr.ttl")` — deprecated fallback
3. Default: 30 seconds (without SecurityManager)

```java
// What -Dnetworkaddress.cache.ttl=5 does:
System.getProperty("networkaddress.cache.ttl")  // Returns "5"
Security.getProperty("networkaddress.cache.ttl") // Returns null ← JVM reads THIS

// What actually works:
Security.setProperty("networkaddress.cache.ttl", "5") // ✅
// or
-Dsun.net.inetaddr.ttl=5  // ✅ (deprecated but works)
```

## Recommendations

For Kubernetes/container environments:

| Approach | Pros | Cons |
|----------|------|------|
| Modify `java.security` in base image | Works before any code runs | Requires image rebuild |
| `-Dsun.net.inetaddr.ttl=N` | Simple JVM flag | Deprecated (may be removed) |
| `Security.setProperty()` | Official API | Must call before first DNS lookup |

### Example: Modify java.security in Dockerfile

```dockerfile
FROM amazoncorretto:21

# Configure DNS cache TTL for Kubernetes
RUN JAVA_SECURITY=$(find $JAVA_HOME -name "java.security" | head -1) && \
    echo "networkaddress.cache.ttl=1" >> "$JAVA_SECURITY" && \
    echo "networkaddress.cache.negative.ttl=0" >> "$JAVA_SECURITY"
```

### Example: Security.setProperty() in code

```java
// Must be called BEFORE any DNS lookups
public class Main {
    static {
        java.security.Security.setProperty("networkaddress.cache.ttl", "1");
        java.security.Security.setProperty("networkaddress.cache.negative.ttl", "0");
    }

    public static void main(String[] args) {
        // Now DNS lookups will use TTL=1s
    }
}
```

## Running the Tests

### Prerequisites
- Docker
- Java 21+ (for building locally)
- Bash

### Quick Test
```bash
./scripts/run-comprehensive-tests.sh --quick
```

### Full Test Matrix
```bash
./scripts/run-comprehensive-tests.sh
```

### Single Configuration Test
```bash
# Build the image
docker build -t dns-test:corretto-21 -f docker/corretto-21.Dockerfile .

# Test with -D flag (doesn't work)
docker run --rm dns-test:corretto-21 \
  -Dnetworkaddress.cache.ttl=1 \
  io.fbg.dns.DnsTestHarnessJava8 www.google.com 2 15

# Test with Security.setProperty (works)
docker run --rm \
  -e DNS_SET_SECURITY_PROPERTY=true \
  -e DNS_CACHE_TTL=1 \
  dns-test:corretto-21 \
  io.fbg.dns.DnsTestHarnessJava8 www.google.com 2 15
```

## Project Structure

```
├── README.md                 # This file
├── RESULTS.md               # Detailed test results
├── build.gradle.kts         # Gradle build file
├── docker/
│   ├── corretto-8.Dockerfile
│   ├── corretto-11.Dockerfile
│   ├── corretto-17.Dockerfile
│   ├── corretto-21.Dockerfile
│   └── corretto-25.Dockerfile
├── scripts/
│   └── run-comprehensive-tests.sh
└── src/main/java/io/fbg/dns/
    ├── DnsTestHarness.java          # Main test harness (Java 21+)
    ├── DnsTestHarnessJava8.java     # Java 8 compatible version
    ├── DnsCacheConfig.java          # Configuration snapshot
    └── InetAddressCachePolicyInspector.java  # Inspects actual JVM TTL
```

## License

MIT
