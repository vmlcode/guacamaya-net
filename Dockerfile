# GuacaMalla Net — backend image (Railway / Docker).
#
# IMPORTANT: build context is the REPO ROOT, not backend/. The backend imports
# @guacamaya/shared (a Bun `workspace:*` package) and backend/tsconfig.json
# extends ../tsconfig.base.json — both live at the root, so an isolated backend/
# build fails (TS5083 → tsc falls back to CommonJS → TS1309 top-level-await error).
#
# @guacamaya/shared resolves straight to its TypeScript source (no dist build), so
# Bun runs the entrypoint directly — no tsc/compile step needed.
FROM oven/bun:1

WORKDIR /app

# 1) Install deps in a cached layer: copy only the manifests + lockfile first.
#    The two workspace package.json files must be present for Bun to wire the
#    workspace symlinks during install.
COPY package.json bun.lock ./
COPY backend/package.json ./backend/package.json
COPY packages/shared/package.json ./packages/shared/package.json
RUN bun install --frozen-lockfile

# 2) Copy the source. node_modules is excluded via .dockerignore so this does
#    not clobber the installed deps from the layer above.
COPY packages/ ./packages/
COPY backend/ ./backend/

# Production by design: the server refuses to boot without GUACAMAYA_ADMIN_KEY
# (set it, and the rest, as Railway Variables — see backend/.env.example).
ENV NODE_ENV=production

# Railway injects $PORT; the server reads process.env.PORT (defaults to 3000)
# and binds 0.0.0.0 (already set in src/index.ts).
EXPOSE 3000

CMD ["bun", "backend/src/index.ts"]
