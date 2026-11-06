# Developer Guide

For server owners, the [README](../README.md) is all you need. This page is for building and changing the plugin.

## Requirements

- A JDK to run Gradle. The build uses a Gradle toolchain for Java 25.
- Git.

## Building

```bash
git clone https://github.com/irev1ak1n/portal-director.git
cd portal-director
./gradlew build
```

On Windows use `gradlew.bat build`. The plugin jar is written to `build/libs/PortalDirector-<version>.jar`. It contains only the plugin classes, `plugin.yml` and `config.yml`. Paper's API is `compileOnly`, so nothing is bundled.

Pinned versions (in `build.gradle.kts`): Paper API 26.3.build.231-stable, MockBukkit (v26.3) 4.119.0, JUnit 6.1.3, Java 25.

## Tests

```bash
./gradlew test
```

The unit tests use MockBukkit and plain JUnit. They cover the layout planners, the radius boundary (every frame block, every facing, the fixed 5% margin), mixed-mode balance, altitude and world-y limits, reveal ordering, spacing, terrain rules, portal names (including old scene files without names), settings migration and validation, help pages, scene saving and block restoring. The report is in `build/reports/tests/test/index.html`.

## Code layout

| Package | What is in it |
|---|---|
| `command` | `/pd` and its subcommands, tab completion, and `HelpCommand` (the paginated, clickable help) |
| `gen` | Layout planners (random, grid, square, wave), the radius `Bounds`, facing rules (`FacingMode`), reveal orders, the generation job |
| `scene` | Remembered portals (with their names) and block log, saving to disk, safe removal |
| `group` | Groups, member-to-portal links, join blocks, `Cinematic` (prepare, hiding, staggered reveal via `RevealPlan`) |
| `nether` | Portal pairing: `NetherLinks` (saved pairs and their blocks), `NetherSite` (safe-site search, tested over a fake grid), `PortalTravelListener` |
| `tnt` | TNT minecart launching and waves, and `launchOne` for sequences (counts against the cumulative limit) |
| `sequence` | The three built-in sequences (`NetherArrival`, `SkyArmy`, `TntStrike`), their settings (`SeqOption`, `SeqSettings`, kept in sequences.yml), `SequenceManager` (what a sequence holds), `FloatingRun` (the shared load, plan and play engine) and the pure parts that are unit tested: `SkyPlanner`, `SeqTimeline`, `ArrivalSpots` |

`Settings.java` is the single list of options behind `/pd set` and `/pd settings`. Renamed and removed config keys are listed at the top of it (`RENAMED`, `REMOVED`) and migrated in `load`; a test in `SettingsMigrationTest` covers each one. Minimum and maximum settings validate each other in `Settings` (and `GenSettings.problem()` before a build).

Portals are paired with the Nether in `NetherLinks`: the listener changes only the destination of vanilla's own teleport (`PlayerPortalEvent#setTo`, search radius 1, no creation), so cooldowns, effects and placement stay vanilla. Group members with `nether-travel false` get the event cancelled in `PortalTravelListener`. Hidden players use `Player#hideEntity` per viewer, tracked in `Cinematic` and always shown again on quit, world change, unlink, group deletion and shutdown.

How the rules are kept:

- **Radius.** `GenSettings.effectiveRadius()` is the radius plus the fixed 5% margin (`RADIUS_MARGIN_PERCENT`, deliberately not a setting), rounded down. The random planner samples the frame's corner inside `Bounds` so the whole obsidian frame fits; grid, wave and square are checked against `Bounds` before anything is removed or built (`requireInsideRadius`).
- **Facing.** `FacingMode.allowedFor(layout)` is the one list of values a layout takes; the axis is always derived from the facing.
- **Ground and slope.** The step a ground portal may stand on is the constant `PortalPlanner.MAX_GROUND_STEP`, not a setting. A failed ground spot is retried a few blocks away before it counts as a rejection.
- **Heights.** `altitude-min/max` are blocks above the local terrain (floating portals); `world-y-min/max` are absolute and apply to every portal frame.

Portals keep an internal numeric id (assignments and block records use it) and a name that people type. `Scene.addPortal` gives generated portals `portal-<id>`; `PortalCommands` handles `/pd create portal` and `/pd remove`. All Tab suggestions go through one alphabetical sort in `PdCommand.onTabComplete`, and `subs` there is a sorted map, so a new command only needs to be added once.

Sequences are plain classes: a `Sequence` has a name, a summary, a list of `SeqOption`s, its run arguments and the plain-language facts shown by `info`. To add one, extend `Sequence`, register it in `SequenceManager`, and add a line to the `sequence` help topic. `SkyPlanner.Run` drives the existing `PortalPlanner.Session` through widening stages, `SeqTimeline` turns portal counts and ranges into a tick schedule before the run starts (a player or minecart is never scheduled before its portal), and `FloatingRun` plays it, building each portal into the scene as its tick comes. Anything a sequence holds (players kept from using portals, portals that let a group through) lives in `SequenceManager` and is released by `stop`, on quit and on shutdown; `PortalTravelListener` asks it before applying group rules. Sequences never write to `Settings` or `GroupManager`.

Help lives in `HelpCommand`: change an entry in its `TOPICS` list (short text, A to Z inside a topic); `HelpCommandTest` checks page length, order and the Previous/Next controls. The words `/pd create` accepts are `CREATE_OPTIONS` in `GenerateCommands`, used for parsing and Tab completion alike, and where portals stand is only ever `/pd set mode`.

## Testing on a real server

Bots cannot see, so these checks cover coordinates, blocks, timing and server behaviour, not how things look.

```
powershell -File scripts\setup-test-server.ps1 -AcceptEula
cd bot-test
npm install --ignore-scripts
node layout-test.js
node reveal-test.js
node names-test.js
node simplify-test.js
node groups-test.js
node nether-link-test.js
node sequence-test.js
node sky-army-test.js
```

`setup-test-server.ps1` creates an offline-mode Paper server bound to 127.0.0.1, next to the repository (not inside it), and installs the jar. Never copy those settings to a server other people can reach. `names-test.js` covers named portals, removal and Tab completion order through a real client. `groups-test.js` covers groups, preparing, hiding and the staggered reveal as seen by other bots, Nether travel on and off, one Nether partner per portal with the way back, removal, restart and migration of old group data. `simplify-test.js` covers facing, the radius boundary and margin, mixed mode, altitude and world-y limits, removed commands, migration, help, TNT and vanilla Nether travel. `nether-link-test.js` covers single portals and their Nether partners (same Nether coordinates after a rebuild), the cumulative TNT limit and A to Z listings. `sequence-test.js` covers the sequence commands, settings, tab completion, nether-arrival and tnt-strike; `sky-army-test.js` runs sky-army with 10, 20 and 40 bot players, an observer client, widening, shared portals and stop. See [`bot-test/README.md`](../bot-test/README.md) for what each script checks.

Keep test servers and worlds outside this repository.

## Releasing

1. Update `version` in `build.gradle.kts`.
2. `./gradlew clean build`, then check the version inside the jar's `plugin.yml`.
3. Tag and publish:

```bash
gh release create vX.Y.Z build/libs/PortalDirector-X.Y.Z.jar --title "PortalDirector X.Y.Z" --notes-file <notes>
```

4. Update the jar name in the README download and install sections.
