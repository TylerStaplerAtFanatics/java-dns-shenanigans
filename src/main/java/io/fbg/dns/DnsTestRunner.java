package io.fbg.dns;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/**
 * Java-based DNS Test Matrix Runner
 *
 * Runs the DNS test harness across different configurations to determine
 * how various methods of setting DNS cache TTL actually affect behavior.
 *
 * Configuration methods tested:
 * 1. System property (-Dnetworkaddress.cache.ttl) - Common but may not work!
 * 2. Security property (Security.setProperty) - The official way
 * 3. java.security file modification - Works but requires file changes
 * 4. No configuration - Baseline to see defaults
 *
 * Usage:
 *   java DnsTestRunner [--quick] [--local] [--docker] [--output-dir <dir>]
 */
public class DnsTestRunner {

    // Test configuration
    private static final int[] TTL_VALUES = {0, 1, 5, 30};
    private static final int QUICK_DURATION_SECONDS = 30;
    private static final int FULL_DURATION_SECONDS = 120;
    private static final int INTERVAL_SECONDS = 5;

    private final boolean quickMode;
    private final boolean localMode;
    private final boolean dockerMode;
    private final Path outputDir;
    private final String targetHost;

    public static void main(String[] args) throws Exception {
        DnsTestRunner runner = parseArgs(args);
        runner.run();
    }

    private static DnsTestRunner parseArgs(String[] args) {
        boolean quick = false;
        boolean local = true;  // Default to local mode
        boolean docker = false;
        Path outputDir = Paths.get("results");
        String targetHost = "www.google.com";  // Default for local testing

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--quick" -> quick = true;
                case "--local" -> { local = true; docker = false; }
                case "--docker" -> { docker = true; local = false; }
                case "--both" -> { local = true; docker = true; }
                case "--output-dir" -> outputDir = Paths.get(args[++i]);
                case "--target-host" -> targetHost = args[++i];
                case "--help" -> {
                    printUsage();
                    System.exit(0);
                }
            }
        }

        return new DnsTestRunner(quick, local, docker, outputDir, targetHost);
    }

    private static void printUsage() {
        System.out.println("""
            DNS Test Matrix Runner

            Usage: java DnsTestRunner [options]

            Options:
              --quick         Run shorter tests (30s instead of 120s)
              --local         Run tests in the local JVM (default)
              --docker        Run tests in Docker containers (requires Docker)
              --both          Run both local and Docker tests
              --output-dir    Directory for test results (default: ./results)
              --target-host   DNS hostname to resolve (default: www.google.com)
              --help          Show this help message

            Configuration methods tested:
              1. System property (-Dnetworkaddress.cache.ttl)
              2. Security.setProperty() at runtime
              3. No configuration (baseline)

            TTL values tested: 0, 1, 5, 30 seconds
            """);
    }

    public DnsTestRunner(boolean quickMode, boolean localMode, boolean dockerMode,
                         Path outputDir, String targetHost) {
        this.quickMode = quickMode;
        this.localMode = localMode;
        this.dockerMode = dockerMode;
        this.outputDir = outputDir;
        this.targetHost = targetHost;
    }

    public void run() throws Exception {
        printBanner();

        // Create timestamped output directory
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path runDir = outputDir.resolve(timestamp);
        Files.createDirectories(runDir);

        System.out.println("Configuration:");
        System.out.println("  Output directory: " + runDir.toAbsolutePath());
        System.out.println("  Target host: " + targetHost);
        System.out.println("  Duration: " + (quickMode ? QUICK_DURATION_SECONDS : FULL_DURATION_SECONDS) + "s");
        System.out.println("  Quick mode: " + quickMode);
        System.out.println("  Local tests: " + localMode);
        System.out.println("  Docker tests: " + dockerMode);
        System.out.println();

        List<TestResult> results = new ArrayList<>();

        if (localMode) {
            System.out.println("Running local tests...");
            System.out.println("=".repeat(60));
            results.addAll(runLocalTests(runDir));
        }

        if (dockerMode) {
            System.out.println("\nRunning Docker tests...");
            System.out.println("=".repeat(60));
            results.addAll(runDockerTests(runDir));
        }

        // Generate summary report
        generateReport(runDir, results);

        System.out.println("\n" + "=".repeat(60));
        System.out.println("Test matrix completed!");
        System.out.println("Results: " + runDir.toAbsolutePath());
        System.out.println("=".repeat(60));
    }

    private List<TestResult> runLocalTests(Path runDir) throws Exception {
        List<TestResult> results = new ArrayList<>();
        int duration = quickMode ? QUICK_DURATION_SECONDS : FULL_DURATION_SECONDS;

        // Test 1: Baseline - no configuration
        System.out.println("\n[1/4] Testing baseline (no configuration)...");
        results.add(runLocalTest(runDir, "baseline", Map.of(), List.of(), duration));

        // Test 2: System property (-D flag) - This is what people commonly try
        for (int ttl : TTL_VALUES) {
            System.out.println("\n[2/4] Testing system property -Dnetworkaddress.cache.ttl=" + ttl + "...");
            results.add(runLocalTest(runDir, "system-property-ttl" + ttl,
                Map.of(),
                List.of("-Dnetworkaddress.cache.ttl=" + ttl,
                        "-Dnetworkaddress.cache.negative.ttl=" + ttl),
                duration));
        }

        // Test 3: Security.setProperty() at runtime
        for (int ttl : TTL_VALUES) {
            System.out.println("\n[3/4] Testing Security.setProperty() with ttl=" + ttl + "...");
            results.add(runLocalTest(runDir, "security-setproperty-ttl" + ttl,
                Map.of("DNS_SET_SECURITY_PROPERTY", "true",
                       "DNS_CACHE_TTL", String.valueOf(ttl),
                       "DNS_CACHE_NEGATIVE_TTL", String.valueOf(ttl)),
                List.of(),
                duration));
        }

        // Test 4: Deprecated sun.net.inetaddr.ttl property
        for (int ttl : TTL_VALUES) {
            System.out.println("\n[4/4] Testing deprecated sun.net.inetaddr.ttl=" + ttl + "...");
            results.add(runLocalTest(runDir, "sun-net-ttl" + ttl,
                Map.of(),
                List.of("-Dsun.net.inetaddr.ttl=" + ttl,
                        "-Dsun.net.inetaddr.negative.ttl=" + ttl),
                duration));
        }

        return results;
    }

    private TestResult runLocalTest(Path runDir, String testName, Map<String, String> env,
                                    List<String> jvmArgs, int durationSeconds) throws Exception {
        Path logFile = runDir.resolve(testName + ".log");
        Instant startTime = Instant.now();

        // Build command
        List<String> command = new ArrayList<>();
        command.add(ProcessHandle.current().info().command().orElse("java"));
        command.addAll(jvmArgs);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("io.fbg.dns.DnsTestHarness");
        command.add(targetHost);
        command.add(String.valueOf(INTERVAL_SECONDS));
        command.add(String.valueOf(durationSeconds));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        // Set environment variables
        Map<String, String> processEnv = pb.environment();
        processEnv.putAll(env);

        Process process = pb.start();

        // Capture output
        StringBuilder output = new StringBuilder();
        output.append("Test: ").append(testName).append("\n");
        output.append("Command: ").append(String.join(" ", command)).append("\n");
        output.append("Environment: ").append(env).append("\n");
        output.append("Started: ").append(startTime).append("\n");
        output.append("=".repeat(60)).append("\n\n");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                // Print progress indicator
                if (line.contains("Iteration")) {
                    System.out.print(".");
                }
            }
        }

        int exitCode = process.waitFor();
        Instant endTime = Instant.now();

        output.append("\n").append("=".repeat(60)).append("\n");
        output.append("Finished: ").append(endTime).append("\n");
        output.append("Exit code: ").append(exitCode).append("\n");
        output.append("Duration: ").append(Duration.between(startTime, endTime).toSeconds()).append("s\n");

        Files.writeString(logFile, output.toString());
        System.out.println(" done");

        return new TestResult(testName, "local", exitCode == 0,
            Duration.between(startTime, endTime), logFile, output.toString());
    }

    private List<TestResult> runDockerTests(Path runDir) throws Exception {
        List<TestResult> results = new ArrayList<>();

        // Check if Docker is available
        try {
            Process p = new ProcessBuilder("docker", "version").start();
            if (p.waitFor() != 0) {
                System.out.println("Docker not available, skipping Docker tests");
                return results;
            }
        } catch (IOException e) {
            System.out.println("Docker not available, skipping Docker tests");
            return results;
        }

        String[] distributions = {"corretto-21", "corretto-25"};

        for (String dist : distributions) {
            System.out.println("\nBuilding Docker image for " + dist + "...");

            // Build the image
            Path dockerfile = Paths.get("docker", dist + ".Dockerfile");
            if (!Files.exists(dockerfile)) {
                System.out.println("  Dockerfile not found: " + dockerfile + ", skipping");
                continue;
            }

            ProcessBuilder buildPb = new ProcessBuilder(
                "docker", "build", "-t", "dns-test:" + dist,
                "-f", dockerfile.toString(), "."
            );
            buildPb.inheritIO();
            Process buildProcess = buildPb.start();
            if (buildProcess.waitFor() != 0) {
                System.out.println("  Failed to build image, skipping");
                continue;
            }

            // Run tests for this distribution
            int duration = quickMode ? QUICK_DURATION_SECONDS : FULL_DURATION_SECONDS;

            // Baseline
            results.add(runDockerTest(runDir, dist, "baseline", Map.of(), List.of(), duration));

            // System property
            for (int ttl : TTL_VALUES) {
                results.add(runDockerTest(runDir, dist, "system-property-ttl" + ttl,
                    Map.of(),
                    List.of("-Dnetworkaddress.cache.ttl=" + ttl),
                    duration));
            }

            // Security.setProperty
            for (int ttl : TTL_VALUES) {
                results.add(runDockerTest(runDir, dist, "security-setproperty-ttl" + ttl,
                    Map.of("DNS_SET_SECURITY_PROPERTY", "true",
                           "DNS_CACHE_TTL", String.valueOf(ttl)),
                    List.of(),
                    duration));
            }
        }

        return results;
    }

    private TestResult runDockerTest(Path runDir, String distribution, String testName,
                                     Map<String, String> env, List<String> javaArgs,
                                     int durationSeconds) throws Exception {
        String fullTestName = distribution + "_" + testName;
        Path logFile = runDir.resolve(fullTestName + ".log");
        Instant startTime = Instant.now();

        System.out.print("  Testing " + fullTestName + "...");

        List<String> command = new ArrayList<>();
        command.add("docker");
        command.add("run");
        command.add("--rm");

        // Add environment variables
        command.add("-e");
        command.add("DNS_TARGET_HOST=" + targetHost);
        command.add("-e");
        command.add("DNS_INTERVAL_SECONDS=" + INTERVAL_SECONDS);
        command.add("-e");
        command.add("DNS_DURATION_SECONDS=" + durationSeconds);

        for (Map.Entry<String, String> entry : env.entrySet()) {
            command.add("-e");
            command.add(entry.getKey() + "=" + entry.getValue());
        }

        command.add("dns-test:" + distribution);
        command.addAll(javaArgs);
        command.add("DnsTestHarness");

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        output.append("Test: ").append(fullTestName).append("\n");
        output.append("Distribution: ").append(distribution).append("\n");
        output.append("Command: ").append(String.join(" ", command)).append("\n");
        output.append("Started: ").append(startTime).append("\n");
        output.append("=".repeat(60)).append("\n\n");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();
        Instant endTime = Instant.now();

        output.append("\n").append("=".repeat(60)).append("\n");
        output.append("Finished: ").append(endTime).append("\n");
        output.append("Exit code: ").append(exitCode).append("\n");

        Files.writeString(logFile, output.toString());
        System.out.println(" done");

        return new TestResult(fullTestName, distribution, exitCode == 0,
            Duration.between(startTime, endTime), logFile, output.toString());
    }

    private void generateReport(Path runDir, List<TestResult> results) throws Exception {
        Path reportFile = runDir.resolve("REPORT.md");

        StringBuilder report = new StringBuilder();
        report.append("# DNS Cache Configuration Test Results\n\n");
        report.append("Generated: ").append(LocalDateTime.now()).append("\n\n");

        report.append("## Summary\n\n");
        report.append("| Test Name | Distribution | Success | Duration |\n");
        report.append("|-----------|--------------|---------|----------|\n");

        for (TestResult result : results) {
            report.append(String.format("| %s | %s | %s | %ds |\n",
                result.testName(),
                result.distribution(),
                result.success() ? "✅" : "❌",
                result.duration().toSeconds()));
        }

        report.append("\n## Key Findings\n\n");
        report.append("Look at the log files to see:\n");
        report.append("1. Which configuration method actually sets `Security.getProperty(\"networkaddress.cache.ttl\")`\n");
        report.append("2. Whether the effective TTL matches the configured value\n");
        report.append("3. Query timing patterns that indicate actual caching behavior\n");

        report.append("\n## Test Details\n\n");
        report.append("See individual log files for detailed output:\n\n");
        for (TestResult result : results) {
            report.append("- `").append(result.logFile().getFileName()).append("`\n");
        }

        Files.writeString(reportFile, report.toString());
        System.out.println("\nReport generated: " + reportFile);
    }

    private void printBanner() {
        System.out.println("""

            ╔════════════════════════════════════════════════════════════════════════════╗
            ║                     DNS Test Matrix Runner                                  ║
            ║                                                                            ║
            ║  Testing how different configuration methods affect JVM DNS caching        ║
            ╚════════════════════════════════════════════════════════════════════════════╝
            """);
    }

    record TestResult(
        String testName,
        String distribution,
        boolean success,
        Duration duration,
        Path logFile,
        String output
    ) {}
}
