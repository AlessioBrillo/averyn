# Build context: apps/web. The Caddyfile is mounted by Compose (infrastructure/compose/Caddyfile).
FROM node:26-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM caddy:2-alpine
COPY --from=build /src/dist /srv
EXPOSE 80
