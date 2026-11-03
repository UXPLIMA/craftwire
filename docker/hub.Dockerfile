# The Craftwire hub as a long-lived server (craftwire serve): MCP over HTTP on 7777 and the web panel at /panel.
# CRAFTWIRE_SOURCE=repo builds this checkout (run ./gradlew build first for the agent jars); npm installs the release
# CRAFTWIRE_VERSION from npm. Target "client" adds Java, Xvfb and Mesa so client_process runs hidden game clients with
# software rendering (screenshots, the panel's live view).
ARG CRAFTWIRE_SOURCE=repo

FROM node:22-bookworm-slim AS from-repo
WORKDIR /src/hub
COPY hub/package.json hub/package-lock.json ./
RUN npm ci
COPY hub/ ./
COPY claude-plugin/skills /src/claude-plugin/skills
COPY docs /src/docs
COPY agent-fabric/build/libs/ /src/agent-fabric/build/libs/
COPY botrunner/build/libs/ /src/botrunner/build/libs/
COPY agent-paper/build/libs/ /src/agent-paper/build/libs/
RUN npm run build && node scripts/copy-skills.mjs && node scripts/copy-agent.mjs && node scripts/copy-docs.mjs \
    && npm prune --omit=dev \
    && mkdir -p /opt/craftwire && cp -r dist node_modules package.json skills agent docs /opt/craftwire/

FROM node:22-bookworm-slim AS from-npm
ARG CRAFTWIRE_VERSION=latest
RUN npm install -g "craftwire@${CRAFTWIRE_VERSION}" && cp -r "$(npm root -g)/craftwire" /opt/craftwire

FROM from-${CRAFTWIRE_SOURCE} AS source

FROM node:22-bookworm-slim AS hub
COPY --from=source /opt/craftwire /opt/craftwire
# The shared home (hub.json for the agents, tokens, logs): owned by uid 1000, as in every Craftwire image.
RUN mkdir /craftwire && chown node:node /craftwire
ENV CRAFTWIRE_HOME=/craftwire
WORKDIR /opt/craftwire
USER node
EXPOSE 7777
ENTRYPOINT ["node", "/opt/craftwire/dist/cli.js"]
CMD ["serve", "--host", "0.0.0.0", "--allow-remote", "--port", "7777"]

FROM hub AS client
USER root
# From 26.3 the game (SDL3) needs EGL under Xvfb; Mesa's llvmpipe renders without a GPU.
RUN apt-get update && apt-get install -y --no-install-recommends xvfb xauth libegl1 libegl-mesa0 libgl1 libgl1-mesa-dri \
      libxrandr2 libxi6 libxcursor1 libxxf86vm1 libpulse0 ca-certificates ffmpeg \
    && rm -rf /var/lib/apt/lists/*
COPY --from=eclipse-temurin:25-jre /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk PATH=/opt/java/openjdk/bin:$PATH SDL_VIDEO_FORCE_EGL=1 LIBGL_ALWAYS_SOFTWARE=1
USER node
ENTRYPOINT ["xvfb-run", "-a", "-s", "-screen 0 1920x1080x24", "node", "/opt/craftwire/dist/cli.js"]
