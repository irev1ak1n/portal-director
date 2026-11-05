# Bot test

A local harness for PortalDirector. It is not part of the plugin and not needed to use it.

It starts the isolated Paper server created by `scripts/setup-test-server.ps1` (offline mode, **127.0.0.1 only**,
port 25591, next to the repository in `portal-director-test-server`), connects a few Mineflayer bots and drives the plugin
from the server console.

```
gradlew build
powershell -File scripts\setup-test-server.ps1 -AcceptEula
cd bot-test
npm install --ignore-scripts
node layout-test.js  # grid, square and wave layouts: coordinates, spacing, facings, TNT, restore
node reveal-test.js  # delayed reveal: timing, order, stop, restart in the middle
node names-test.js   # named portals: create in four directions, remove by name and coordinates, tab order, old scene files
node groups-test.js   # groups, prepare, hiding and reveal timing, Nether travel and pairing, restart, old group data
node nether-link-test.js # single portals and their Nether partners, same Nether coordinates after rebuild, TNT launch limit, A-Z listings
node simplify-test.js # facing, exact radius with its automatic 5% margin, mixed mode, altitude and world-y, removed commands, migration, help, TNT, Nether travel
```

Results go to `reports/<suite>-latest.json` and the server console to `reports/<suite>-console.log`.

What is checked, and how:

* Generation: the scene file, spacing and uniqueness are checked from the saved scene, and every frame and
  interior block is asked from the server itself (`execute unless block ...`).
* Vanilla Nether travel: a bot stands in a generated portal and its dimension is read.
* TNT: minecart counts through `execute if entity`, flight direction through `data get entity`.
* Restart persistence and safe removal: the server is stopped gracefully and started again; restored blocks are
  compared against the scene's block file.

What it cannot show: bots have no eyes. Nothing here proves how the scene looks, the sound, or how it performs with
real latency and real clients.
