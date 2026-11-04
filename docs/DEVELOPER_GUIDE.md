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

The unit tests use MockBukkit and plain JUnit. They cover the layout planners, reveal ordering, spacing, terrain rules, portal names (including old scene files without names), settings migration, scene saving and block restoring. The report is in `build/reports/tests/test/index.html`.

## Code layout

| Package | What is in it |
|---|---|
| `command` | `/pd` and its subcommands, help text, tab completion |
| `gen` | Layout planners (random, grid, square, wave), reveal orders, the generation job |
| `scene` | Remembered portals (with their names) and block log, saving to disk, safe removal |
| `group` | Groups, join blocks, portal assignment |
| `entrance` | Preparing and releasing players, release patterns |
| `tnt` | TNT minecart launching and waves |

`Settings.java` is the single list of options behind `/pd set` and `/pd settings`.

Portals keep an internal numeric id (assignments and block records use it) and a name that people type. `Scene.addPortal` gives generated portals `portal-<id>`; `PortalCommands` handles `/pd create portal` and `/pd remove`. All Tab suggestions go through one alphabetical sort in `PdCommand.onTabComplete`, and `subs` there is a sorted map, so a new command only needs to be added once. Keep the lines of help pages in A to Z order as well.

## Testing on a real server

Bots cannot see, so these checks cover coordinates, blocks, timing and server behaviour, not how things look.

```
powershell -File scripts\setup-test-server.ps1 -AcceptEula
cd bot-test
npm install --ignore-scripts
node layout-test.js
node reveal-test.js
node names-test.js
```

`setup-test-server.ps1` creates an offline-mode Paper server bound to 127.0.0.1, next to the repository (not inside it), and installs the jar. Never copy those settings to a server other people can reach. `names-test.js` covers named portals, removal and Tab completion order through a real client. See [`bot-test/README.md`](../bot-test/README.md) for what each script checks.

Keep test servers and worlds outside this repository.

## Releasing

1. Update `version` in `build.gradle.kts`.
2. `./gradlew clean build`, then check the version inside the jar's `plugin.yml`.
3. Tag and publish:

```bash
gh release create vX.Y.Z build/libs/PortalDirector-X.Y.Z.jar --title "PortalDirector X.Y.Z" --notes-file <notes>
```

4. Update the jar name in the README download and install sections.
