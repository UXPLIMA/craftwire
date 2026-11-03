# The Craftwire bot runner (Java and Bedrock protocol bots) for docker/compose.yml, from this checkout
# (CRAFTWIRE_SOURCE=repo, after ./gradlew build) or from the npm release CRAFTWIRE_VERSION.
ARG CRAFTWIRE_SOURCE=repo

FROM eclipse-temurin:25-jre AS from-repo
COPY botrunner/build/libs/craftwire-botrunner-*.jar /opt/craftwire/

FROM node:22-bookworm-slim AS from-npm
ARG CRAFTWIRE_VERSION=latest
RUN npm install -g "craftwire@${CRAFTWIRE_VERSION}" && mkdir -p /opt/craftwire \
    && cp "$(npm root -g)"/craftwire/agent/craftwire-botrunner-*.jar /opt/craftwire/

FROM from-${CRAFTWIRE_SOURCE} AS runner

FROM eclipse-temurin:25-jre
COPY --from=runner /opt/craftwire/ /opt/craftwire/
RUN mv /opt/craftwire/craftwire-botrunner-*.jar /opt/craftwire/botrunner.jar && mkdir -p /craftwire && chown 1000:1000 /craftwire
ENV CRAFTWIRE_HOME=/craftwire
USER 1000
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/opt/craftwire/botrunner.jar", "--home", "/craftwire"]
CMD ["--name", "docker-runner", "--kinds", "java,bedrock", "--max-bots", "1000"]
