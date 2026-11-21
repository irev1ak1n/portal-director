# Engineering audit

A review of PortalDirector 1.8.0 before the performance and reliability work. It lists how the plugin is built, what already works well, and where the real weak spots are. Every point below was checked in the code. Numbers come from the baseline runs described at the end.

## How it is built

| Part | Package | What it does |
|---|---|---|
| Plugin start-up | `PortalDirectorPlugin` | Creates the managers, registers listeners, owns the one active job (`Job`) that `/pd stop` cancels |
| Planning | `gen` | `PortalPlanner.Session` picks spots a few attempts per tick, `FormationPlanner` plans grid, wave and square, `Bounds` keeps every frame inside the radius |
| Building | `gen` | `GenerationJob` runs load, plan, build; `PortalEdits` turns a portal into block edits; `SinglePortal` builds one named portal |
| Scenes | `scene` | `Scene` holds portals and a `BlockLog` of every changed block; `SceneStore` writes a `.yml` and a `.blocks` file; `RemovalJob` restores blocks |
| Groups | `group` | `GroupManager` (saved in `groups.yml`), `Cinematic` (prepare, hide, staggered reveal), `JoinBlockListener` |
| Nether | `nether` | `NetherLinks` keeps one partner per portal, `NetherSite` searches a safe spot, `PortalTravelListener` redirects vanilla travel |
| TNT | `tnt` | `TntDirector` launches and counts minecarts, `TntSchedule` plans waves |
| Sequences | `sequence` | `SequenceManager`, the shared `FloatingRun` engine and the three sequences, with pure planners (`SkyPlanner`, `SeqTimeline`, `ArrivalSpots`) |
| Commands | `command` | `PdCommand` dispatches to `GenerateCommands`, `PortalCommands`, `GroupCommands`, `SequenceCommands`, `TntCommands`, `SceneCommands`, `HelpCommand` |

Everything that touches the world runs on the main thread. The only asynchronous work today is chunk loading (`World#getChunkAtAsync`), and every callback hops back to the main thread with the scheduler before it touches anything.

## What already works well (kept as it is)

- **Bounded asynchronous chunk loading.** `GenerationJob` and `FloatingRun` keep at most `generate.chunk-loads-at-once` (6) loads in flight and hold a plugin chunk ticket for each loaded chunk until the job ends, so chunks cannot unload mid-build.
- **Work spread over ticks.** Planning tries `plannerBudget` (80) spots per tick, instant generation places `blocks-per-tick` (400) blocks, delayed generation builds one whole portal per step, and removal restores the same number of blocks per tick, chunk by chunk.
- **A portal is never half built.** In delayed mode a portal joins the scene only after all its blocks are placed; an interrupted portal is dropped from the scene but its blocks stay in the log.
- **Compact, safe block log.** `BlockLog` is a palette-compressed binary file written to a temporary file and moved into place, and removal only restores a block that still holds what the scene placed.
- **Cancellation.** `/pd stop` cancels the active job; `GenerationJob.cancel` finishes at once when the server is stopping; every job releases its chunk tickets in `finish`.
- **Cleanup of players.** `Cinematic` and `SequenceManager` show hidden players and release held players on quit, world change, stop and shutdown.
- **Planning is pure.** The planners work over the `Terrain` and `NetherGrid` interfaces, so they are unit tested with fake worlds (no server needed).

## Findings

Severity is about large scenes (100 or more portals) and busy servers. The "Measured" column says what number backs a finding. "Not isolated" means the cost is inside a larger measurement and was not timed on its own.

### Reliability

| # | Finding | Where | Risk |
|---|---|---|---|
| R1 | Several files are written in place, so a crash or full disk during the write can leave a truncated file: the scene `.yml`, `groups.yml`, `nether/pairs.yml`, `nether/memory.yml`, `sequences.yml`. Only the `.blocks` file uses a temporary file and a move | `SceneStore.save`, `GroupManager.save`, `NetherLinks.save`, `SeqSettings.save` | Data loss of a scene, group or pairing list |
| R2 | A scene is two files written one after the other (`.yml` then `.blocks`). A crash between them leaves a new portal list with an old block log | `SceneStore.save` | A portal that cannot be fully restored |
| R3 | A removal that is cut off by a shutdown is safe (the block log keeps what is left), but an interrupted Nether partner removal is only repaired at the next start by `cleanOrphans` | `NetherLinks`, `RemovalJob` | Low, repaired on start |
| R4 | No record of long operations, so after a crash there is no way to tell what was running | n/a | Hard to diagnose |

### Main thread and I/O

| # | Finding | Where | Measured |
|---|---|---|---|
| P1 | Every scene save runs on the main thread and rewrites the whole `.yml` and the whole `.blocks` file. It is called after every single portal, at the end of each job, and every 40 ticks during a delayed reveal, so a long reveal rewrites a growing file many times | `SceneManager.save`, `GenerationJob.autosave`, 12 call sites in `GenerateCommands` | yes, 22 to 39 ms per save on the main thread |
| P2 | `saveSettings()` rewrites `config.yml` on the main thread after most commands | `PortalDirectorPlugin.saveSettings` | no, small file |
| P3 | Nether chunks around a partner are loaded with the blocking `getChunkAt` (up to about 40 chunks, which generate if new). This runs inside `PlayerPortalEvent` on a player's first trip through a portal, and in `/pd create portal` and Nether Arrival | `NetherLinks.ensure`, `NetherArrival.arrive` | partly |
| P4 | `PortalEdits.apply` builds two block-data strings for every block it places, and `RemovalJob.restoreOne` builds one per block it checks | `PortalEdits`, `RemovalJob` | not isolated |
| P5 | Floating and ground planning read the same column heights several times (`airY`, `groundY`, `openAbove`, then again at build time in `PortalEdits.forPortal`) | `PortalPlanner`, `PortalEdits` | not isolated, part of the planning times |

### Data structures

| # | Finding | Where | Measured |
|---|---|---|---|
| D1 | Every candidate spot is compared with every placed portal (`separatedFrom` in a list), so planning is O(attempts x portals) | `PortalPlanner.Session.tryAt`, `FormationPlanner.check`, `SinglePortal.checkSpacing` | yes, 1.6 ms for 100 portals up to 82 ms for 1000 |
| D2 | A real-mode explosion with terrain damage on checks every block of the explosion against every portal and rebuilds the portal list for each explosion | `TntDirector.onExplode` | yes, 0.4 to 1.1 ms per explosion |
| D3 | Removing one portal scans the whole block log to find its records, so removing N portals one by one is O(N x blocks) | `PortalCommands.recordsOf` | no |
| D4 | Looking for the portal a player entered scans every portal of every scene on each portal event | `PortalTravelListener.onPortal` | no, rare event |
| D5 | Hiding a group of P players calls `hideEntity` for every pair, O(P squared) calls | `Cinematic.hide` | not isolated, inside the Sky Army runs |

### Threading and portability

- No world object is used off the main thread, so there are no races to fix today. Any asynchronous work added later has to keep that rule: capture data on the main thread, compute or write files elsewhere, apply results back on the main thread.
- The plugin uses the Bukkit scheduler, not the region schedulers, so it is **Paper only**. `plugin.yml` does not claim Folia support and the README says Folia is unsupported. That stays true.
- `PlayerQuitEvent` and world changes release sequence and cinematic state, but a callback that arrives after a scene was deleted or renamed has no guard today (scene writes are synchronous, so nothing is pending, but an asynchronous writer would need one).

### Test coverage

- 152 unit tests (2 skipped because MockBukkit does not implement the world calls they need) and ten Mineflayer suites on an isolated server. Building and restoring blocks is covered by the live suites, not by unit tests.
- There is no continuous integration: the repository has no `.github/workflows`.
- There was no benchmark workflow and no diagnostics command.

## Where new technology helps, and where it does not

| Idea | Verdict |
|---|---|
| Background writer with immutable snapshots and atomic files | Yes: fixes R1, R2 and P1 without touching the world off-thread |
| Spatial grid for portal lookups | Yes: fixes D1, D2, D4 and speeds up every planner |
| Column-height cache while planning | Yes: P5, planning only, never used at build time |
| SQLite | Yes, for new structured data (recording takes, assignment profiles, take history, recovery records). Not for scenes: the block log and scene files stay as they are |
| HikariCP | No: one SQLite file with one writer thread does not need a pool |
| PacketEvents | Not needed for a static preview: Paper has `Player#sendBlockChanges`, which is supported and safer (see `docs/PERFORMANCE.md` and the preview notes) |
| FastAsyncWorldEdit | Optional only, for restoring a recording arena between takes |
| Cloud or Brigadier | Only if it clearly beats the current parser; the current one already has context-aware, sorted tab completion and clear errors |

## Baseline (before any change)

Measured on the development machine with the 1.8.0 jar. The method and the numbers for the benchmark are in `docs/PERFORMANCE.md`.

### Planning only (microbenchmark, no server)

Random floating portals over flat ground, median of 7 runs.

| Portals | Time |
|---|---|
| 100 | 1.6 ms |
| 250 | 6.2 ms |
| 500 | 19.8 ms |
| 1000 | 81.7 ms |

### Saving a scene (microbenchmark, what ran on the main thread)

| Portals | Blocks | File size | Time |
|---|---|---|---|
| 30 | 1800 | 38 KB | 22.4 ms |
| 100 | 6000 | 126 KB | 28.5 ms |
| 250 | 15000 | 316 KB | 25.3 ms |
| 500 | 30000 | 633 KB | 39.4 ms |

### One explosion against every portal (microbenchmark)

120 blocks, terrain damage on, median of 7 runs: 0.39 ms with 100 portals, 0.86 ms with 250, 1.10 ms with 500, 0.55 ms with 1000. The 1000 figure is lower because the portals spread out and fewer are close to the blast.

### Live server (Paper 26.3, view and simulation distance 2, 127.0.0.1 only, 3 cycles per size)

| Portals | Build time | Clear time | Worst 99th percentile tick |
|---|---|---|---|
| 10 | 626 ms | 75 ms | 70 ms (one 9796 ms tick during the first cycle while the world was still loading) |
| 30 | 1271 ms | 131 ms | 52 ms |
| 100 | 2957 ms | 303 ms | 37 ms |
| 250 | 4722 ms | 662 ms | 62 ms |

- TNT Strike, 20 portals, safe mode: 11.4 s, 137 minecarts, worst average tick 24.5 ms, cleaned up in 36 ms.
- Sky Army with bots: 10 players ready in 6.2 s (worst 99th percentile 60 ms), 20 in 8.9 s (111 ms), 40 in 18.8 s (125 ms). Bots are simulated players, not real clients.
- Restart with 250 portals: shutdown 2.3 s, the scene was back after the restart, clear after restart 649 ms.
- No errors and no warnings in the whole run.

### What this changed in the plan

Planning is cheap until about 1000 portals, so the spatial index matters mostly for very large scenes and for explosions. The largest cost is the synchronous scene save, so background and atomic saves come first.

Unit tests at this point: 183 passed, 2 skipped. Live suites: names had one late-reply flake, simplify had two checks that expected an old tab list and help-page length (both are test problems, not plugin bugs, and are fixed in the regression pass).

