# slim (glibc) rather than alpine so better-sqlite3 uses prebuilt binaries.
# Node 22 LTS (Node 20 stopped getting security fixes in April 2026).
FROM node:22-slim

WORKDIR /app

# Install production dependencies first for better layer caching.
COPY package.json package-lock.json ./
RUN npm ci --omit=dev

COPY . .

ENV NODE_ENV=production
ENV PORT=8080
EXPOSE 8080

CMD ["node", "server.js"]
