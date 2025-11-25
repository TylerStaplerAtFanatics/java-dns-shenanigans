plugins {
    java
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // For running Docker containers programmatically
    implementation("com.github.docker-java:docker-java-core:3.3.6")
    implementation("com.github.docker-java:docker-java-transport-httpclient5:3.3.6")

    // JSON for structured output
    implementation("com.google.code.gson:gson:2.10.1")

    // SLF4J for logging
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("org.slf4j:slf4j-simple:2.0.9")

    // JUnit for test assertions
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
}

application {
    mainClass.set("io.fbg.dns.DnsTestRunner")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

// Task to run the simple DNS test harness (no Docker)
tasks.register<JavaExec>("runDnsTest") {
    group = "dns-tests"
    description = "Run the DNS test harness locally"
    mainClass.set("io.fbg.dns.DnsTestHarness")
    classpath = sourceSets["main"].runtimeClasspath

    // Allow passing JVM args like -Dnetworkaddress.cache.ttl=5
    jvmArgs = System.getProperty("dns.jvmArgs")?.split(" ") ?: listOf()
}

// Task to run the full test matrix
tasks.register<JavaExec>("runTestMatrix") {
    group = "dns-tests"
    description = "Run the full DNS test matrix across configurations"
    mainClass.set("io.fbg.dns.DnsTestRunner")
    classpath = sourceSets["main"].runtimeClasspath
}

// Task to run quick test matrix
tasks.register<JavaExec>("runQuickTestMatrix") {
    group = "dns-tests"
    description = "Run a quick DNS test matrix (shorter duration)"
    mainClass.set("io.fbg.dns.DnsTestRunner")
    classpath = sourceSets["main"].runtimeClasspath
    args = listOf("--quick")
}
