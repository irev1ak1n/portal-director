# Troubleshooting

Start with `/pd diagnostics`. It shows the plugin version, the server, TPS and tick times, the counters and timings PortalDirector keeps, the scene writer, the database and arena status, and how many operations were cut off at the last start. `/pd diagnostics export` writes the same to a file without player names, which is safe to share. `/pd diagnostics reset` clears the timings.

| Symptom | Likely cause and fix |
|---|---|
| `/pd take ...` says it needs the database | `database.enabled` is false, or the file could not be opened. `/pd diagnostics` shows the reason. A damaged file is moved aside automatically |
| The log says operations were cut off | The server stopped while a create, clear or sequence ran. `/pd list` shows what exists, `/pd clear` puts the ground back |
| `/pd take start` says to prepare first | Start only runs a take prepared since the server started. `/pd take prepare <name>` |
| Prepare lists offline players | They are skipped. Assignments for them stay in the take |
| Reset did not clear the portals | The scene held other portals when the take was prepared, so nothing was cleared. Use the names in the reply with `/pd remove` |
| `/pd take arena` needs WorldEdit | Install WorldEdit or FastAsyncWorldEdit and restart. `/pd diagnostics` should show it |
| Arena restore is refused | A job or sequence is running, a portal stands inside the box, or the box is above `arena.max-blocks`. The message says which |
| `/pd preview show` is refused | Only the random layout is previewed, or the settings can not make portals (the message says which) |
| The preview shows nothing | No portal fits with the current settings. Try a smaller radius or another placement |
| Preview blocks stay after something odd | `/pd preview hide`, or change world, or rejoin. The world itself was never changed |
| Chat colours look wrong after editing `messages.*` | Each style needs `<text>` (`command` needs `<usage>` and `<what>`). A broken style is reported in the log and the default is used |
| Builds feel slow on a busy server | Lower `generate.blocks-per-tick`, build in several runs, and look at a Spark profile. See [PERFORMANCE.md](PERFORMANCE.md) |

Where things live: `plugins/PortalDirector/` holds `config.yml`, `sequences.yml`, `groups.yml`, `scenes/`, `arenas/` and `portaldirector.db`.
