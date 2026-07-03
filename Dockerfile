# GuacaMalla Net — backend image (Railway / Docker). Also builds and bundles
# the marketing/waitlist page (guacamalla-landing/landing), which the backend
# serves as static files at GET / (see backend/src/index.ts) — one image, one
# service.
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
#    Every workspace's package.json must be present for Bun to wire the
#    workspace symlinks during install.
COPY package.json bun.lock ./
COPY backend/package.json ./backend/package.json
COPY packages/shared/package.json ./packages/shared/package.json
RUN bun install --frozen-lockfile

#    The landing is NOT a root workspace — it ships its own bun.lock/bunfig
#    (self-contained Vite app), so it gets its own cached install layer.
COPY guacamalla-landing/landing/package.json guacamalla-landing/landing/bun.lock guacamalla-landing/landing/bunfig.toml ./guacamalla-landing/landing/
RUN bun install --frozen-lockfile --cwd guacamalla-landing/landing

# 2) Copy the source. node_modules is excluded via .dockerignore so this does
#    not clobber the installed deps from the layers above.
COPY packages/ ./packages/
COPY backend/ ./backend/
COPY guacamalla-landing/landing/ ./guacamalla-landing/landing/

# 3) Build the landing page to static files (guacamalla-landing/landing/dist) —
#    no SSR, no server needed at runtime, the backend just serves the compiled
#    output.
RUN bun run --cwd guacamalla-landing/landing build

# Production by design: the server refuses to boot without GUACAMAYA_ADMIN_KEY
# (set it, and the rest, as Railway Variables — see backend/.env.example).
ENV NODE_ENV=production

# Railway injects $PORT; the server reads process.env.PORT (defaults to 3000)
# and binds 0.0.0.0 (already set in src/index.ts).
EXPOSE 3000

CMD ["bun", "backend/src/index.ts"]
