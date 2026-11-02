# Bot test

A local harness for PortalDirector. It is not part of the plugin and not needed to use it.

It starts the isolated Paper server created by `scripts/setup-test-server.ps1` (offline mode, **127.0.0.1 only**,
port 25590, next to the repository in `portal-director-server`), connects 30 Mineflayer bots and drives the plugin
from the server console.

```
gradlew build
powershell -File scripts\setup-test-server.ps1 -AcceptEula
cd bot-test
npm install --ignore-scripts
node run.js
```

Results go to `reports/latest.json` and the server console to `reports/server-console.log`.

What is checked, and how:

* Generation: the scene file, spacing and uniqueness are checked from the saved scene, and every frame and
  interior block is asked from the server itself (`execute unless block ...`).
* Joining, assignments, prepare, release, reset, reconnect, cancellation: through the bots' own position and
  teleport packets and the plugin's console output.
* Timing: how far apart the 30 clients received their teleport (local network, so this shows the server side
  is simultaneous, not what a remote player's ping would add).
* TNT: minecart counts through `execute if entity`, flight direction through `data get entity`.
* Restart persistence and safe removal: the server is stopped gracefully and started again; restored blocks are
  compared against the scene's block file.

What it cannot show: bots have no eyes. Nothing here proves how the scene looks, how smooth the walking looks
from a player's camera, the sound, or how it performs with real latency and real clients.
