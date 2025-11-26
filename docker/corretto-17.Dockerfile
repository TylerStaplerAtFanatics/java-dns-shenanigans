# Amazon Corretto 17 - LTS
FROM amazoncorretto:17

# Install tcpdump for DNS packet capture
RUN yum install -y tcpdump iproute bind-utils && yum clean all

WORKDIR /app

# Copy all Java source files
COPY src/main/java/io/fbg/dns/*.java /app/io/fbg/dns/

# Compile the test harness
RUN javac io/fbg/dns/*.java

# Default command - can be overridden
ENTRYPOINT ["java", "-cp", "/app"]
CMD ["io.fbg.dns.DnsTestHarnessJava8"]
