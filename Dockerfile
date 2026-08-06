# Two stages: build the storefront and the uberjar, ship neither toolchain.
FROM clojure:temurin-21-tools-deps AS build
WORKDIR /src

# Dependencies first, so a source edit does not re-resolve the world.
COPY deps.edn shadow-cljs.edn package.json package-lock.json* ./
RUN clojure -P -M:cljs:stripe:monero-rpc:jdbc:build

COPY . .
RUN apt-get update && apt-get install -y --no-install-recommends nodejs npm \
    && npm install --no-audit --no-fund \
    && npx shadow-cljs release app \
    && rm -rf node_modules \
    && apt-get purge -y nodejs npm && apt-get autoremove -y && rm -rf /var/lib/apt/lists/*

# Include the rails this image runs. Drop an alias to drop its SDK.
RUN clojure -T:build uber :aliases '[:stripe :monero-rpc :jdbc]'

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN adduser -D -u 10001 store
COPY --from=build /src/target/*-standalone.jar /app/store.jar
USER store
EXPOSE 8080
ENV PORT=8080
ENTRYPOINT ["java", "-jar", "/app/store.jar"]
