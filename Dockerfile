FROM jtl-tkgiharbor.hq.bni.co.id/wss-dev/ubi9/openjdk-21:1.22-1 AS builder
ARG DEBIAN_FRONTEND=noninteractive

USER root
COPY pom.xml /usr/src/app/
WORKDIR /usr/src/app

COPY src /usr/src/app/src
RUN mvn -Dspring-boot.run.profiles=oracle -Dhttps.proxyHost=192.168.98.199 -Dhttps.proxyPort=8080 -Dserver.address=0.0.0.0 -Dmaven.test.skip=true clean package

FROM jtl-tkgiharbor.hq.bni.co.id/wss-dev/ubi9/openjdk-21-runtime:latest
ENV DEBIAN_FRONTEND noninteractive

USER root
COPY --from=builder /usr/src/app/target/*.jar bni-tb-gateway-0.0.1-SNAPSHOT.jar

ENTRYPOINT ["java","-jar","bni-tb-gateway-0.0.1-SNAPSHOT.jar"]
