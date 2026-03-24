# Workspace

## Overview

pnpm workspace monorepo using TypeScript. Each package manages its own dependencies.

---

## ErnOS Mobile Hub (Android)

Android APK project at `android/`. Targets arm64-v8a, API 26+.

### Milestones

| # | Title | Status |
|---|-------|--------|
| 1 | Android Project + Local Inference (llama.cpp JNI, LlamaRuntime.kt) | COMPLETE |
| 2 | Cognitive Engine + ReAct Loop (SystemPrompter, ReActLoopManager, ToolRegistry) | COMPLETE |
| 3 | 5-Tier Memory System | COMPLETE |
| 4 | Platform Bridges + PC Offload | COMPLETE |
| 5 | Meta Ray-Ban Glasses + Vision | COMPLETE |
| 6 | Settings, Themes, and Polish | COMPLETE |

### Milestone 3 — 5-Tier Memory System

All five memory tiers written; fully wired into `ChatViewModel` and `ToolRegistry`.

**Tier structure:**

| Tier | File | Backing Store | Purpose |
|------|------|---------------|---------|
| 1 | `Tier1WorkingMemory.kt` | Room `messages` table | Rolling window of chat messages; KV-cache state serialisation |
| 2 | `Tier2AutoSave.kt` | Room `chunks` + ONNX (all-MiniLM-L6-v2) | Semantic embedding + cosine similarity search |
| 3 | `Tier3SynapticGraph.kt` | Room `graph_nodes` + `graph_edges` | Homeostasis: decay×0.95, prune<0.05, reinforce+0.1 |
| 4 | `Tier4Timeline.kt` | JSONL append-only file | Audit log with date + keyword search |
| 5 | `Tier5Scratchpad.kt` | DataStore\<Preferences\> | Typed KV store for session state, model config, KV-cache tag |

**Retrieval-first ordering (enforced in ChatViewModel.sendMessage):**
1. `retrieveContext(userMessage)` — before any store
2. `storeUserMessage(text)`
3. ReAct loop runs
4. `storeAiResponse(finalText)`

**ONNX model:** `all-MiniLM-L6-v2.onnx` must be pushed to `getExternalFilesDir(null)/` alongside the GGUF file. If absent, Tier 2 disables gracefully; Tier 4 keyword search still works.

**Room DB:** `AppDatabase` (version 1), `exportSchema = true`, schema exported to `android/app/schemas/`.

**Unit tests (src/test):**
- `RetrievalFirstOrderTest` — retrieval-first ordering invariant
- `Tier2EmbeddingTest` — FNV-1a hash, normalisation, cosine similarity, CSV round-trip
- `Tier3GraphTest` — homeostasis constants, reinforcement cap, decay, prune logic, entity defaults

---

### Milestone 6 — Settings, Themes, and Polish

**SettingsViewModel** (`settings/SettingsViewModel.kt`):
- DataStore-backed via Tier5Scratchpad (keys prefixed `settings_` to avoid collision)
- Parameters: nCtx (512–16384), temperature (0–2), topP (0–1), maxTurns (1–30), presencePenalty (0–2)
- Theme: themeChoice ("system"|"dark"|"light"), dynamicColor
- All setters coerce into valid range and persist immediately via viewModelScope

**SettingsScreen** (`settings/SettingsScreen.kt`):
- Full slider UI with local drag-state; persists on `onValueChangeFinished`
- FilterChip row for theme selection; Switch for dynamic color
- Reset-to-defaults button in TopAppBar and at bottom of screen

**Theme** (`theme/Theme.kt`):
- `ErnOSTheme(themeChoice, dynamicColor)` — replaces old `(darkTheme, dynamicColor)` signature
- Supports "system" (follows OS), "dark", "light" + Material3 dynamic color (Android 12+)
- `DarkColorScheme` and `LightColorScheme` now public for downstream use

**ModelHubViewModel** (`modelhub/ModelHubViewModel.kt`):
- Queries `https://huggingface.co/api/models?search=…&filter=gguf` for GGUF models
- Parses model siblings to extract GGUF filenames, quantization tags, and file sizes
- Streams download progress (OkHttp) into `DownloadState` map keyed by `modelId/filename`
- On download completion, provides local path; "Load" button triggers `ChatViewModel.loadModel`

**ModelHubScreen** (`modelhub/ModelHubScreen.kt`):
- Search bar → real HuggingFace results with download count and likes
- Expandable model cards listing GGUF files with size/quant metadata
- Per-file download progress bar + status icons (Download / Cancel / ✓ Load)

**MainActivity navigation** (`MainActivity.kt`):
- Compose NavHost with 3 routes: `chat`, `settings`, `model_hub`
- `ErnOSApp` composable observes `themeChoice` + `dynamicColor` from SettingsViewModel
- `ChatScreen` gains `onOpenSettings` and `onOpenModelHub` nav callbacks
- Settings and Hub icons added to ChatScreen TopAppBar

**Navigation dependency**: `androidx.navigation:navigation-compose:2.8.3` added to `libs.versions.toml` and `build.gradle.kts`

**Unit tests (src/test):**
- `engine/ReActLoopTest` — MAX_TURNS, tool-call regex, stripToolCallBlocks, ToolCall/ToolResult/ReActLoopResult, FakeToolRegistry, multimodal filename heuristic
- `engine/SystemPrompterTest` — vision tool gating, host-tool gating, memory section presence, additionalDirectives ordering
- `bridge/BridgeTest` — UnifiedMessage construction, BridgeSource enum, BridgeConfig.isConfigured for all 5 variants, Telegram/Discord JSON normalisation, reply routing
- `settings/SettingsViewModelTest` — all defaults, coercion at boundaries, resetToDefaults
- `modelhub/ModelHubViewModelTest` — sizeLabel formatting, quantization parsing, DownloadState transitions, HuggingFaceModel, formatCount

---

## Stack

- **Monorepo tool**: pnpm workspaces
- **Node.js version**: 24
- **Package manager**: pnpm
- **TypeScript version**: 5.9
- **API framework**: Express 5
- **Database**: PostgreSQL + Drizzle ORM
- **Validation**: Zod (`zod/v4`), `drizzle-zod`
- **API codegen**: Orval (from OpenAPI spec)
- **Build**: esbuild (CJS bundle)

## Structure

```text
artifacts-monorepo/
├── artifacts/              # Deployable applications
│   └── api-server/         # Express API server
├── lib/                    # Shared libraries
│   ├── api-spec/           # OpenAPI spec + Orval codegen config
│   ├── api-client-react/   # Generated React Query hooks
│   ├── api-zod/            # Generated Zod schemas from OpenAPI
│   └── db/                 # Drizzle ORM schema + DB connection
├── scripts/                # Utility scripts (single workspace package)
│   └── src/                # Individual .ts scripts, run via `pnpm --filter @workspace/scripts run <script>`
├── pnpm-workspace.yaml     # pnpm workspace (artifacts/*, lib/*, lib/integrations/*, scripts)
├── tsconfig.base.json      # Shared TS options (composite, bundler resolution, es2022)
├── tsconfig.json           # Root TS project references
└── package.json            # Root package with hoisted devDeps
```

## TypeScript & Composite Projects

Every package extends `tsconfig.base.json` which sets `composite: true`. The root `tsconfig.json` lists all packages as project references. This means:

- **Always typecheck from the root** — run `pnpm run typecheck` (which runs `tsc --build --emitDeclarationOnly`). This builds the full dependency graph so that cross-package imports resolve correctly. Running `tsc` inside a single package will fail if its dependencies haven't been built yet.
- **`emitDeclarationOnly`** — we only emit `.d.ts` files during typecheck; actual JS bundling is handled by esbuild/tsx/vite...etc, not `tsc`.
- **Project references** — when package A depends on package B, A's `tsconfig.json` must list B in its `references` array. `tsc --build` uses this to determine build order and skip up-to-date packages.

## Root Scripts

- `pnpm run build` — runs `typecheck` first, then recursively runs `build` in all packages that define it
- `pnpm run typecheck` — runs `tsc --build --emitDeclarationOnly` using project references

## Packages

### `artifacts/api-server` (`@workspace/api-server`)

Express 5 API server. Routes live in `src/routes/` and use `@workspace/api-zod` for request and response validation and `@workspace/db` for persistence.

- Entry: `src/index.ts` — reads `PORT`, starts Express
- App setup: `src/app.ts` — mounts CORS, JSON/urlencoded parsing, routes at `/api`
- Routes: `src/routes/index.ts` mounts sub-routers; `src/routes/health.ts` exposes `GET /health` (full path: `/api/health`)
- Depends on: `@workspace/db`, `@workspace/api-zod`
- `pnpm --filter @workspace/api-server run dev` — run the dev server
- `pnpm --filter @workspace/api-server run build` — production esbuild bundle (`dist/index.cjs`)
- Build bundles an allowlist of deps (express, cors, pg, drizzle-orm, zod, etc.) and externalizes the rest

### `lib/db` (`@workspace/db`)

Database layer using Drizzle ORM with PostgreSQL. Exports a Drizzle client instance and schema models.

- `src/index.ts` — creates a `Pool` + Drizzle instance, exports schema
- `src/schema/index.ts` — barrel re-export of all models
- `src/schema/<modelname>.ts` — table definitions with `drizzle-zod` insert schemas (no models definitions exist right now)
- `drizzle.config.ts` — Drizzle Kit config (requires `DATABASE_URL`, automatically provided by Replit)
- Exports: `.` (pool, db, schema), `./schema` (schema only)

Production migrations are handled by Replit when publishing. In development, we just use `pnpm --filter @workspace/db run push`, and we fallback to `pnpm --filter @workspace/db run push-force`.

### `lib/api-spec` (`@workspace/api-spec`)

Owns the OpenAPI 3.1 spec (`openapi.yaml`) and the Orval config (`orval.config.ts`). Running codegen produces output into two sibling packages:

1. `lib/api-client-react/src/generated/` — React Query hooks + fetch client
2. `lib/api-zod/src/generated/` — Zod schemas

Run codegen: `pnpm --filter @workspace/api-spec run codegen`

### `lib/api-zod` (`@workspace/api-zod`)

Generated Zod schemas from the OpenAPI spec (e.g. `HealthCheckResponse`). Used by `api-server` for response validation.

### `lib/api-client-react` (`@workspace/api-client-react`)

Generated React Query hooks and fetch client from the OpenAPI spec (e.g. `useHealthCheck`, `healthCheck`).

### `scripts` (`@workspace/scripts`)

Utility scripts package. Each script is a `.ts` file in `src/` with a corresponding npm script in `package.json`. Run scripts via `pnpm --filter @workspace/scripts run <script>`. Scripts can import any workspace package (e.g., `@workspace/db`) by adding it as a dependency in `scripts/package.json`.
