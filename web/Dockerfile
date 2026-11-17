# syntax=docker/dockerfile:1.7
# ParkSmart web app: static build served by nginx, which also reverse-proxies the API (same origin, no CORS).
# Build from web/:  docker build -t parksmart/web .
# Run:              docker run -p 3000:8080 -e GATEWAY_URL=http://gateway:8080 parksmart/web

FROM node:22.20.0-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY . .
# true while payments run on the FakeProcessor (test-mode banner, test cards, test payout accounts).
ARG VITE_TEST_MODE=true
# Map tiles. OpenStreetMap's public servers are for light use only; use a tile provider for real traffic.
ARG VITE_MAP_TILE_URL=https://tile.openstreetmap.org/{z}/{x}/{y}.png
ENV VITE_TEST_MODE=$VITE_TEST_MODE VITE_MAP_TILE_URL=$VITE_MAP_TILE_URL
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.27.5-alpine
# Upstreams and CSP origins, substituted into the config templates when the container starts.
ENV GATEWAY_URL=http://gateway:8080 \
    STORAGE_URL=http://object-storage:9000 \
    STORAGE_SIGNED_HOST=localhost:9000 \
    MEDIA_ORIGINS="http://localhost:9000" \
    UPLOAD_ORIGINS="" \
    MAP_TILE_ORIGIN=https://tile.openstreetmap.org
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY nginx/security-headers.conf.template /etc/nginx/templates/snippets/security-headers.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
