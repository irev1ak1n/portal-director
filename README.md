# Portal Director

Build a field of Nether portals, line your players up behind them and let them all walk out at once.
Then, if you want to, send TNT minecarts out of the same portals. Made for video creators who want the huge
portal scenes from Minecraft invasion videos without building them by hand.

* **Paper 26.3, Java 25.** Server side only. Nobody needs a mod.
* The portals are real Nether portals (4x5 obsidian frame, purple 2x3 interior). Anyone can walk into one and travel
  to the Nether and back like normal. Separately, **cinematic mode** lets you stage chosen players and release them
  all at once. The two do not get in each other's way (see "Two ways to use the portals").

## Install

1. Download `PortalDirector-1.0.0.jar` and put it in your server's `plugins` folder.
2. Start the server. Operators can use everything straight away (permission `portaldirector.admin`).

## Quick start (about two minutes)

Stand somewhere open with grass and a bit of space, then type:

```
/pd generate 30        build 30 scattered portals around you
/pd list               the id and coordinates of every portal
/pd clear              remove everything and put the ground back
```

That is the random layout. For organised formations pick a layout first (details in "Layouts" below):

```
/pd set layout grid      /pd set grid rows 4       /pd set grid columns 5        /pd generate
/pd set layout square    /pd set square per-side 5 /pd set square layers 3       /pd generate
/pd set layout wave      /pd set wave rows 4       /pd set wave per-row 5        /pd generate
```

`/pd help` explains everything in plain words, and pressing Tab after `/pd` shows every command. To go to a portal, use
Minecraft's own `/tp` with the coordinates from `/pd list`.

All configuration goes through `/pd set`. `/pd settings` shows the current values and how many portals the next
`/pd generate` will make. If portals already exist, `/pd generate` tells you your options instead of just refusing:
`/pd generate add` keeps them and builds more, `/pd generate replace` swaps them for new ones, `/pd clear` removes them.

**Can't see them?** The server only sends the land around you up to its view distance. If the view distance is
smaller than the formation, far portals are invisible until you get closer, and Portal Director warns you after generating when
this might be the problem. Raise `view-distance` in `server.properties`, or use a smaller formation.

## Layouts

Every layout works the same way: `/pd set layout <random|grid|square|wave>`, set that layout's numbers, then `/pd generate`.
Grid, square and wave work out how many portals to build from their own numbers, and `/pd generate` tells you the total before it starts
and the real count when it is done. Portals are always upright; facing only turns them sideways and decides where players and minecarts come out.

| Layout | Settings | What you get |
|---|---|---|
| random | `/pd set count 50`, `/pd set radius 100` | Portals scattered around you. `/pd generate 50` is a shortcut |
| grid | `/pd set grid rows 4`, `/pd set grid columns 5` | A vertical wall: 4 rows going up, 5 columns side by side, exactly 20 portals, evenly aligned |
| square | `/pd set square per-side 5`, `/pd set square layers 3` | A square perimeter with 5 portals on each side, repeated in 3 identical layers stacked upward, 60 portals |
| wave | `/pd set wave rows 4`, `/pd set wave per-row 5` | Rows of 5 that rise and fall like a wave, every other row shifted sideways, exactly 20 portals |

**Spacing.** `/pd set spacing horizontal 8` is the empty gap between portals side by side (columns, neighbours on a square side). `/pd set spacing vertical 5` is the
gap between rows and between layers above each other. They are independent.

**Facing.** `/pd set facing north|south|east|west` turns a whole grid or wave wall: it faces that way, and players and minecarts come out of that side.
`random` turns every portal its own way but keeps every position exactly where it was. A square uses `/pd set facing inward` (toward the middle, the default) or `outward`.
`auto` (the default) makes a wall face you.

**Center.** The formation is built around where you stand (`/pd set center here` pins that spot), or around coordinates: `/pd set center 150 100 -200`
(x y z), `/pd set center reset` goes back to your position. Height rule for several rows or layers: the middle one (for an even number, the lower of the two middle ones) has its doorway at the
center height, and the others are stacked above and below it, one row or layer apart. So 3 layers are below, at and above the center; 2 layers are at the center and above it; a 4-row
grid has one row below, one at the center and two above.

**Ground or air.** `/pd set mode ground` (also `mixed`) stands the lowest row on the terrain. `/pd set mode air` floats the formation exactly around the center height, so
a large square can hang over forests, mountains or an arena. Natural blocks (grass, stone, trees) in the way are replaced, and `/pd clear` puts them back. If something a
player built is in the way, nothing is built and nothing is changed: you get the list of blocked portals, and `/pd generate partial` builds only the ones that fit.

Square layouts also let `/pd group release <name> clockwise|opposite-sides|side-by-side` release players around the square, and `/pd tnt launch square` or `/pd tnt launch side east` pick its portals.

## Two ways to use the portals

**Normal mode (default).** Walk into a generated portal, wait a few seconds, and you travel to the Nether. Walk into the
portal there to come back, exactly like a vanilla portal. Players have to jump onto the obsidian floor only if the ground
in front of it is lower. To make them decoration only: `/pd set entrance.block-vanilla-travel on`.

**Cinematic mode.** Players who are in a group are moved around by Portal Director instead. Prepared players wait inside
their portal and cannot be sent through it, and on release they walk out into the Overworld together:

```
/pd group create team                  a group for the players taking part
/pd group block set emerald_block      players who step on an emerald block join and get the next free portal
/pd group prepare team                 everybody waits in their own portal
/pd group release team                 all of them walk out at the same moment
/pd group reset team                   cancel and send everybody back to where they were
```

Players walk out in the direction their portal faces, for a second or so. Players coming out of floating portals are
protected from fall damage for 20 seconds. Want waves? `/pd group release team staggered 10` releases 5 players every 10 seconds
and `/pd group release team staggered 10 8` makes the waves 8 players. Extra portals stay empty, extra players have no portal
(`/pd group info` shows who is where). Players who leave during `prepare` are put back in their portal when they return.

## TNT minecarts

```
/pd tnt launch all         one minecart out of every portal
/pd tnt wave 5 20          5 waves, 20 seconds apart
/pd tnt status             are explosions on? how many minecarts? what is the limit?
/pd tnt mode safe|real     safe: minecarts fly but never explode. real: they explode
/pd tnt fuse 3             they explode 3 seconds after launch (off: only when they crash)
/pd tnt terrain-damage on  explosions break blocks (off: they only hurt, the default)
/pd tnt limit 300          most minecarts alive at once
/pd tnt stop               stop launching; minecarts already out keep going
/pd tnt clear              stop and remove every TNT minecart
```

Real explosions are on by default with a 3 second fuse and no block damage. Nothing is ever launched unless you
type a launch command, and every launch tells you whether safe mode or real explosions are on.

## Single portals at exact coordinates

```
/pd portal create 150 80 -200 north        x y z, then the direction players and minecarts come out
/pd portal create 150 80 -200 south
/pd portal create 150 80 -200 east
/pd portal create 150 80 -200 west
/pd portal create here north               at your feet
/pd portal create ~ ~10 ~ east             relative to you
/pd portal remove 7                        take portal 7 down and restore the blocks
/pd list                                   ids and coordinates of all portals
```

**What the coordinates mean:** `x y z` is the lower of the two bottom blocks inside the portal, the block a
player's feet are in when standing in the doorway (the smaller x for north/south portals, the smaller z for
east/west ones). The obsidian floor is the block directly below it, so the frame spans `y-1` to `y+3`. North and
south portals are 4 blocks wide along x, east and west portals along z. The direction is the way people walk
out of the portal.

Manual portals get the next free id and behave exactly like generated ones: they can be
assigned to players, used for entrances and TNT, saved, rebuilt and cleared. They can float at any valid height
or stand on the ground. A portal is refused when it is closer than one empty block to another portal, outside the
build height or world border, or would replace blocks that look player-built (add `force` at the end to override
that last check). From the console add the world name: `/pd portal create 0 100 0 north world`.

## Clearing and restoring

Portal Director remembers every portal and every block it changed, even after a restart. `/pd clear` removes the portals and
puts the ground back. A block is only restored if it still holds what Portal Director put there: if somebody built on a
portal or mined the frame, those blocks are left exactly as they are. Your assigned players, the join block and all settings
are saved too.

## Settings

`/pd settings` shows the values that matter for the current layout, `/pd set <name> <value>` changes one, and Tab completion fills in names and values.
`/pd set` is the only way to configure Portal Director.

| Command | What it does |
|---|---|
| `/pd generate [count] [ground\|air\|mixed] [add\|replace] [partial]` | Build the current layout. A count only applies to the random layout |
| `/pd set layout random\|grid\|square\|wave` | The formation |
| `/pd set grid rows 4` / `columns 5` | Grid size |
| `/pd set square per-side 5` / `layers 3` | Square size |
| `/pd set wave rows 4` / `per-row 5` | Wave size |
| `/pd set count 50` / `radius 100` | Random layout: how many portals and how far from the center |
| `/pd set mode ground\|air\|mixed` | Stand on the terrain, or float around the center height (`/pd set air-ratio 0.5` sets the share that floats in the random layout) |
| `/pd set spacing horizontal 8` / `vertical 5` | Gap between portals side by side / between rows and layers |
| `/pd set facing north\|south\|east\|west\|random\|inward\|outward\|auto` | Which way portals face and where players and minecarts come out |
| `/pd set center here` / `150 100 -200` / `reset` | Where the formation is centered |
| `/pd set height min 15` / `height max 60` | Random layout: floating portals hang this far above the ground |
| `/pd set orientation x\|z\|random` | Random layout: which way the frames run |
| `/pd set min-y 40` / `max-y 250` | Absolute limits for any portal (optional) |
| `/pd set staging here` / `clear` | Hold prepared players at your position (for example in the Nether) instead of at their portals |
| `/pd set tnt.fuse-seconds 3`, `/pd set entrance.walk-ticks 30` ... | Every other option; `/pd settings all` lists them |
| `/pd list` | The id and coordinates of every portal |
| `/pd stop` | Cancel a generation or removal that is running |

**How spacing works.** Two portals are always kept apart: either by the horizontal spacing along x or z, or by the vertical spacing between their heights.
Frames can never touch or overlap, whichever way they are turned. (Older configs with a single `generate.spacing` keep working: that value becomes the horizontal spacing.)

In the random layout portals avoid water, steep ground and anything that looks built by a player (chests, houses, farms...);
they only replace ground, plants and trees. Ground portals are set level with the ground, never buried, never under a hill or a tree canopy, and get support underneath on uneven terrain. Building
is spread over many ticks and chunks in the area are loaded a few at a time, so the server does not freeze.

## Groups and entrances

| Command | What it does |
|---|---|
| `/pd group create <name>` | New group |
| `/pd group block set <block>` | Stepping on this block joins the group |
| `/pd group info [name]` | Members and their portals |
| `/pd group add\|remove [name] <player>` | Manual membership |
| `/pd group assign <name> auto [fill]` | Portal 1 -> player 1, ... (`fill` keeps manual choices) |
| `/pd group assign <name> <portal> <player>` | Manual slot |
| `/pd group assign <name> swap <a> <b>` / `clear [portal]` | Rearrange |
| `/pd group assignments [name]` | Who is at which portal |
| `/pd group prepare <name>` | Stage everyone |
| `/pd group release <name> [staggered <secs> [players per wave]]` | Everybody walks out |
| `/pd group reset <name>` | Cancel and send players back |
| `/pd group disband <name>` | Delete the group |

With only one group you can leave its name out. Players who disconnect while prepared are placed back in their
portal when they return. To hide staged players, `/pd set entrance.staging-invisible on`.

## Permissions

Operators have everything by default. `portaldirector.admin` is all of it, or give people the pieces:
`portaldirector.generate` (build, set, list), `.group`, `.tnt` and `.scene` (`/pd clear`).

## Supported versions

Built and tested against **Paper 26.3 (build 231) on Java 25**. Other versions are untested.

## Limitations

* Entrances move players with teleports plus a short walk (a velocity push every tick). It is not the
  player's own input, so a player can interrupt it, and how smooth it looks on a camera depends on their ping.
* Players staged at their portals are visible to everyone unless `entrance.staging-invisible` is on
  or a staging area is set.
* Terrain checks recognise common natural blocks; unusual modded or custom-world blocks may be treated as
  structures and avoided.
* Generating very large areas loads many chunks; the limit is `generate.max-chunks`.
* With `/pd tnt fuse off`, minecarts that never crash may not explode; they are removed after 60 seconds.
* Folia is not supported.

## Building from source

```
gradlew build           (needs a JDK that can start Gradle; the build uses a Java 25 toolchain)
```

The jar is in `build/libs/`. `gradlew test` runs the unit tests.

## Testing it yourself

`scripts/setup-test-server.ps1 -AcceptEula` creates an offline-mode Paper test server bound to 127.0.0.1 next to
the repository and installs the jar. `bot-test/` holds the Mineflayer harness (`npm install`, then `node ux-test.js` or `node layout-test.js`)
that drives a few bots through the whole flow. Read `bot-test/README.md` before trusting it: bots cannot see
the portals.
