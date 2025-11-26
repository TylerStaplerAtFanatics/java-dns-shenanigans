# DNS Cache Configuration Test Results

## Overview

This test suite validates how different JVM DNS cache configuration methods
affect actual DNS resolution behavior across Amazon Corretto LTS versions (8, 11, 17, 21, 25).

**Test Date:** 2025-11-25
**Test Duration:** 10s per configuration
**Query Interval:** 2s (5 queries per test)

## Key Findings

| Configuration Method | Works? | Notes |
|---------------------|--------|-------|
| `-Dnetworkaddress.cache.ttl=N` | ❌ NO | Sets System property, but JVM reads Security property |
| `-Dsun.net.inetaddr.ttl=N` | ✅ YES | Deprecated fallback that still works |
| `Security.setProperty()` | ✅ YES | Official way, must be called before first DNS lookup |
| Modify `java.security` file | ✅ YES | Best for base images |

## Test Matrix Results

### Legend
- **Effective TTL**: The actual TTL value the JVM is using
- **Source**: Where the TTL value came from
- **Cached**: Number of queries that returned 0ms (from cache) out of total non-first queries

### Corretto 8 (1.8.0)

| Configuration | Effective TTL | Source | Cached/Total |
|--------------|---------------|--------|--------------|
| baseline | 30s | default | 4/5 |
| `-Dnetworkaddress.cache.ttl=1` | 30s | default | 3/5 ❌ (ignored) |
| `-Dnetworkaddress.cache.ttl=5` | 30s | default | 4/5 ❌ (ignored) |
| `-Dsun.net.inetaddr.ttl=1` | 1s | sun_net_inetaddr_ttl | 0/5 ✅ |
| `-Dsun.net.inetaddr.ttl=5` | 5s | sun_net_inetaddr_ttl | 3/5 ✅ |
| `Security.setProperty(ttl=1)` | 1s | security_property | 0/5 ✅ |
| `Security.setProperty(ttl=5)` | 5s | security_property | 3/5 ✅ |

### Corretto 11 (11.x)

| Configuration | Effective TTL | Source | Cached/Total |
|--------------|---------------|--------|--------------|
| baseline | 30s | default | 4/5 |
| `-Dnetworkaddress.cache.ttl=1` | 30s | default | 4/5 ❌ (ignored) |
| `-Dnetworkaddress.cache.ttl=5` | 30s | default | 4/5 ❌ (ignored) |
| `-Dsun.net.inetaddr.ttl=1` | 1s | sun_net_inetaddr_ttl | 0/5 ✅ |
| `-Dsun.net.inetaddr.ttl=5` | 5s | sun_net_inetaddr_ttl | 1/5 ✅ |
| `Security.setProperty(ttl=1)` | 1s | security_property | 0/5 ✅ |
| `Security.setProperty(ttl=5)` | 5s | security_property | 2/5 ✅ |

### Corretto 17 (17.x)

| Configuration | Effective TTL | Source | Cached/Total |
|--------------|---------------|--------|--------------|
| baseline | 30s | default | 4/5 |
| `-Dnetworkaddress.cache.ttl=1` | 30s | default | 3/5 ❌ (ignored) |
| `-Dnetworkaddress.cache.ttl=5` | 30s | default | 4/5 ❌ (ignored) |
| `-Dsun.net.inetaddr.ttl=1` | 1s | sun_net_inetaddr_ttl | 0/5 ✅ |
| `-Dsun.net.inetaddr.ttl=5` | 5s | sun_net_inetaddr_ttl | 3/5 ✅ |
| `Security.setProperty(ttl=1)` | 1s | security_property | 0/5 ✅ |
| `Security.setProperty(ttl=5)` | 5s | security_property | 3/5 ✅ |

### Corretto 21 (21.x)

| Configuration | Effective TTL | Source | Cached/Total |
|--------------|---------------|--------|--------------|
| baseline | 30s | default | 4/5 |
| `-Dnetworkaddress.cache.ttl=1` | 30s | default | 3/5 ❌ (ignored) |
| `-Dnetworkaddress.cache.ttl=5` | 30s | default | 4/5 ❌ (ignored) |
| `-Dsun.net.inetaddr.ttl=1` | 1s | sun_net_inetaddr_ttl | 0/5 ✅ |
| `-Dsun.net.inetaddr.ttl=5` | 5s | sun_net_inetaddr_ttl | 3/5 ✅ |
| `Security.setProperty(ttl=1)` | 1s | security_property | 0/5 ✅ |
| `Security.setProperty(ttl=5)` | 5s | security_property | 3/5 ✅ |

### Corretto 25 (25.x)

| Configuration | Effective TTL | Source | Cached/Total |
|--------------|---------------|--------|--------------|
| baseline | 30s | default | 3/5 |
| `-Dnetworkaddress.cache.ttl=1` | 30s | default | 3/5 ❌ (ignored) |
| `-Dnetworkaddress.cache.ttl=5` | 30s | default | 4/5 ❌ (ignored) |
| `-Dsun.net.inetaddr.ttl=1` | 1s | sun_net_inetaddr_ttl | 0/5 ✅ |
| `-Dsun.net.inetaddr.ttl=5` | 5s | sun_net_inetaddr_ttl | 3/5 ✅ |
| `Security.setProperty(ttl=1)` | 1s | security_property | 0/5 ✅ |
| `Security.setProperty(ttl=5)` | 5s | security_property | 3/5 ✅ |

## Analysis

### Consistent Across All Versions (8-25)

1. **`-Dnetworkaddress.cache.ttl` DOES NOT WORK**
   - Sets `System.getProperty("networkaddress.cache.ttl")` but JVM ignores it
   - JVM only reads `networkaddress.cache.ttl` as a **Security property**
   - Effective TTL remains at 30s (default)

2. **`-Dsun.net.inetaddr.ttl` WORKS**
   - Deprecated but still functional fallback
   - JVM checks this when Security property is not set

3. **`Security.setProperty()` WORKS**
   - Official method
   - Must be called before first DNS lookup

### Root Cause

The JVM's `InetAddressCachePolicy` reads TTL values in this order:

1. `Security.getProperty("networkaddress.cache.ttl")` - **NOT** `System.getProperty()`!
2. `System.getProperty("sun.net.inetaddr.ttl")` - deprecated fallback
3. Default: 30 seconds (without SecurityManager)

**The `-Dnetworkaddress.cache.ttl` flag sets the wrong property type!**

### Recommendation

For Kubernetes/container environments:

| Approach | Pros | Cons |
|----------|------|------|
| Modify `java.security` in base image | Works before any code runs | Requires image rebuild |
| `-Dsun.net.inetaddr.ttl=N` | Simple JVM flag | Deprecated (may be removed) |
| `Security.setProperty()` | Official API | Must call before first DNS lookup |

## Files

- `results.json` - Complete test results in JSON format
- `*.log` - Individual test logs with raw output
- `build-*.log` - Docker image build logs
