# PortalDirector reference

The details behind the commands in the [README](../README.md): how the settings work, what the sequences do step by step, how Nether portals are paired, and what changed between versions. The command lists are in the README; this page explains them.

**Contents:** [Settings in detail](#settings-that-matter) · [Groups](#groups-in-detail) · [TNT minecarts](#tnt-minecarts-in-detail) · [Sequences](#sequences) · [Nether portals](#nether-portals) · [Notes and limits](#notes-and-limits) · [Help topics](#built-in-help) · [Upgrading](#upgrading)

## Settings that matter

These are all changed with `/pd set <name> <value>`. `/pd settings` shows the ones that apply to your current layout first.

| Setting | What it changes in the world |
|---|---|
| `/pd set center here` / `150 100 -200` / `reset` | Where the formation is built (default: where you stand) |
| `/pd set count 30` | Random layout: how many portals |
| `/pd set facing north` | Which way portals face, and which way TNT minecarts come out (see [Facing](#facing)) |
| `/pd set generation delay 0.5` | Delayed generation: seconds between portals |
| `/pd set mode ground\|air\|mixed` | Portals on the ground, floating, or both |
| `/pd set radius 100` | How far from the center portals may be north, south, east and west (an automatic 5% is added) |
| `/pd set spacing horizontal 8` | Least gap between portal frames side by side |
| `/pd set spacing vertical 5` | Least gap between portals above each other |

## Facing

`/pd set facing` is the only setting that decides which way a portal points. The frame turns to match (north and south portals run along the x axis, east and west portals along z), and TNT minecarts leave in the same direction.

| Value | For | Meaning |
|---|---|---|
| `north`, `south`, `east`, `west` | random, grid, wave | Every portal faces that way |
| `random` | random, grid, wave | Each portal picks one of the four directions |
| `inward`, `outward` | square | Portals face the middle of the square, or away from it |

**Random facing is genuinely random.** In the random layout every portal is its own draw: it picks one of the four directions at random among those used least so far. That scatters the directions across the whole field and keeps the counts within one of each other, so 40 portals face about 10 each way. Portals never face you, the center or a common direction. Switching to the random layout from another layout puts the facing back to `random`; `/pd set facing north` afterwards makes them all face north. Grid and wave pick each portal's direction at random with `facing random` too, and use one direction otherwise.

A value the current layout can't use is refused with an explanation, for example `inward` for a grid. When you change the layout and the facing no longer fits, it switches to `inward` for a square and `random` for everything else and tells you. Single portals take the direction in the command: `/pd create portal Test here north`.

## Radius

`/pd set radius 100` is how far portals may spread north, south, east and west of the center. A fixed 5% margin is added automatically, so a radius of 100 really allows 105 blocks in each direction (`/pd settings` shows `Radius: 100 blocks (105 effective with automatic 5% margin)`). There is nothing else to configure.

The permitted area is a square: x from `center - 105` to `center + 105`, and the same for z. **Every block of the obsidian frame, including its outermost blocks, has to be inside it**, whichever way the portal faces. A portal's cleared walkway can reach up to three blocks further in the direction it faces.

- Random layout: portals are only placed where the whole frame fits. If fewer fit, the message says how many and what stopped the rest.
- Grid, wave and square: if the formation reaches further than the permitted area, nothing is built or removed and the message names the radius it needs, for example `/pd set radius 60`.

## Heights

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

## Mode: ground, air and mixed

- **ground:** each portal stands on the natural terrain surface. PortalDirector looks for a suitable spot nearby when a position is water, a cliff or blocked, never builds inside terrain, never leaves a portal hanging above the ground, and doesn't touch what players built.
- **air:** portals float, between `altitude-min` and `altitude-max` above the ground.
- **mixed:** about half floating and half on the ground, with no extra setting. (Grid, wave and square layouts always stand their lowest row on the ground in `ground` and `mixed` mode.)

Natural blocks in the way (grass, stone, trees) are replaced and restored by `/pd clear`. If something a player built is in the way, nothing is built. `/pd create skip-blocked` builds only the portals that fit.

## Creating portals

`/pd create` builds the layout you chose with `/pd set`. It has no placement options: where portals stand is the setting `/pd set mode ground|air|mixed`.

| Command | What it does |
|---|---|
| `/pd create` | Build the selected layout around you (grid, square and wave work out their own count) |
| `/pd create 30` | Random layout only: build 30 portals |
| `/pd create more` | Keep the existing portals and build another set |
| `/pd create replacement` | Remove the existing portals first, then build |
| `/pd create skip-blocked` | Build only the portals that fit and skip blocked positions |

Options can be combined with a count in any order, for example `/pd create 30 more skip-blocked`. `more` and `replacement` can't be used together.

## Single named portals

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
- **Around a player.** Instead of `here` or `x y z` you can give an online player's name (not case sensitive): `/pd create portal EntranceC Steve north`. The portal goes where that player is **at the moment the command runs**, in their world, so it also works while they run, fall or fly, and from the console. They are not followed afterwards. The same works for `/pd create 30 around Steve` (the whole formation is built around them, whatever center is pinned) and for the first argument of every sequence (`/pd sequence sky-army run Steve`).
- A portal is refused when it is closer than one empty block to another portal, outside the build height or world border, or would replace blocks that look player-built (add `force` at the end to override that last check). From the console add the world name: `/pd create portal EntranceA 0 100 0 north world`.
- **Nether link.** A single portal gets its Nether partner right away, and the message gives its coordinates; `/pd list` shows them next to the portal. `/pd remove` takes the Nether portal down too. The Nether coordinates are remembered for that Overworld spot: remove a portal and build it again at the same coordinates, and its Nether portal comes back at the same Nether coordinates (unless something has been built there in the meantime, in which case the nearest safe spot is used). See "Nether portals" below.
- Named portals work everywhere generated ones do: TNT (`/pd tnt launch EntranceA`), saving, restarts and restoring the ground.

## Cinematic reveal

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

## Groups in detail

A group is a few players who take part in a scene. Each one is linked to a portal; `prepare` stands them in it. Commands that do not name a group use the only group; with several groups add its name at the end. The command list is in the [README](../README.md#pd-group).

**Preparing.** `prepare` puts each online player with a portal inside the purple area, looking out of the portal. Players without a portal, offline players and missing portals are skipped and named in the message. Nobody is walked out or launched.

**Nether travel.** With `nether-travel false` (the default), a group member standing in the portal linked to them is not sent to the Nether, so the scene can wait inside the purple. Everybody else, and members in other portals, travel as normal. `true` allows travel, through the correct partner portal.

**Visibility.** `visibility-in-portal false` hides the prepared players from every other player's client (they are removed from view, not made invisible with a potion). Turning it `true` reveals them in small groups, 3, 2, 4, 2, 3 and so on, the first at once and the next ones one delay apart (every third pause is half as long again), so it looks natural but plays the same every time. If visibility was already on, prepared players simply appear. Hidden players become visible again if they leave the server, change world, leave the group or the group is deleted. Only prepared members of a group with visibility off are ever hidden.

Old groups are kept. Player assignments saved by older versions in scene files become links on their group, by portal name.

## TNT minecarts in detail

Nothing is ever launched unless you type a launch command (or run the `tnt-strike` sequence).

`/pd tnt launch all` fires from every portal in the current scene, one minecart each by default (`/pd tnt launch all 3` for three). `floating`, `ground`, `square`, `side <direction>` or portal names narrow it down. `/pd tnt limit` is the most minecarts launched in total, exploded or not (default 300); a launch that would pass it is cut short and says how many were skipped, and `/pd tnt clear` starts the count again. Portals in chunks that are not loaded launch nothing, so keep players nearby or force-load the area.

The default mode launches real, exploding minecarts, with `terrain-damage` and `portal-protection` set so that blocks and portals are safe. Use `/pd tnt mode safe` to rehearse: the minecarts fly but never explode.

## Built-in help

`/pd help` shows the five-step start (`/pd set`, `/pd create`, `/pd list`, `/pd remove`, `/pd clear`) on page 1. Every page ends with **Previous** and **Next** controls you can click, and clicking a command puts an example into your chat box.

| Command | Shows |
|---|---|
| `/pd help` or `/pd help 1` | Start here; `/pd help 2`, `3` and so on go page by page |
| `/pd help create` | Creating portals and the `more`, `replacement` and `skip-blocked` options |
| `/pd help examples` | Ready-made recipes: 30 floating portals, different heights, a world Y range, a wall |
| `/pd help group` | Groups, linking players to portals, preparing, visibility and Nether travel |
| `/pd help layouts` | Grid, random, square and wave |
| `/pd help portals` | Single named portals |
| `/pd help reveal` | The cinematic delay |
| `/pd help scenes` | Listing, removing and clearing |
| `/pd help sequence` | The three built-in sequences and their commands |
| `/pd help set` | Every setting, in plain words, including the four height settings |
| `/pd help tnt` | TNT minecarts |

## Sequences

A sequence is a ready-made scene you run with one command, instead of creating portals, assigning players and setting up effects by hand. There are three, and each keeps **its own settings**: changing a sequence never changes `/pd set`, and never changes another sequence. The manual commands all keep working.

| Sequence | What it does | Players moved automatically? | TNT? | Portal travel? |
|---|---|---|---|---|
| `nether-arrival` | Builds one portal and moves your group beside its Nether partner. They walk in when you tell them to. | Yes, once, to the Nether side | No | Yes: that portal lets the group through |
| `sky-army` | Builds floating portals one by one and makes every group member appear inside one. | Yes, one by one, into the Overworld portals | No | Switched off while they wait |
| `tnt-strike` | Builds floating portals one by one; each fires a random number of TNT minecarts outward. | No | Yes | No |

| Command | What it does |
|---|---|
| `/pd sequence list` | The sequences, what they do and an example for each |
| `/pd sequence <name> info` | What it does, how to run it, what happens to players, TNT and portal travel, and its current settings |
| `/pd sequence <name> run <x y z \| here> ...` | Run it (`here` is the block you stand on; from the console add a world name at the end) |
| `/pd sequence <name> set <option> <value>` | Change a setting of that sequence only |
| `/pd sequence <name> reset` | Back to its default settings |
| `/pd sequence status` | What is running or waiting, with progress |
| `/pd sequence stop` | Cancel the running sequence and let go of anyone it holds |

### nether-arrival

```
/pd sequence nether-arrival set group NULL
/pd sequence nether-arrival run here north
/pd sequence nether-arrival run 100 75 200 north
```

Builds one portal at that spot, facing north, south, east or west, creates (or reuses) its Nether partner, and teleports every **online** member of the group to safe places beside the Nether portal: two open blocks above natural ground, no lava or fire near, outside the portal and its frame, each on a block of their own and looking at the portal. **Nothing else happens.** Nobody is walked in and nothing is timed: you tell your players when to step into the Nether portal, and it sends them to the portal you built.

For that one portal the group may travel even if `/pd group nether-travel` is false. The group setting itself is never changed, and every other portal keeps obeying it. `/pd sequence stop` ends the allowance (players stay where they are). Running it again at the same spot reuses the portal; the same spot with another direction is refused.

| Setting | Default | Meaning |
|---|---|---|
| `group` | the only group | Which group is moved |
| `spawn-distance` | 5 | How many blocks from the Nether portal players wait (2 to 20); places are searched up to 12 blocks beyond |

If a cave is too cramped for everyone, those who fit are moved and the rest are named in the message.

### sky-army

```
/pd sequence sky-army set group NULL
/pd sequence sky-army run here
/pd sequence sky-army run 100 120 200
```

Plans one **floating** portal for every online member of the group (random layout, each portal facing its own random direction, all inside the radius and within `height-range` blocks above and below the height you give). Portals appear one after another at short random gaps; each player is then teleported into the purple middle of their own portal, a short random time after it appears, and shown to everyone else at that moment (until then they are hidden from other players). Nobody appears before their portal exists, and they face outward.

Everyone is accommodated. If the portals do not all fit, the search widens step by step: a wider area, then a taller band of sky (always inside the world's build height and border), then narrower gaps (never less than one empty block, so frames never touch). If there are still fewer portals than players (or fewer are allowed by `/pd set max-portals`), players share portals evenly: two stand on the two interior blocks, more get slightly different spots. The final message says how many share, or that only part of the group could be staged. It never replaces blocks that are not air, and never touches existing portals.

While staged the players stay in the Overworld and **cannot travel to the Nether**. They wait until you run `/pd sequence stop`, which sends each player back to where they stood before and shows everyone again; the portals stay (`/pd clear` removes them). Existing `/pd group` links and settings are not touched.

| Setting | Default | Meaning |
|---|---|---|
| `group` | the only group | Which group appears |
| `height-range` | 30 | Portals float this many blocks above and below the height you give (widens if needed) |
| `player-delay-max` / `player-delay-min` | 0.5 / 0.1 | Seconds before a player appears in their portal, a random value in between for each |
| `portal-delay-max` / `portal-delay-min` | 0.55 / 0.15 | Seconds between two portals appearing, a random value in between each time |
| `radius` | 100 | How far from the center portals may float (an automatic 5% is added, and it grows if they do not all fit, within the chunk limit) |

### tnt-strike

```
/pd sequence tnt-strike set portals 20
/pd sequence tnt-strike run here
/pd sequence tnt-strike run 100 120 200
```

Builds floating portals the same way (random layout, random facings, one after another at short random gaps). Each portal then fires its own random number of TNT minecarts, 5 to 10 by default, outward the way it faces: the first one a random moment after the portal appears, and each next one a random moment after the one before.

**The limit is checked first.** Every minecart counts against `/pd tnt limit` like any other launch. Before anything is built, the sequence works out how many minecarts it will launch; if the limit does not have room, it tells you and builds nothing. It never goes past the limit and never resets the count (`/pd tnt clear` does, when you decide). Global TNT settings are not changed.

Minecarts are **safe by default**: they fly but never explode, even if `/pd tnt mode` is real. `/pd sequence tnt-strike set tnt-mode real` makes this sequence's minecarts explode (using your fuse, `terrain-damage` and `portal-protection` settings, which keep blocks and portals safe by default).

| Setting | Default | Meaning |
|---|---|---|
| `height-range` | 30 | Portals float this many blocks above and below the height you give |
| `launch-delay-max` / `launch-delay-min` | 0.7 / 0.2 | Seconds before a portal's first minecart and between its minecarts |
| `portal-delay-max` / `portal-delay-min` | 0.55 / 0.15 | Seconds between two portals appearing |
| `portals` | 20 | How many floating portals to make |
| `radius` | 100 | How far from the center portals may float |
| `tnt-max` / `tnt-min` | 10 / 5 | Most and fewest minecarts one portal fires |
| `tnt-mode` | safe | `safe` or `real` |

### Good to know

- Sequences use the normal portal building, naming (`sky-army-1`, `tnt-strike-3`, `nether-arrival`), tracking and restoring, so `/pd list`, `/pd remove` and `/pd clear` work on them, and every block is put back.
- Running a sequence again never deletes anything: new portals are added, and existing ones are treated as obstacles. Only one sequence runs at a time (and not while a build or removal is running).
- `/pd sequence stop` (or `/pd stop`) cancels safely: portals already built stay, players the sequence moved go back, hidden players are shown, and travel restrictions are lifted. Players are also released if they quit, and everything is released when the server stops.
- Settings are saved in `plugins/PortalDirector/sequences.yml` (only what you changed) and survive restarts. Permission: `portaldirector.sequence`.
- `tnt-strike` and `sky-army` need loaded chunks: they load the area and keep it loaded while they run. Very large areas are refused with the chunk limit (`/pd set max-chunks`).

## Nether portals

When a player walks into a PortalDirector portal, the plugin sends them to that portal's own partner in the Nether, and back through the same partner to the same portal. Portals that aren't PortalDirector's are left alone.

**Why this is needed.** Minecraft divides Overworld coordinates by 8 to find the Nether spot, then reuses any Nether portal within 16 blocks of it. Portals built 8 to 100 blocks apart in the Overworld land within a few blocks of each other in the Nether, so dozens of them share one or two Nether portals. Coming back, it multiplies by 8 and picks the nearest Overworld portal within 128 blocks, which is often a different one. Vanilla linking simply can't keep a one-to-one connection for portals this close.

**What PortalDirector does.** The first time someone uses a portal, it builds that portal's partner in the Nether at a safe spot as near as possible to the Overworld coordinates divided by 8 (up to 32 blocks away) and remembers the pair in `plugins/PortalDirector/nether/`. Partners keep at least 3 empty blocks between them, face the same way as the Overworld portal, and are only placed in open natural space on solid ground: never next to lava, never within 2 blocks of a fortress, bastion, chest or someone's build, and never close to another portal. If there is no such place, the player stays where they are and is told why; the portal is never linked to another one. Removing a portal (`/pd remove`, `/pd clear`) restores the Nether blocks of its partner and nothing else.

**Same spot every time.** Where a partner stood is remembered for its Overworld anchor, even after the pair is removed. Build a portal at the same coordinates again and the search starts at those Nether coordinates, so you get the same Nether portal in the same place whenever it is still free and safe. Single portals made with `/pd create portal` get their partner immediately and report its coordinates; `/pd list` shows them.

**What to know.** Portals from layouts (`/pd create`) get their Nether partner on first use, so nothing is changed in your Nether until someone travels. The first trip through a new portal loads and may generate some Nether chunks, which takes a moment. A vanilla portal built within about 128 Overworld blocks of one of ours can end up in one of our Nether portals, and then returns to ours; keep vanilla portals away from PortalDirector areas if that matters.

## Notes and limits

- Creating very large areas loads many chunks; the limit is `generate.max-chunks`.
- Normal Nether travel through generated portals works, with one Nether partner per portal (see [Nether portals](#nether-portals)). Group members with `nether-travel false` stay in their portal.
- The radius holds the obsidian frame. The cleared walkway in front of a portal can reach up to three blocks further.
- Grid, wave and square layouts treat `mixed` mode like `ground`: their lowest row stands on the ground and the rest stack above it.

## Upgrading

**From 1.4.0:** `/pd sequence` is new. `/pd tnt limit` now means the most minecarts launched **in total**, exploded or not (it used to be the most alive at once); `/pd tnt clear` starts the count again, and `/pd tnt status` shows it. `/pd create portal` now builds the Nether partner straight away, `/pd list` shows each portal's Nether coordinates, and a portal rebuilt at the same coordinates gets its Nether portal back at the same Nether coordinates. Nothing in your saved scenes, groups or config needs changing.

**From 1.3.0:** the group commands changed.

| Before | Now |
|---|---|
| `/pd group add` | `/pd group assign-to-group <player> <group>` |
| `/pd group remove` | `/pd group remove-from-group <player> <group>` |
| `/pd group disband` | `/pd group delete <name>` |
| `/pd group block set <block>` | `/pd group block <block>` |
| `/pd group assign`, `assignments` | `/pd group link-to-portal`, `unlink-from-portal`, `info` |
| `/pd group status`, `release`, `reset` | removed |
| (new) | `prepare`, `nether-travel`, `visibility-in-portal`, `visibility-in-portal-delay` |

Existing groups, members and join blocks are kept, and portal assignments from scene files saved by 1.2.0 or older become links on their group.

**From 1.2.0** (everything below is migrated automatically when the plugin starts; saved portals are untouched):

| Before | Now |
|---|---|
| `height-min`, `height-max` | `altitude-min`, `altitude-max` (same values) |
| `min-y`, `max-y` | `world-y-min`, `world-y-max` (same values) |
| `orientation` | removed: `/pd set facing` turns the frame |
| `air-ratio` | removed: `mixed` is about half and half |
| `slope` | removed: ground portals pick suitable terrain themselves |
| `facing auto` | `random` (`inward` for a square). `inward` and `outward` are for the square only |
| `entrance.*`, `/pd group release\|reset\|assign\|assignments\|status`, `/pd set staging` | removed with the player entrance system (1.4.0 adds a simpler `/pd group prepare`) |
| `groups.auto-assign`, `groups.announce-join` | removed |
| `/pd tnt launch group <name>` | removed (it used the assignments) |

The radius now holds whole frames inside a square (with an automatic 5% margin) instead of a circle that could reach a few blocks past it. Old player assignments in scene files are ignored and dropped the next time the scene is saved.

**From 1.1.0:** `/pd create add`, `replace` and `partial` are now `more`, `replacement` and `skip-blocked`, and `air`, `ground` and `mixed` are only set with `/pd set mode`. Old words are refused with a message that names the new one.

**From 1.0.0:** `/pd generate` is now `/pd create`, and `/pd portal create` and `/pd portal remove` are now `/pd create portal <name> ...` and `/pd remove`. Saved scenes load unchanged; their portals are named `portal-<number>`.
