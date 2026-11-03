# A Paper server with the Craftwire plugin, for docker/compose.yml. The Paper build is pinned with its sha256
# (versions/<mc>.properties); the plugin comes from this checkout (CRAFTWIRE_SOURCE=repo, after ./gradlew build) or
# from the npm release CRAFTWIRE_VERSION. It starts only once the user sets EULA=true (docker/paper-entrypoint.sh).
ARG CRAFTWIRE_SOURCE=repo

FROM eclipse-temurin:25-jre AS paper-jar
ARG MC_VERSION=26.3
ARG PAPER_BUILD=157
ARG PAPER_SHA256=77cdcd758777734b0b64db74974f68e56cb08637f3c36b26734842cb8521cc2e
RUN apt-get update && apt-get install -y --no-install-recommends curl jq && rm -rf /var/lib/apt/lists/* \
    && url="$(curl -fsSL "https://fill.papermc.io/v3/projects/paper/versions/${MC_VERSION}/builds/${PAPER_BUILD}" \
        | jq -r '.downloads["server:default"].url')" \
    && curl -fsSL -o /opt/paper.jar "$url" \
    && echo "${PAPER_SHA256}  /opt/paper.jar" | sha256sum -c -

FROM eclipse-temurin:25-jre AS from-repo
COPY agent-paper/build/libs/craftwire-paper-*.jar /opt/craftwire/

FROM node:22-bookworm-slim AS from-npm
ARG CRAFTWIRE_VERSION=latest
RUN npm install -g "craftwire@${CRAFTWIRE_VERSION}" && mkdir -p /opt/craftwire \
    && cp "$(npm root -g)"/craftwire/agent/craftwire-paper-*.jar /opt/craftwire/

FROM from-${CRAFTWIRE_SOURCE} AS plugin

FROM eclipse-temurin:25-jre
COPY --from=paper-jar /opt/paper.jar /opt/paper.jar
COPY --from=plugin /opt/craftwire/ /opt/craftwire/
COPY docker/paper-entrypoint.sh /usr/local/bin/paper-entrypoint
# uid 1000 (the image's "ubuntu" user) like every Craftwire image, so the shared home's hub.json is readable.
RUN chmod +x /usr/local/bin/paper-entrypoint && mkdir -p /data /craftwire && chown 1000:1000 /data /craftwire
ENV CRAFTWIRE_HOME=/craftwire
WORKDIR /data
USER 1000
ENTRYPOINT ["paper-entrypoint"]
