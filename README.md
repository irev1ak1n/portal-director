# PortalDirector

PortalDirector is a Minecraft server plugin for content creators. It builds whole fields of Nether portals in one command, lets you reveal them one by one like a movie shot, walks groups of players out of them at the same moment, and can send TNT minecarts out of the same portals.

## Download

**[Download PortalDirector from GitHub Releases](https://github.com/irev1ak1n/portal-director/releases/latest)**

On that page, scroll to **Assets** and click `PortalDirector-1.0.0.jar`. That file is the whole plugin. You don't need to clone the repository, install Gradle or compile anything.

## Who installs it?

Only the **server owner or admin** installs PortalDirector, once, on the server. **Players don't download or install anything.** They join as normal with a regular Minecraft Java client.

## What you need

- A **Paper** server for **Minecraft 26.3** (get Paper at [papermc.io](https://papermc.io/)). It is built and tested on Paper 26.3 (build 231) and will not run on other Minecraft versions. Spigot and Folia are not supported.
- **Java 25** on the server (Paper 26.x needs it; most hosting panels let you pick it).

## Install it

1. Download `PortalDirector-1.0.0.jar` (see above).
2. Put it in your server's **`plugins`** folder:
   - **Hosting panel** (for example Pterodactyl or Apex): open the **File Manager**, open the `plugins` folder, click **Upload** and choose the jar.
   - **Your own computer or server:** copy the file into the `plugins` folder next to `paper.jar`.
3. **Restart** the server (use the Restart button, or type `stop` in the console and start it again).
4. Check it worked: run `/plugins`. **PortalDirector** should be green. Then run `/pd help`.

PortalDirector makes its own settings in `plugins/PortalDirector/`. Updating later: stop the server, replace the old jar with the new one and start again. Your settings, portals and groups are kept.

## Quick start

You need to be an operator (see [Permissions](#permissions)). Stand somewhere open and type:

```
/pd generate 30        build 30 scattered portals around you
/pd list               the id and coordinates of every portal
/pd clear              remove everything and put the ground back
```

For an organised formation, pick a layout first, then generate:

```
/pd set layout grid
/pd set grid rows 4
/pd set grid columns 5
/pd generate
```

Want a reveal instead of everything at once?

```
/pd set generation delayed
/pd set generation delay 0.5
/pd generate
```

Then let a group walk out together:

```
/pd group create team
/pd group block set emerald_block      players who step on an emerald block join
/pd group prepare team                 everybody waits in their own portal
/pd group release team                 all of them walk out at the same moment
```

`/pd help` explains everything in plain words, and Tab after `/pd` shows every command. To go to a portal, use Minecraft's own `/tp` with the coordinates from `/pd list`.

**Can't see the far portals?** The server only sends the land around you up to its view distance. Raise `view-distance` in `server.properties` or use a smaller formation. PortalDirector warns you after generating when this might be the problem.

## Features

- **Four layouts:** `/pd set layout random|grid|square|wave`.

| Layout | Settings | What you get |
|---|---|---|
| Random | `count`, `radius` | Portals scattered around you |
| Grid | `grid rows`, `grid columns` | A vertical wall, for example 4 rows by 5 columns = 20 portals |
| Square | `square per-side`, `square layers` | A square perimeter, repeated in stacked layers |
| Wave | `wave rows`, `wave per-row` | Rows that rise and fall like a wave |

- **Real Nether portals.** Every portal is a 4x5 obsidian frame with a purple interior. Anyone can still walk into one and travel to the Nether and back like normal.
- **Cinematic reveal.** Portals appear one by one, left to right, bottom to top, clockwise and more, with an optional sound and particles. `/pd stop` cancels safely.
- **Groups and entrances.** Assign players to portals, hold them inside, then release them all at once, in waves, or around a square.
- **TNT minecart sequences.** Launch a minecart out of every portal, or in timed waves, with safe and real modes.
- **Scene management.** PortalDirector remembers every portal and every block it changed, even after a restart. `/pd clear` puts the ground back.
- **Safe building.** Portals avoid water, steep ground and anything that looks player-built. Big builds are spread over many ticks so the server does not freeze.

### Layout options

| Setting | What it does |
|---|---|
| `/pd set spacing horizontal 8` / `vertical 5` | Gap between portals side by side / between rows and layers |
| `/pd set facing north\|south\|east\|west\|random` | Which way a grid or wave faces (where players and minecarts come out) |
| `/pd set facing inward\|outward` | Which way a square faces |
| `/pd set center here` / `150 100 -200` / `reset` | Where the formation is built (default: where you stand) |
| `/pd set mode ground\|air\|mixed` | Stand on the terrain, or float around the center height |

Natural blocks in the way (grass, stone, trees) are replaced and restored by `/pd clear`. If something a player built is in the way, nothing is built. `/pd generate partial` builds only the portals that fit.

### Cinematic reveal

| Command | What it does |
|---|---|
| `/pd set generation instant\|delayed` | Build everything at once, or one portal at a time |
| `/pd set generation delay 0.5` | Seconds between portals (0.05 to 60) |
| `/pd set generation order <order>` | Which portal appears next (table below) |
| `/pd set generation effects on` | A soft sound and particles as each portal appears (off by default) |
| `/pd stop` | Cancel a running reveal; portals already built stay |

Every portal is planned and checked before the first one appears, and each one is built whole, so you never get half a portal. Progress shows on the action bar (for example `12/50 portals created`).

| Order | Grid and wave | Square | Random layout |
|---|---|---|---|
| `auto` (default) | grid: bottom-to-top, wave: left-to-right | clockwise | scattered |
| `left-to-right` | column by column, as seen from in front | not available | west to east |
| `bottom-to-top` | row by row from the bottom | layer by layer from the bottom | lowest first |
| `top-to-bottom` | row by row from the top | layer by layer from the top | highest first |
| `clockwise` / `counterclockwise` | not available | around each layer | around the center |
| `random` | shuffled | shuffled | shuffled |

If you pick an order that does not fit the layout, `/pd generate` tells you why and which orders do.

## Commands

Base command: `/pd` (alias `/portaldirector`). Every subcommand has tab completion.

### Building portals

| Command | Description |
|---|---|
| `/pd generate [count] [ground\|air\|mixed] [add\|replace] [partial]` | Build the current layout. A count only applies to the random layout |
| `/pd list` | The id and coordinates of every portal |
| `/pd clear` | Remove all portals and restore the ground |
| `/pd stop` | Cancel a generation or removal that is running |
| `/pd portal create <x y z\|here\|~ ~ ~> <north\|south\|east\|west>` | Build one portal at exact coordinates |
| `/pd portal remove <id>` | Take one portal down and restore its blocks |

`x y z` is the lower of the two bottom blocks inside the portal. The direction is the way players and minecarts come out. Add `force` at the end to override the player-built check. From the console, add the world name at the end.

### Settings

| Command | Description |
|---|---|
| `/pd settings [all]` | Show the current settings and how many portals the next `/pd generate` makes |
| `/pd set layout random\|grid\|square\|wave` | The formation |
| `/pd set grid rows 4` / `columns 5` | Grid size |
| `/pd set square per-side 5` / `layers 3` | Square size |
| `/pd set wave rows 4` / `per-row 5` | Wave size |
| `/pd set count 50` / `radius 100` | Random layout: how many portals and how far out |
| `/pd set spacing horizontal 8` / `vertical 5` | Gaps between portals |
| `/pd set facing <direction>` | Which way portals face |
| `/pd set center here` / `150 100 -200` / `reset` | Where the formation is centered |
| `/pd set mode ground\|air\|mixed` | Terrain or floating |
| `/pd set generation ...` | Reveal settings (see above) |
| `/pd set staging here` / `clear` | Hold prepared players at your position instead of at their portals |
| `/pd set <name> <value>` | Any other option; `/pd settings all` lists them |
| `/pd reload` | Reload `config.yml` |
| `/pd help [portals\|layouts\|players\|tnt]` | Plain-language help |

### Groups and entrances

| Command | Description |
|---|---|
| `/pd group create <name>` | New group |
| `/pd group block set <block>` | Stepping on this block joins the group |
| `/pd group info [name]` | Members and their portals |
| `/pd group add\|remove [name] <player>` | Manual membership |
| `/pd group assign <name> auto [fill]` | Portal 1 to player 1, and so on (`fill` keeps manual choices) |
| `/pd group assign <name> <portal> <player>` | Assign one slot by hand |
| `/pd group assign <name> swap <a> <b>` / `clear [portal]` | Rearrange |
| `/pd group assignments [name]` | Who is at which portal |
| `/pd group prepare <name>` | Everyone waits inside their portal |
| `/pd group release <name> [pattern]` | Everyone walks out. Patterns: `all-at-once`, `clockwise`, `opposite-sides`, `side-by-side` (square layouts), or `staggered <secs> [players per wave]` |
| `/pd group reset <name>` | Cancel and send everyone back |
| `/pd group disband <name>` | Delete the group |

With only one group you can leave its name out. Players who disconnect while prepared are put back in their portal when they return.

### TNT minecarts

| Command | Description |
|---|---|
| `/pd tnt launch <all\|floating\|ground\|square\|side <dir>\|group\|ids>` | Send a minecart out of the chosen portals |
| `/pd tnt wave <waves> <seconds>` | Several launches in a row, for example `/pd tnt wave 5 20` |
| `/pd tnt status` | Are explosions on? How many minecarts? What is the limit? |
| `/pd tnt mode safe\|real` | Safe: minecarts fly but never explode. Real: they explode |
| `/pd tnt fuse 3` | Explode 3 seconds after launch (`off`: only when they crash) |
| `/pd tnt terrain-damage on\|off` | Explosions break blocks (off by default: they only hurt) |
| `/pd tnt limit 300` | Most minecarts alive at once |
| `/pd tnt speed 1.3` | Launch speed |
| `/pd tnt stop` | Stop launching; minecarts already out keep going |
| `/pd tnt clear` | Stop and remove every TNT minecart |

Nothing is ever launched unless you type a launch command.

## Permissions

**PortalDirector's administrative commands require permission.** By default, server operators can generate and remove portals, manage groups, and launch TNT sequences. Regular players don't need OP to join the server or step on a group's configured joining block.

**Give a trusted creator OP:** the server owner can open their Minecraft hosting panel's **Console** and type `op PlayerName` (for example, `op SpokeIsHere`), **without a slash**. On a self-hosted server, use its server console. This does not require PowerShell when using a hosting panel. **OP grants broad server powers**, so reserve it for trusted administrators.

**Prefer limited access?** With [LuckPerms](https://luckperms.net/), the server admin can grant only the necessary PortalDirector permissions. For example, run `lp user PlayerName permission set portaldirector.generate true` in the server console to allow portal generation without full OP. Grant `portaldirector.group`, `portaldirector.tnt`, or `portaldirector.scene` separately as needed. The table below lists the available permission nodes.

Operators have everything by default. Give players only the parts you want them to have.

| Permission | Default | Grants |
|---|---|---|
| `portaldirector.admin` | op | Everything below |
| `portaldirector.generate` | op | `/pd generate`, `set`, `settings`, `list`, `portal`, `stop` |
| `portaldirector.group` | op | `/pd group` (assignments, prepare, release) |
| `portaldirector.tnt` | op | `/pd tnt` |
| `portaldirector.scene` | op | `/pd clear` |

`/pd help` is available to everyone.

## Notes and limits

- Entrances move players with teleports plus a short walk. A player can interrupt it, and how smooth it looks on camera depends on their ping.
- Players waiting at their portals are visible to everyone. Use `/pd set entrance.staging-invisible on` or a staging area to hide them.
- Generating very large areas loads many chunks; the limit is `generate.max-chunks`.
- Normal Nether travel through generated portals works. To make them decoration only: `/pd set entrance.block-vanilla-travel on`.

## For developers

Building from source, running the tests and the test server are covered in the [Developer Guide](docs/DEVELOPER_GUIDE.md). Server owners don't need it.
