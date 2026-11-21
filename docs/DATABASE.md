# Database

PortalDirector keeps takes and a short history of long operations in one SQLite file, `plugins/PortalDirector/portaldirector.db`. Scenes, groups, settings and everything else stay in the same YAML and block files as before, so nothing about them changed and old files still load.

## What is in it

- `takes` and their settings, assignments and positions
- `take_runs`: one row per prepare/start of a take
- `operations`: the last 200 long operations (create, clear, sequence) with how they ended

## Setting it up

Nothing to do. It is on by default and uses the SQLite driver that Paper already bundles, so there is no extra download. `database.enabled: false` in `config.yml` turns it off; takes then say they need it, and everything else works.

## How it behaves

- One connection on one background thread. The main thread never waits for the disk for the operation history; take commands wait a few milliseconds for a small query.
- Every change runs in a transaction. Queries use prepared statements, foreign keys cascade, and constraints limit names, statuses and sizes.
- Schema versions are applied in order inside a transaction (`PRAGMA user_version`). A step that fails is undone and the database is switched off with the reason in the log and in `/pd diagnostics`.
- A database made by a **newer** PortalDirector is left alone and switched off, so an old jar never damages a newer file.
- A damaged file (fails its integrity check or can not be opened) is moved to `portaldirector.db.corrupt-<time>` and a new one is created. The damaged file is kept for you to inspect.
- On a clean stop the write-ahead log is folded into the file.

## Crash recovery

Long operations are recorded when they start and when they end. At the next start, anything still marked running is marked INTERRUPTED and reported once in the log, and runs of takes that were running are marked INTERRUPTED too. `/pd diagnostics` shows how many were cut off at the last start. Scenes are saved as they go, so `/pd list` shows what exists and `/pd clear` puts the ground back.

## Migration of old data

There is nothing to migrate: takes and the history are new, and no legacy file is read, changed or deleted by the database. Scene files are still written atomically (temporary file, then move), blocks before yml, so a crash can not leave a half written file.

## Backup

Copy `portaldirector.db` while the server is stopped (or copy the `.db`, `-wal` and `-shm` files together). The file never holds passwords. It holds player names and UUIDs of take assignments, so do not publish it. `*.db` is ignored by git in this repository.

## Tests

`DatabaseTest`, `TakeRepositoryTest` and `JournalTest` cover: creating, reopening, a newer schema, a corrupt file, a failing migration, a later migration on existing data, data after an unclean stop, one thread for all tasks, a failed write rolling back, closing with queued work, size limits and trimming.
