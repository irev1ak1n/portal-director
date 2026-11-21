# Takes, arena restore and preview

Everything here is optional. Nothing in this page changes how `/pd create`, groups or the three sequences work.

## Takes

A take is a saved recording setup, so you can run the same scene again and again. Takes are stored in the SQLite database (see [DATABASE.md](DATABASE.md)). If the database is off, the take commands say so and everything else keeps working.

A take holds: the scene and world, the portal formation settings (`generate.*`), the sequence and its settings, the group and who is assigned to which portal, a target (where you stood when you saved it), and, if you asked for them, each player's position.

| Command | What it does |
|---|---|
| `/pd take save <name> [sequence <name>] [positions]` | Write the current setup down. Run it as a player: your position and the way you face become the target. Saving never starts a sequence and never moves anyone. Saving a name again replaces the take and keeps its creation time |
| `/pd take list` | The saved takes, A to Z |
| `/pd take info <name>` | What the take holds and its last three runs |
| `/pd take prepare <name>` | Checks the take and puts its settings and group assignments back. It starts nothing and moves nobody. It names anything missing (an offline player, a portal that is no longer in the scene) |
| `/pd take stage <name>` | Moves the saved players to their saved positions. Only when you ask for it |
| `/pd take start <name>` | Runs the saved sequence at the saved target. It refuses unless the take was prepared since the server started |
| `/pd take reset <name>` | Stops the sequence, TNT and staging (players go back where they stood) and clears what the take built |
| `/pd take delete <name>` | Forgets the take. Scenes, groups and portals stay |

Take names are 1 to 32 letters, numbers, `-` and `_`, and are not case sensitive.

### Replay workflow

1. Build your scene, set up the group and the sequence settings the way you want them.
2. `/pd take save scene1 sequence sky-army positions`
3. `/pd take prepare scene1`, then `/pd take stage scene1` if you want everyone back at their marks.
4. `/pd take start scene1`
5. Cut. `/pd take reset scene1`.
6. Repeat from step 3.

### What reset clears

Reset removes the portals the run built, but only when the scene was empty when the take was prepared (then it is the same as `/pd clear`). If the scene already held portals, nothing is cleared automatically: the reply lists the portals the run built and you remove them with `/pd remove <name>`. That keeps your earlier portals safe.

### Safety rules

- One take is prepared or running at a time. Preparing or deleting a running take is refused.
- Starting needs a prior prepare. A second start needs a reset first.
- A take that was running when the server stopped is marked INTERRUPTED at the next start and reported in the log. Scenes are saved as they go, so `/pd list` shows what exists and `/pd clear` puts the ground back.
- Offline players, a missing world, a deleted scene or group and invalid assignments are reported by prepare, and the take is left as it is.
- Nether Arrival keeps its rule: nobody is moved automatically.

## Arena restore (WorldEdit or FastAsyncWorldEdit)

Restores a box of blocks between takes, for example a set that players build in. It needs **WorldEdit** or **FastAsyncWorldEdit** on the server. PortalDirector works the same without either; the arena commands then say that one is missing.

### Setup

1. Install FastAsyncWorldEdit (or WorldEdit) for your Paper version and restart.
2. `/pd diagnostics` shows `Arena restore: FastAsyncWorldEdit <version>`.
3. `/pd take arena set <take> <x1 y1 z1 x2 y2 z2>` stores the box with the take.
4. `/pd take arena save <take>` stores the blocks as they are now, in `plugins/PortalDirector/arenas/<take>.schem`.
5. Between takes: `/pd take arena restore <take>` explains what will happen, then `/pd take arena restore <take> confirm` does it.

Also: `/pd take arena info <take>` and `/pd take arena clear <take>` (the saved file stays).

### Limits and safety

- Needs the permission `portaldirector.arena` (op by default).
- The box is limited by `arena.max-blocks` (250000 by default). Never a whole world.
- Restore is destructive and always asks for `confirm` first.
- Refused while portals are being built or removed, while a sequence runs, while another arena operation runs, and while any portal of the scene stands inside the box (so it can not race PortalDirector's own block restore). `/pd clear` first.
- Entities are not copied or removed.

### What is verified

The box rules (size, height, overlap with portals) are unit tested. The plugin was loaded and tested live without WorldEdit installed. **The WorldEdit and FastAsyncWorldEdit calls themselves were compiled against WorldEdit 7.4.6 but were not run against a real FAWE server.** Before relying on it, test once on a copy of your server: set a small box, save, change blocks, restore with `confirm`, and check the blocks came back.

## Preview

`/pd preview show [x z]` shows where random portals would go, to you only.

| Command | What it does |
|---|---|
| `/pd preview show [x z]` | Works out the portals with your current settings around you (or the given x z) and sends them to your client as fake blocks |
| `/pd preview hide` | Puts the real blocks back on your screen |
| `/pd preview status` | What you are looking at |

- It is a player command, with the same permission as `/pd create`.
- The world is never changed, nothing is saved and no entity is created. Nobody else sees it.
- Only the random layout is previewed. For grid, square and wave, `/pd create` is the preview (and `/pd clear` undoes it).
- It follows the same rules as building: radius and its margin, spacing, placement, altitude, facing. Random layouts are random, so building afterwards gives different spots.
- It is removed when you hide it, show a new one, change world, leave, or the plugin stops. Real blocks are sent back where that still makes sense.
- Limits: `preview.max-portals` (100) and `preview.max-blocks` (8000) in `config.yml`. Planning is spread over ticks, at most 100 ticks.
- If the world changes under the preview (someone builds there), the fake blocks can be overwritten by the real ones for that spot. Hide and show again.
- It uses Paper's own `Player#sendBlockChanges`, not PacketEvents. See the decision in [ENGINEERING_AUDIT.md](ENGINEERING_AUDIT.md).

Verified: unit tests for the state keeping, and a live test with two real bot clients (the admin's client received obsidian and portal blocks, the other client received none, hide and rejoin left nothing behind). Bots cannot see, so this checks what the client was told, not how it looks.
