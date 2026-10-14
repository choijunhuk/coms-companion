FROM node:24.21.0-bookworm-slim AS build
WORKDIR /app
ARG PUBLIC_BASE_URL=http://localhost:3000
COPY package.json package-lock.json ./
COPY apps/web/package.json apps/web/package.json
COPY apps/server/package.json apps/server/package.json
RUN npm ci
COPY apps/web apps/web
COPY apps/server apps/server
RUN PUBLIC_BASE_URL="$PUBLIC_BASE_URL" npm run build
RUN npm prune --omit=dev

FROM node:24.21.0-bookworm-slim
WORKDIR /app
ARG SOURCE_COMMIT=local
LABEL org.opencontainers.image.revision=$SOURCE_COMMIT
ENV NODE_ENV=production
COPY --from=build /app/node_modules node_modules
COPY package.json ./
COPY apps/server/package.json apps/server/package.json
COPY --from=build /app/apps/server/dist apps/server/dist
COPY --from=build /app/apps/web/dist apps/web/dist
USER node
CMD ["node", "apps/server/dist/index.js"]
