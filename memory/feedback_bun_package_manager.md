---
name: feedback_bun_package_manager
description: User prefers Bun as the package manager and runtime for this project
metadata:
  type: feedback
---

Use Bun instead of npm/npx/node for all package management and script execution in guacamaya-net.

**Why:** User preference — Bun first.

**How to apply:** Use `bun install`, `bun run`, `bunx` instead of `npm install`, `npm run`, `npx`. Update package.json scripts to use `bun` where relevant.
