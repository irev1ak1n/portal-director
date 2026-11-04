# PortalDirector

PortalDirector is a Minecraft server plugin for content creators. It builds whole fields of Nether portals in one command, lets you reveal them one by one like a movie shot, and can send TNT minecarts out of the same portals.

## Download

**[Download PortalDirector from GitHub Releases](https://github.com/irev1ak1n/portal-director/releases/latest)**

On that page, scroll to **Assets** and click `PortalDirector-1.3.0.jar`. That file is the whole plugin. You don't need to clone the repository, install Gradle or compile anything. Direct link: [PortalDirector-1.3.0.jar](https://github.com/irev1ak1n/portal-director/releases/download/v1.3.0/PortalDirector-1.3.0.jar).

## Who installs it?

Only the **server owner or admin** installs PortalDirector, once, on the server. **Players don't download or install anything.** They join as normal with a regular Minecraft Java client.

## What you need

- A **Paper** server for **Minecraft 26.3** (get Paper at [papermc.io](https://papermc.io/)). It is built and tested on Paper 26.3 (build 231) and will not run on other Minecraft versions. Spigot and Folia are not supported.
- **Java 25** on the server (Paper 26.x needs it; most hosting panels let you pick it).

## Install it

1. Download `PortalDirector-1.3.0.jar` (see above).
2. Put it in your server's **`plugins`** folder:
   - **Hosting panel** (for example Pterodactyl or Apex): open the **File Manager**, open the `plugins` folder, click **Upload** and choose the jar.
   - **Your own computer or server:** copy the file into the `plugins` folder next to `paper.jar`.
3. **Restart** the server (use the Restart button, or type `stop` in the console and start it again).
4. Check it worked: run `/plugins`. **PortalDirector** should be green. Then run `/pd help`.

PortalDirector makes its own settings in `plugins/PortalDirector/`. Updating later: stop the server, delete the old jar, put the new one in and start again. Your settings and portals are kept: old setting names are renamed automatically and saved scenes load unchanged (see [Upgrading](#upgrading)).

## Quick start

You need to be an operator (see [Permissions](#permissions)). Stand somewhere open and type:

```
/pd create 30          build 30 scattered portals around you
/pd list               the name and coordinates of every portal
/pd clear              remove everything and put the ground back
```

For an organised formation, pick a layout first, then create it:

```
/pd set layout grid
/pd set facing north
/pd create
```

Want a reveal instead of everything at once?

```
/pd set generation delayed
/pd set generation delay 0.5
/pd create
```

One portal with a name you choose, and taking it down again:

```
/pd create portal EntranceA here north
/pd remove EntranceA
```

Then send TNT minecarts out of them:

```
/pd tnt mode safe          flies, but nothing explodes
/pd tnt launch all
```

`/pd help` has short clickable pages that explain everything in plain words, and Tab after `/pd` shows every command (always in alphabetical order). To go to a portal, use Minecraft's own `/tp` with the coordinates from `/pd list`.

**Can't see the far portals?** The server only sends the land around you up to its view distance. Raise `view-distance` in `server.properties` or use a smaller formation. PortalDirector warns you after creating when this might be the problem.

## Features

- **Four layouts:** `/pd set layout random|grid|square|wave`.

| Layout | Settings | What you get |
|---|---|---|
| Grid | `grid rows`, `grid columns` | A vertical wall, for example 4 rows by 5 columns = 20 portals |
| Random | `count` | Portals scattered around the center |
| Square | `square per-side`, `square layers` | A square perimeter, repeated in stacked layers |
| Wave | `wave rows`, `wave per-row` | Rows that rise and fall like a wave |

- **Real Nether portals.** Every portal is a 4x5 obsidian frame with a purple interior. Anyone can still walk into one and travel to the Nether and back like normal.
- **A strict radius.** Every frame block stays within the radius you set (plus an automatic 5%), north, south, east and west of the center.
- **Named portals.** Every portal has a name you can type: generated ones are `portal-1`, `portal-2` and so on, and you choose the names of single portals. Names are not case sensitive.
- **Cinematic reveal.** Portals appear one by one, left to right, bottom to top, clockwise and more, with an optional sound and particles. `/pd stop` cancels safely.
- **TNT minecart sequences.** Launch a minecart out of every portal, or in timed waves, with safe and real modes. Minecarts come out the way the portal faces.
- **Scene management.** PortalDirector remembers every portal and every block it changed, even after a restart. `/pd clear` or `/pd remove` puts the ground back.
- **Safe building.** Portals avoid water, steep ground and anything that looks player-built. Big builds are spread over many ticks so the server does not freeze.

### Settings that matter

These are all changed with `/pd set <name> <value>`. `/pd settings` shows the ones that apply to your current layout first.

| Setting | What it changes in the world |
|---|---|
| `/pd set center here` / `150 100 -200` / `reset` | Where the formation is built (default: where you stand) |
| `/pd set count 30` | Random layout: how many portals |
| `/pd set facing north` | Which way portals face, and which way TNT minecarts come out (see below) |
| `/pd set generation delay 0.5` | Delayed generation: seconds between portals |
| `/pd set mode ground\|air\|mixed` | Portals on the ground, floating, or both |
| `/pd set radius 100` | How far from the center portals may be north, south, east and west (an automatic 5% is added) |
| `/pd set spacing horizontal 8` | Least gap between portal frames side by side |
| `/pd set spacing vertical 5` | Least gap between portals above each other |

### Facing

`/pd set facing` is the only setting that decides which way a portal points. The frame turns to match (north and south portals run along the x axis, east and west portals along z), and TNT minecarts leave in the same direction.

| Value | For | Meaning |
|---|---|---|
| `north`, `south`, `east`, `west` | random, grid, wave | Every portal faces that way |
| `random` | random, grid, wave | Each portal picks one of the four directions |
| `inward`, `outward` | square | Portals face the middle of the square, or away from it |

A value the current layout can't use is refused with an explanation, for example `inward` for a grid. When you change the layout and the facing no longer fits, it switches to `inward` for a square and `random` for everything else and tells you. Single portals take the direction in the command: `/pd create portal Test here north`.

### Radius

`/pd set radius 100` is how far portals may spread north, south, east and west of the center. A fixed 5% margin is added automatically, so a radius of 100 really allows 105 blocks in each direction (`/pd settings` shows `Radius: 100 blocks (105 effective with automatic 5% margin)`). There is nothing else to configure.

The permitted area is a square: x from `center - 105` to `center + 105`, and the same for z. **Every block of the obsidian frame, including its outermost blocks, has to be inside it**, whichever way the portal faces. A portal's cleared walkway can reach up to three blocks further in the direction it faces.

- Random layout: portals are only placed where the whole frame fits. If fewer fit, the message says how many and what stopped the rest.
- Grid, wave and square: if the formation reaches further than the permitted area, nothing is built or removed and the message names the radius it needs, for example `/pd set radius 60`.

### Heights

These four settings are optional. The defaults suit most worlds.

| Setting | Default | What it means |
|---|---|---|
| `/pd set altitude-min 15` | 15 | Lowest height of a floating portal, **above the ground** nearby |
| `/pd set altitude-max 60` | 60 | Highest height of a floating portal, **above the ground** nearby |
| `/pd set world-y-min -64` | -64 | Lowest **Minecraft Y coordinate** any portal frame may use |
| `/pd set world-y-max 320` | 320 | Highest **Minecraft Y coordinate** any portal frame may use |

Altitude is relative: with the ground at Y 70, `altitude-min 15` starts floating portals at about Y 85 and `altitude-max 60` lets them reach about Y 130. World Y is a safety limit on the real coordinates, not a height above the ground. Floating portals must satisfy both, whole frames stay inside the world Y range, and ground portals stay on the terrain surface.

A minimum can't be above its maximum (the message tells you which one to change first). When no valid spot exists, for example `world-y-max 130` over ground at Y 70 with `altitude-min 15` and a frame 5 high, the build report names the reason (`above world-y-max`).

```
/pd set mode air
/pd set radius 80
/pd create 30
```

```
/pd set altitude-min 20
/pd set altitude-max 50
```

```
/pd set world-y-min 80
/pd set world-y-max 180
```

### Mode: ground, air and mixed

- **ground:** each portal stands on the natural terrain surface. PortalDirector looks for a suitable spot nearby when a position is water, a cliff or blocked, never builds inside terrain, never leaves a portal hanging above the ground, and doesn't touch what players built.
- **air:** portals float, between `altitude-min` and `altitude-max` above the ground.
- **mixed:** about half floating and half on the ground, with no extra setting. (Grid, wave and square layouts always stand their lowest row on the ground in `ground` and `mixed` mode.)

Natural blocks in the way (grass, stone, trees) are replaced and restored by `/pd clear`. If something a player built is in the way, nothing is built. `/pd create skip-blocked` builds only the portals that fit.

### Creating portals

`/pd create` builds the layout you chose with `/pd set`. It has no placement options: where portals stand is the setting `/pd set mode ground|air|mixed`.

| Command | What it does |
|---|---|
| `/pd create` | Build the selected layout around you (grid, square and wave work out their own count) |
| `/pd create 30` | Random layout only: build 30 portals |
| `/pd create more` | Keep the existing portals and build another set |
| `/pd create replacement` | Remove the existing portals first, then build |
| `/pd create skip-blocked` | Build only the portals that fit and skip blocked positions |

Options can be combined with a count in any order, for example `/pd create 30 more skip-blocked`. `more` and `replacement` can't be used together.

### Single named portals

```
/pd create portal EntranceA here north
/pd create portal EntranceB 150 100 -200 east
/pd remove EntranceA
/pd remove 150 100 -200
```

- The name comes first. It starts with a letter, may use letters, digits, `-` and `_`, and is up to 24 characters. A name that is already taken (in any capitalisation) is refused and tells you where the existing portal is.
- `here` uses the block you stand in. The direction is the way minecarts come out: `north`, `south`, `east` or `west`.
- **The coordinates are the anchor:** the lower of the two bottom blocks inside the doorway, the block a player's feet are in when standing in it. The obsidian floor is the block below it, so the frame reaches from `y-1` to `y+3`. North and south portals are 4 blocks wide along x, east and west portals along z. `/pd list` shows the anchor of every portal.
- `/pd remove <x> <y> <z>` only removes the portal whose anchor is exactly that block. Coordinates that match nothing remove nothing, and the message names the closest portal.
- A portal is refused when it is closer than one empty block to another portal, outside the build height or world border, or would replace blocks that look player-built (add `force` at the end to override that last check). From the console add the world name: `/pd create portal EntranceA 0 100 0 north world`.
- Named portals work everywhere generated ones do: TNT (`/pd tnt launch EntranceA`), saving, restarts and restoring the ground.

### Cinematic reveal

| Command | What it does |
|---|---|
| `/pd set generation delay 0.5` | Seconds between portals (0.05 to 60) |
| `/pd set generation effects on` | A soft sound and particles as each portal appears (off by default) |
| `/pd set generation instant\|delayed` | Build everything at once (default), or one portal at a time |
| `/pd set generation order <order>` | Which portal appears next (table below) |
| `/pd stop` | Cancel a running reveal; portals already built stay |

Every portal is planned and checked before the first one appears, and each one is built whole, so you never get half a portal. Progress shows on the action bar (for example `12/50 portals created`). Portals from a stopped or interrupted reveal stay tracked: you can list and remove them like any other.

| Order | Grid and wave | Square | Random layout |
|---|---|---|---|
| `auto` (default) | grid: bottom-to-top, wave: left-to-right | clockwise | scattered |
| `bottom-to-top` | row by row from the bottom | layer by layer from the bottom | lowest first |
| `clockwise` / `counterclockwise` | not available | around each layer | around the center |
| `left-to-right` | column by column, as seen from in front | not available | west to east |
| `random` | shuffled | shuffled | shuffled |
| `top-to-bottom` | row by row from the top | layer by layer from the top | highest first |

If you pick an order that does not fit the layout, `/pd create` tells you why and which orders do.

## Commands

Base command: `/pd` (alias `/portaldirector`). Every command and every Tab suggestion is listed A to Z.

### Main commands

| Command | Description |
|---|---|
| `/pd clear` | Remove all portals and restore the ground |
| `/pd create [count] [more\|replacement] [skip-blocked]` | Build the current layout with your `/pd set` settings. A count only applies to the random layout |
| `/pd create portal <name> here <direction> [force]` | Build one named portal where you stand |
| `/pd create portal <name> <x> <y> <z> <direction> [world] [force]` | Build one named portal at exact coordinates |
| `/pd group ...` | A roster of players and a join block (see below) |
| `/pd help [page or topic]` | Short, clickable help pages (see "Built-in help") |
| `/pd list [page]` | The name and anchor coordinates of every portal |
| `/pd reload` | Reload `config.yml` |
| `/pd remove <name>` | Take one portal down and restore its blocks |
| `/pd remove <x> <y> <z>` | The same, by the anchor coordinates shown in `/pd list` |
| `/pd set ...` | Change a setting (see below) |
| `/pd settings [tnt\|all]` | Show the settings for your layout first; `tnt` and `all` show more |
| `/pd stop` | Cancel a creation or removal that is running |
| `/pd tnt ...` | TNT minecarts (see below) |

### Settings

| Command | Description |
|---|---|
| `/pd set altitude-max 60` / `altitude-min 15` | Floating portals: highest and lowest height above the ground |
| `/pd set center here` / `150 100 -200` / `reset` | Where the formation is centered |
| `/pd set count 50` | Random layout: how many portals |
| `/pd set facing <direction>` | Which way portals face and TNT launches (`inward`/`outward` for the square) |
| `/pd set generation ...` | Reveal settings (see above) |
| `/pd set grid rows 4` / `columns 5` | Grid size |
| `/pd set layout grid\|random\|square\|wave` | The formation |
| `/pd set mode ground\|air\|mixed` | Terrain, floating, or half and half |
| `/pd set radius 100` | How far from the center (an automatic 5% is added) |
| `/pd set spacing horizontal 8` / `vertical 5` | Gaps between portals |
| `/pd set square per-side 5` / `layers 3` | Square size |
| `/pd set wave rows 4` / `per-row 5` | Wave size |
| `/pd set world-y-max 320` / `world-y-min -64` | Highest and lowest Minecraft Y any portal frame may use |
| `/pd set <name> <value>` | Any other option; `/pd settings all` lists them |

### Groups

A group is a list of players. Players who step on the group's join block are added to it automatically.

| Command | Description |
|---|---|
| `/pd group add [name] <player>` | Add a player by hand |
| `/pd group block set <block>` | Stepping on this block joins the group |
| `/pd group create <name>` | New group |
| `/pd group disband <name>` | Delete the group |
| `/pd group info [name]` | Who is in the group |
| `/pd group list` | Every group and its join block |
| `/pd group remove [name] <player>` | Take a player out |

With only one group you can leave its name out.

### TNT minecarts

| Command | Description |
|---|---|
| `/pd tnt clear` | Stop and remove every TNT minecart |
| `/pd tnt fuse 3` | Explode 3 seconds after launch (`off`: only when they crash) |
| `/pd tnt launch <all\|floating\|ground\|side <dir>\|square\|portal names>` | Send a minecart out of the chosen portals, for example `/pd tnt launch EntranceA,EntranceB` |
| `/pd tnt limit 300` | Most minecarts alive at once |
| `/pd tnt mode safe\|real` | Safe: minecarts fly but never explode. Real: they explode |
| `/pd tnt speed 1.3` | Launch speed |
| `/pd tnt status` | Are explosions on? How many minecarts? What is the limit? |
| `/pd tnt stop` | Stop launching; minecarts already out keep going |
| `/pd tnt terrain-damage on\|off` | Explosions break blocks (off by default: they only hurt) |
| `/pd tnt wave <waves> <seconds>` | Several launches in a row, for example `/pd tnt wave 5 20` |

Nothing is ever launched unless you type a launch command.

### Built-in help

`/pd help` shows the five-step start (`/pd set`, `/pd create`, `/pd list`, `/pd remove`, `/pd clear`) on page 1. Every page ends with **Previous** and **Next** controls you can click, and clicking a command puts an example into your chat box.

| Command | Shows |
|---|---|
| `/pd help` or `/pd help 1` | Start here; `/pd help 2`, `3` and so on go page by page |
| `/pd help create` | Creating portals and the `more`, `replacement` and `skip-blocked` options |
| `/pd help examples` | Ready-made recipes: 30 floating portals, different heights, a world Y range, a wall |
| `/pd help group` | Groups |
| `/pd help layouts` | Grid, random, square and wave |
| `/pd help portals` | Single named portals |
| `/pd help reveal` | The cinematic delay |
| `/pd help scenes` | Listing, removing and clearing |
| `/pd help set` | Every setting, in plain words, including the four height settings |
| `/pd help tnt` | TNT minecarts |

## Permissions

**PortalDirector's administrative commands require permission.** By default, server operators can generate and remove portals, manage groups, and launch TNT sequences. Regular players don't need OP to join the server or step on a group's configured joining block.

**Give a trusted creator OP:** the server owner can open their Minecraft hosting panel's **Console** and type `op PlayerName` (for example, `op SpokeIsHere`), **without a slash**. On a self-hosted server, use its server console. This does not require PowerShell when using a hosting panel. **OP grants broad server powers**, so reserve it for trusted administrators.

**Prefer limited access?** With [LuckPerms](https://luckperms.net/), the server admin can grant only the necessary PortalDirector permissions. For example, run `lp user PlayerName permission set portaldirector.generate true` in the server console to allow portal generation without full OP. Grant `portaldirector.group`, `portaldirector.tnt`, or `portaldirector.scene` separately as needed. The table below lists the available permission nodes.

Operators have everything by default. Give players only the parts you want them to have.

| Permission | Default | Grants |
|---|---|---|
| `portaldirector.admin` | op | Everything below |
| `portaldirector.generate` | op | `/pd create`, `list`, `remove`, `set`, `settings`, `stop` |
| `portaldirector.group` | op | `/pd group` |
| `portaldirector.scene` | op | `/pd clear` |
| `portaldirector.tnt` | op | `/pd tnt` |

`/pd help` is available to everyone.

## Notes and limits

- Creating very large areas loads many chunks; the limit is `generate.max-chunks`.
- Normal Nether travel through generated portals works.
- The radius holds the obsidian frame. The cleared walkway in front of a portal can reach up to three blocks further.
- Grid, wave and square layouts treat `mixed` mode like `ground`: their lowest row stands on the ground and the rest stack above it.

## Upgrading

**From 1.2.0** (everything below is migrated automatically when the plugin starts; saved portals are untouched):

| Before | Now |
|---|---|
| `height-min`, `height-max` | `altitude-min`, `altitude-max` (same values) |
| `min-y`, `max-y` | `world-y-min`, `world-y-max` (same values) |
| `orientation` | removed: `/pd set facing` turns the frame |
| `air-ratio` | removed: `mixed` is about half and half |
| `slope` | removed: ground portals pick suitable terrain themselves |
| `facing auto` | `random` (`inward` for a square). `inward` and `outward` are for the square only |
| `entrance.*`, `/pd group prepare\|release\|reset\|assign\|assignments\|status`, `/pd set staging` | removed with the player entrance system |
| `groups.auto-assign`, `groups.announce-join` | removed |
| `/pd tnt launch group <name>` | removed (it used the assignments) |

The radius now holds whole frames inside a square (with an automatic 5% margin) instead of a circle that could reach a few blocks past it. Old player assignments in scene files are ignored and dropped the next time the scene is saved.

**From 1.1.0:** `/pd create add`, `replace` and `partial` are now `more`, `replacement` and `skip-blocked`, and `air`, `ground` and `mixed` are only set with `/pd set mode`. Old words are refused with a message that names the new one.

**From 1.0.0:** `/pd generate` is now `/pd create`, and `/pd portal create` and `/pd portal remove` are now `/pd create portal <name> ...` and `/pd remove`. Saved scenes load unchanged; their portals are named `portal-<number>`.

## For developers

Building from source, running the tests and the test server are covered in the [Developer Guide](docs/DEVELOPER_GUIDE.md). Server owners don't need it.
