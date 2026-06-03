# Graph Report - storage-console  (2026-06-03)

## Corpus Check
- 64 files · ~17,052 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 435 nodes · 525 edges · 52 communities (39 shown, 13 thin omitted)
- Extraction: 93% EXTRACTED · 7% INFERRED · 0% AMBIGUOUS · INFERRED: 38 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `73c93ad8`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- [[_COMMUNITY_Community 0|Community 0]]
- [[_COMMUNITY_Community 1|Community 1]]
- [[_COMMUNITY_Community 2|Community 2]]
- [[_COMMUNITY_Community 3|Community 3]]
- [[_COMMUNITY_Community 4|Community 4]]
- [[_COMMUNITY_Community 5|Community 5]]
- [[_COMMUNITY_Community 6|Community 6]]
- [[_COMMUNITY_Community 7|Community 7]]
- [[_COMMUNITY_Community 8|Community 8]]
- [[_COMMUNITY_Community 9|Community 9]]
- [[_COMMUNITY_Community 10|Community 10]]
- [[_COMMUNITY_Community 11|Community 11]]
- [[_COMMUNITY_Community 12|Community 12]]
- [[_COMMUNITY_Community 13|Community 13]]
- [[_COMMUNITY_Community 14|Community 14]]
- [[_COMMUNITY_Community 15|Community 15]]
- [[_COMMUNITY_Community 16|Community 16]]
- [[_COMMUNITY_Community 17|Community 17]]
- [[_COMMUNITY_Community 18|Community 18]]
- [[_COMMUNITY_Community 19|Community 19]]
- [[_COMMUNITY_Community 20|Community 20]]
- [[_COMMUNITY_Community 21|Community 21]]
- [[_COMMUNITY_Community 22|Community 22]]
- [[_COMMUNITY_Community 23|Community 23]]
- [[_COMMUNITY_Community 24|Community 24]]
- [[_COMMUNITY_Community 25|Community 25]]
- [[_COMMUNITY_Community 26|Community 26]]
- [[_COMMUNITY_Community 27|Community 27]]
- [[_COMMUNITY_Community 28|Community 28]]
- [[_COMMUNITY_Community 29|Community 29]]
- [[_COMMUNITY_Community 30|Community 30]]
- [[_COMMUNITY_Community 31|Community 31]]
- [[_COMMUNITY_Community 32|Community 32]]
- [[_COMMUNITY_Community 33|Community 33]]
- [[_COMMUNITY_Community 34|Community 34]]
- [[_COMMUNITY_Community 35|Community 35]]
- [[_COMMUNITY_Community 36|Community 36]]
- [[_COMMUNITY_Community 37|Community 37]]
- [[_COMMUNITY_Community 38|Community 38]]
- [[_COMMUNITY_Community 39|Community 39]]
- [[_COMMUNITY_Community 40|Community 40]]
- [[_COMMUNITY_Community 41|Community 41]]
- [[_COMMUNITY_Community 42|Community 42]]
- [[_COMMUNITY_Community 43|Community 43]]
- [[_COMMUNITY_Community 44|Community 44]]
- [[_COMMUNITY_Community 45|Community 45]]
- [[_COMMUNITY_Community 46|Community 46]]
- [[_COMMUNITY_Community 47|Community 47]]
- [[_COMMUNITY_Community 48|Community 48]]
- [[_COMMUNITY_Community 51|Community 51]]

## God Nodes (most connected - your core abstractions)
1. `compilerOptions` - 17 edges
2. `Architecture` - 14 edges
3. `KubernetesService` - 12 edges
4. `Storage Console` - 12 edges
5. `User` - 11 edges
6. `Immich restic Backup` - 10 edges
7. `TaskController` - 10 edges
8. `String` - 10 edges
9. `storage-console` - 9 edges
10. `Maintenance` - 8 edges

## Surprising Connections (you probably didn't know these)
- `TaskCard()` --calls--> `relativeTime()`  [EXTRACTED]
  frontend/src/components/TaskCard.tsx → frontend/src/lib/format.ts
- `TaskCard()` --calls--> `timeOfDay()`  [EXTRACTED]
  frontend/src/components/TaskCard.tsx → frontend/src/lib/format.ts

## Communities (52 total, 13 thin omitted)

### Community 0 - "Community 0"
Cohesion: 0.07
Nodes (32): AuditEventRepository, Instant, KubernetesClient, List, Optional, RunSummary, String, Optional (+24 more)

### Community 1 - "Community 1"
Cohesion: 0.09
Nodes (20): ApplicationRunner, Bean, PasswordEncoder, String, UserRepository, AuthUser, GetMapping, PostMapping (+12 more)

### Community 2 - "Community 2"
Cohesion: 0.17
Nodes (14): ModeToggle(), HddBadge(), StatusBadge(), TaskCard(), api, HddStatus, Me, req() (+6 more)

### Community 3 - "Community 3"
Cohesion: 0.09
Nodes (21): dependencies, clsx, next, react, react-dom, devDependencies, autoprefixer, postcss (+13 more)

### Community 4 - "Community 4"
Cohesion: 0.10
Nodes (20): compilerOptions, allowJs, baseUrl, esModuleInterop, incremental, isolatedModules, jsx, lib (+12 more)

### Community 5 - "Community 5"
Cohesion: 0.12
Nodes (16): Backend logs, CronJob not found in the UI, DB access, "FAIL sha256-mismatch" in mover logs, Frontend logs, HDD shows "not mounted" but the disk is plugged in, Immich backup (restic), Maintenance (+8 more)

### Community 6 - "Community 6"
Cohesion: 0.13
Nodes (14): Architecture, Container images, CronJob HDD guard, Data model, Deployment diagram, HDD-connected detection, Ingress, Mode persistence (+6 more)

### Community 7 - "Community 7"
Cohesion: 0.24
Nodes (4): AuditEvent, Instant, Long, String

### Community 8 - "Community 8"
Cohesion: 0.17
Nodes (11): JwtService, String, User, Claims, FilterChain, HttpServletRequest, HttpServletResponse, OncePerRequestFilter (+3 more)

### Community 9 - "Community 9"
Cohesion: 0.31
Nodes (6): Bean, PasswordEncoder, HttpSecurity, JwtAuthFilter, SecurityConfig, SecurityFilterChain

### Community 10 - "Community 10"
Cohesion: 0.20
Nodes (9): Access, Depends on, Docs, History, Quick start, Stack, storage-console, Tear down (+1 more)

### Community 11 - "Community 11"
Cohesion: 0.22
Nodes (8): Checking from the CLI, Fallback: the `tier-now.sh` script, Logging in, Setting Auto mode, The dashboard, Triggering a manual run, Two concurrent runs, Usage

### Community 12 - "Community 12"
Cohesion: 0.25
Nodes (7): args, command, cwd, env, type, mcpServers, code-review-graph

### Community 13 - "Community 13"
Cohesion: 0.33
Nodes (3): Status, String, HddProbeService

### Community 14 - "Community 14"
Cohesion: 0.38
Nodes (4): GetMapping, HddProbeService, Status, SystemController

### Community 15 - "Community 15"
Cohesion: 0.15
Nodes (12): Access, Database, File reference, Initial credentials, See also, Source, Stack, Stack & framework (+4 more)

### Community 16 - "Community 16"
Cohesion: 0.47
Nodes (4): Bean, KubernetesClient, String, KubernetesConfig

### Community 17 - "Community 17"
Cohesion: 0.40
Nodes (4): Optional, String, User, UserRepository

### Community 18 - "Community 18"
Cohesion: 0.47
Nodes (4): JwtService, PasswordEncoder, UserRepository, AuthController

### Community 19 - "Community 19"
Cohesion: 0.40
Nodes (4): Debug Issue, Steps, Tips, Token Efficiency Rules

### Community 20 - "Community 20"
Cohesion: 0.40
Nodes (4): Explore Codebase, Steps, Tips, Token Efficiency Rules

### Community 21 - "Community 21"
Cohesion: 0.40
Nodes (4): Refactor Safely, Safety Checks, Steps, Token Efficiency Rules

### Community 22 - "Community 22"
Cohesion: 0.40
Nodes (4): Output Format, Review Changes, Steps, Token Efficiency Rules

### Community 23 - "Community 23"
Cohesion: 0.40
Nodes (4): Debug Issue, Steps, Tips, Token Efficiency Rules

### Community 24 - "Community 24"
Cohesion: 0.40
Nodes (4): Explore Codebase, Steps, Tips, Token Efficiency Rules

### Community 25 - "Community 25"
Cohesion: 0.40
Nodes (4): Refactor Safely, Safety Checks, Steps, Token Efficiency Rules

### Community 26 - "Community 26"
Cohesion: 0.40
Nodes (4): Output Format, Review Changes, Steps, Token Efficiency Rules

### Community 27 - "Community 27"
Cohesion: 0.40
Nodes (4): Key Tools, MCP Tools: code-review-graph, When to use graph tools FIRST, Workflow

### Community 28 - "Community 28"
Cohesion: 0.40
Nodes (4): Key Tools, MCP Tools: code-review-graph, When to use graph tools FIRST, Workflow

### Community 29 - "Community 29"
Cohesion: 0.40
Nodes (4): Key Tools, MCP Tools: code-review-graph, When to use graph tools FIRST, Workflow

### Community 30 - "Community 30"
Cohesion: 0.40
Nodes (4): Key Tools, MCP Tools: code-review-graph, When to use graph tools FIRST, Workflow

### Community 31 - "Community 31"
Cohesion: 0.40
Nodes (4): Key Tools, MCP Tools: code-review-graph, When to use graph tools FIRST, Workflow

### Community 32 - "Community 32"
Cohesion: 0.40
Nodes (4): Key Tools, MCP Tools: code-review-graph, When to use graph tools FIRST, Workflow

### Community 33 - "Community 33"
Cohesion: 0.50
Nodes (3): hooks, PostToolUse, SessionStart

### Community 35 - "Community 35"
Cohesion: 0.50
Nodes (3): hooks, AfterTool, SessionStart

### Community 36 - "Community 36"
Cohesion: 0.50
Nodes (3): hooks, PostToolUse, SessionStart

### Community 51 - "Community 51"
Cohesion: 0.14
Nodes (13): Concurrency model, Immich restic Backup, Manual run, Manual + scheduled overlap (safe but slower), Mount guard, Password (3 places), Repo location, Restore runbook (+5 more)

## Knowledge Gaps
- **198 isolated node(s):** `What it backs up`, `Repo location`, `Password (3 places)`, `Retention`, `Mount guard` (+193 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **13 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `JwtService` connect `Community 8` to `Community 1`?**
  _High betweenness centrality (0.004) - this node is a cross-community bridge._
- **Why does `AuthController` connect `Community 18` to `Community 1`?**
  _High betweenness centrality (0.003) - this node is a cross-community bridge._
- **What connects `What it backs up`, `Repo location`, `Password (3 places)` to the rest of the system?**
  _198 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 0` be split into smaller, more focused modules?**
  _Cohesion score 0.07393483709273183 - nodes in this community are weakly interconnected._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.09103840682788052 - nodes in this community are weakly interconnected._
- **Should `Community 3` be split into smaller, more focused modules?**
  _Cohesion score 0.09090909090909091 - nodes in this community are weakly interconnected._
- **Should `Community 4` be split into smaller, more focused modules?**
  _Cohesion score 0.09523809523809523 - nodes in this community are weakly interconnected._