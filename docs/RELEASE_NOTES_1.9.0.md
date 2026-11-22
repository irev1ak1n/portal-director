## PortalDirector 1.9.0

New and optional. Existing commands, config, scenes and groups work as before. Replace the jar and start the server.

**New**
- `/pd take save|list|info|prepare|stage|start|reset|delete`: save a recording setup and replay it. Saving never starts or moves anything.
- `/pd preview show|hide|status`: shows where random portals would go, only to you, as fake blocks. Nothing is built.
- `/pd diagnostics [export|reset]`: performance counters, database and arena status. `export` leaves out player names.
- `/pd take arena ...`: restore a box of blocks between takes. Needs WorldEdit or FastAsyncWorldEdit (optional; untested against a real FAWE server, so try it on a copy first).
- `messages.*` in `config.yml` restyles chat output with MiniMessage.

**Faster and safer**
- Scenes are saved in the background and files are written atomically.
- Planning large scenes is much faster (1000 portals: 82 ms to 15 ms in a benchmark).
- A cancelled build can no longer leave a chunk ticket behind.
- A small SQLite file (`portaldirector.db`) records takes and long operations, and reports operations cut off by a crash. Turn it off with `database.enabled: false`.

**Notes**
- Needs Paper 26.3 and Java 25.
- The preview shows the random layout only. Take reset clears portals automatically only when the scene was empty when the take was prepared.
- Details: docs/TAKES.md, docs/DATABASE.md, docs/PERFORMANCE.md.
