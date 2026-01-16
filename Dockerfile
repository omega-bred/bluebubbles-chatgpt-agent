FROM eclipse-temurin:24-jdk AS base

RUN apt-get update \
 && apt-get install -y curl libgfortran5 findutils \
 && curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
 && apt-get install -y nodejs \
 && rm -rf /var/lib/apt/lists/*


FROM base AS app

RUN mkdir /app
WORKDIR /app
ADD . /app
RUN ./gradlew build bootJar -x test


FROM base

COPY --from=app /app/build/libs/bluebubbles-chatgpt-agent-0.0.1-SNAPSHOT.jar /app/bluebubbles-chatgpt-agent.jar
CMD ["java", "--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.nio.charset=ALL-UNNAMED", "-jar", "/app/bluebubbles-chatgpt-agent.jar"]
