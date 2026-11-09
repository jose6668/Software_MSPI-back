# Official Eclipse Temurin 17 JRE Jammy, manifest pinned for this delivery.
FROM eclipse-temurin:17-jre-jammy@sha256:2a9eb226abb654c9d1f91b53cf638d20cb3abb726d1b18a67909c29515545469
# USN-8996-1: use authenticated HTTPS while updating the affected APT packages.
RUN sed -i -e 's|http://archive.ubuntu.com/ubuntu/|https://archive.ubuntu.com/ubuntu/|g' \
           -e 's|http://security.ubuntu.com/ubuntu/|https://security.ubuntu.com/ubuntu/|g' \
           -e 's|http://ports.ubuntu.com/ubuntu-ports/|https://ports.ubuntu.com/ubuntu-ports/|g' /etc/apt/sources.list \
    && apt-get -o APT::Update::Error-Mode=any update \
    && apt-get install -y --no-install-recommends --only-upgrade apt libapt-pkg6.0 \
    && dpkg --compare-versions "$(dpkg-query -W -f='${Version}' apt)" ge 2.4.15ubuntu0.1 \
    && dpkg --compare-versions "$(dpkg-query -W -f='${Version}' libapt-pkg6.0)" ge 2.4.15ubuntu0.1 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /opt/mspi
COPY mspi/target/mspi-0.0.1-SNAPSHOT.jar /opt/mspi/app.jar
USER 10001:10001
ENTRYPOINT ["java","-Xms128m","-Xmx512m","-XX:MaxMetaspaceSize=192m","-XX:MaxDirectMemorySize=128m","-XX:ActiveProcessorCount=2","-Xss512k","-XX:+ExitOnOutOfMemoryError","-Djava.io.tmpdir=/tmp","-Duser.home=/tmp","-jar","/opt/mspi/app.jar"]
