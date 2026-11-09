# slim (glibc) rather than alpine so better-sqlite3 uses prebuilt binaries.
# Node 22 LTS (Node 20 stopped getting security fixes in April 2026), pinned
# by digest so the image can't change under the same tag.
FROM node:22-slim@sha256:5ec23b7c622b00547753bc6ffd78085dd868a68aeb41c8e0a1418f033e13d96d

WORKDIR /app

# Install production dependencies first for better layer caching.
COPY package.json package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force

COPY . .

ENV NODE_ENV=production
ENV PORT=8080
EXPOSE 8080

# The Fly volume at /data is root-owned: hand it to the unprivileged "node"
# user, then run the server as that user (falls back to root only if setpriv
# were ever missing from the base image).
CMD ["sh", "-c", "mkdir -p /data && chown -R node:node /data && if command -v setpriv >/dev/null; then exec setpriv --reuid=node --regid=node --init-groups node server.js; else exec node server.js; fi"]
