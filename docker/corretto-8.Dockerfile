# Amazon Corretto 8 - Legacy LTS
FROM amazoncorretto:8

# Install tcpdump for DNS packet capture
RUN yum install -y tcpdump iproute bind-utils && yum clean all

WORKDIR /app

# Copy Java 8 compatible source files
COPY src/main/java/io/fbg/dns/DnsTestHarnessJava8.java /app/io/fbg/dns/

# Compile the test harness
RUN javac io/fbg/dns/DnsTestHarnessJava8.java

# Default command - can be overridden
ENTRYPOINT ["java", "-cp", "/app"]
CMD ["io.fbg.dns.DnsTestHarnessJava8"]
