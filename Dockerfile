# ==============================
# Stage 1 - Builder
# ==============================
FROM jtl-tkgiharbor.hq.bni.co.id/wss-dev/ubi9/openjdk-21:1.22-1 AS builder

ARG DEBIAN_FRONTEND=noninteractive

USER root
WORKDIR /app

# Copy settings.xml for Nexus mirror
RUN mkdir -p /root/.m2
COPY settings.xml /root/.m2/settings.xml

# Copy only pom.xml first (for better caching)
COPY pom.xml .

# Download dependencies (will use Nexus mirror)
RUN mvn -s /root/.m2/settings.xml \
    -Dmaven.test.skip=true \
    dependency:go-offline

# Copy full source code
COPY src ./src

# Build application
RUN mvn -s /root/.m2/settings.xml \
    -Dspring-boot.run.arguments=--server.port=8080 \
    -Dmaven.test.skip=true \
    clean package

# ==============================
# Stage 2 - Runtime
# ==============================
# FROM jtl-tkgiharbor.hq.bni.co.id/wss-dev/ubi9/openjdk-21-runtime
FROM nexus-dev.bni.co.id/docker-wdl-cts/ubi8/openjdk-21-runtime:latest

ENV DEBIAN_FRONTEND=noninteractive

USER root
WORKDIR /app

COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java","-jar","app.jar"]
