# Official Eclipse Temurin 17 JRE Jammy, manifest pinned for this delivery.
FROM eclipse-temurin:17-jre-jammy@sha256:2a9eb226abb654c9d1f91b53cf638d20cb3abb726d1b18a67909c29515545469
WORKDIR /opt/mspi
COPY mspi/target/mspi-0.0.1-SNAPSHOT.jar /opt/mspi/app.jar
USER 10001:10001
ENTRYPOINT ["java","-Xms128m","-Xmx512m","-XX:MaxMetaspaceSize=192m","-XX:MaxDirectMemorySize=128m","-XX:ActiveProcessorCount=2","-Xss512k","-XX:+ExitOnOutOfMemoryError","-Djava.io.tmpdir=/tmp","-Duser.home=/tmp","-jar","/opt/mspi/app.jar"]
