# Amazon Corretto 11 - LTS
FROM amazoncorretto:11

# Install tcpdump for DNS packet capture
RUN yum install -y tcpdump iproute bind-utils && yum clean all

WORKDIR /app

# Copy all Java source files
COPY src/main/java/io/fbg/dns/*.java /app/io/fbg/dns/

# Compile the test harness (excluding Java 21+ features)
RUN javac io/fbg/dns/DnsTestHarnessJava8.java

# Default command - can be overridden
ENTRYPOINT ["java", "-cp", "/app"]
CMD ["io.fbg.dns.DnsTestHarnessJava8"]
