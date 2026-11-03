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
/pd generate 30        build 30 portals around you (on the ground and floating)
/pd view               float above the middle of them and look around
/pd portal tp 1        stand in front of portal 1 (also: nearest, farthest)
/pd clear              remove everything and put the ground back
```

That is all you need to start. `/pd help` explains the rest in plain words, and pressing Tab after `/pd` shows
every command. Not sure where your portals went? `/pd where` tells you how many there are and which direction the
nearest and farthest ones are, and `/pd portal list` lists them all, nearest first.

Change how they are built with `/pd set`, then generate again (`/pd generate 30 replace` removes the old ones first):

```
/pd settings               every setting and its current value
/pd set radius 50          how far from you portals may be
/pd set spacing 5          empty blocks between portals
/pd set mode air           ground, air or mixed
/pd set layout circle      random, grid, circle or wave
/pd set height min 10      floating portals hang at least 10 blocks above the ground ...
/pd set height max 40      ... and at most 40 blocks above it
```

If a scene already has portals, `/pd generate 30` tells you your options instead of just refusing:
`/pd generate 30 add` keeps them and builds 30 more, `/pd generate 30 replace` swaps them for new ones, and
`/pd scene new heist` starts a separate scene.

**Can't see them?** The server only sends the land around you up to its view distance. If your view distance is
smaller than the radius, far portals are invisible until you get closer. `/pd view` and `/pd portal tp` take you to them,
and after generating Portal Director warns you when this might be the problem.

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
/pd portal list
```

**What the coordinates mean:** `x y z` is the lower of the two bottom blocks inside the portal, the block a
player's feet are in when standing in the doorway (the smaller x for north/south portals, the smaller z for
east/west ones). The obsidian floor is the block directly below it, so the frame spans `y-1` to `y+3`. North and
south portals are 4 blocks wide along x, east and west portals along z. The direction is the way people walk
out of the portal.

Manual portals get the next free id in the current scene and behave exactly like generated ones: they can be
assigned to players, used for entrances and TNT, saved, rebuilt and cleared. They can float at any valid height
or stand on the ground. A portal is refused when it is closer than one empty block to another portal, outside the
build height or world border, or would replace blocks that look player-built (add `force` at the end to override
that last check). From the console add the world name: `/pd portal create 0 100 0 north world`.

## Square formation

Portals along the four sides of a square with an open middle, like an arena. Players or minecarts come out
toward the middle (inward, the default) or away from it (outward).

```
/pd square preview 60                  plan it first: how many fit, outlined with particles, nothing is built
/pd square create 60                   a square about 60 blocks wide around you
/pd square create 8 portals-per-side   8 portals on every side, the size is worked out for you
/pd square create 100 spacing 3        3 empty blocks between neighbouring portals
/pd square create 60 elevation 25      floating, 25 blocks above the highest ground
/pd square create 60 facing outward    portals face away from the middle
/pd square create 60 center 100 -40 world    exact center instead of where you stand
/pd square facing outward              set the default and turn the existing square around
/pd square info
/pd square remove                      take the whole square down and restore the terrain
```

Before anything is built you see the result: for example "9 portals per side, 36 in all, 2 blocks between
them. Fits: 36 of 36". If water, steep ground or a building blocks some positions nothing is built unless you add
`partial`. The sides are identical and each one stops short of the next, so frames never touch at the corners.
Ids go clockwise from the north-west corner: the north side first, then east, south and west. The square
is a normal part of its scene, so everything else works on it: group assignment, entrances, TNT, save, rebuild, clear.

Cinematic releases (give each side's portals players with `/pd group assign`, then `prepare`, then):

```
/pd group release NULL all-at-once            every side at the same moment
/pd group release NULL clockwise 0.5          one portal after another around the square, 0.5 s apart
/pd group release NULL clockwise 2 4          four portals at a time, 2 s apart
/pd group release NULL opposite-sides 3       north and south together, then east and west 3 s later
/pd group release NULL side-by-side 4         one side at a time, 4 s apart
```

TNT from the square: `/pd tnt launch square`, `/pd tnt launch side east`, `/pd tnt launch square 2 stagger 0.25`
(the stagger follows the clockwise order), `/pd tnt wave 5 20 1 square`.

## Ready-made scenes

```
/pd preset list
/pd preset invasion        random portals on the ground and in the air, all facing the middle
/pd preset sky-army        floating portals at many different heights
/pd preset formation       a tidy grid of ground portals, everyone walking out the same way
/pd preset tnt-barrage     a ring of portals with 2 minecarts each and 3 timed waves ready
/pd preset invasion 60     the same with a different portal count
```

A preset sets the generator options, builds the scene (named after the preset) around you and leaves
everything else alone. Run `/pd scene clear invasion` to take it down again.

## Scenes

Every portal belongs to a scene. A scene remembers its portals, their IDs, orientation, the players
assigned to them and every block it changed, so it can be taken down without damaging anything.

```
/pd scene info                 what is in the current scene
/pd scene save invasion        name and save the current scene
/pd scene new heist            start a second, independent scene
/pd scene load invasion        switch to a scene
/pd scene list
/pd scene clear [name]         remove the portals and restore the terrain (also: /pd clear)
/pd scene rebuild [name]       put missing portals back
/pd scene delete <name>        forget an empty scene
```

Removal only restores a block if it still holds what the scene put there. If somebody built on top of a
portal, or mined the frame, those blocks are left exactly as they are.

Scenes, groups, assignments and all settings survive restarts.

## Generator settings

Everything is also editable with `/pd settings` and `/pd set <name> <value>`. Tab completion helps everywhere.

| Command | What it does |
|---|---|
| `/pd generate [count] [ground\|air\|mixed] [add]` | Build portals. `add` keeps the portals already in the scene |
| `/pd count 50` | Portals per generate |
| `/pd radius 100` | How far from the center portals may go (up to 400) |
| `/pd spacing 10` | Empty blocks kept between portals (minimum 1) |
| `/pd height 25 80` | Floating portals hang this many blocks above the ground, a different height each |
| `/pd placement ground\|air\|mixed` | Ground, floating, or both (`generate.air-ratio` sets the share) |
| `/pd orientation x\|z\|random` | Which way the frame runs. Always upright |
| `/pd facing auto\|inward\|outward\|random\|north\|south\|east\|west` | The way players and minecarts leave |
| `/pd layout random\|grid\|circle\|wave` | How portals are arranged |
| `/pd center here` / `/pd center 100 -40 world` / `/pd center reset` | Pin the middle of the scene |
| `/pd list`, `/pd tp <id>` | Browse portals and visit one |
| `/pd stop` | Cancel a generation or removal that is running |

Portals avoid water, steep ground and anything that looks built by a player (chests, houses, farms...);
they only replace ground, plants and trees. Ground portals get support underneath on uneven terrain. Building
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
| `/pd staging set` / `/pd staging clear` | Hold prepared players at your position (for example in the Nether) instead of at their portals |

With only one group you can leave its name out. Players who disconnect while prepared are placed back in their
portal when they return. To hide staged players, `/pd set entrance.staging-invisible on`.

## Permissions

Operators have everything by default. `portaldirector.admin` is all of it, or give people the pieces:
`portaldirector.generate`, `.group`, `.tnt`, `.scene`.

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
* Minecarts that never hit anything may not explode on their own; they are removed after 60 seconds, or set
  `tnt.fuse-ticks` to light them after launch.
* Folia is not supported.

## Building from source

```
gradlew build           (needs a JDK that can start Gradle; the build uses a Java 25 toolchain)
```

The jar is in `build/libs/`. `gradlew test` runs the unit tests.

## Testing it yourself

`scripts/setup-test-server.ps1 -AcceptEula` creates an offline-mode Paper test server bound to 127.0.0.1 next to
the repository and installs the jar. `bot-test/` holds the Mineflayer harness (`npm install`, then `node run.js`)
that drives 30 bots through the whole flow. Read `bot-test/README.md` before trusting it: bots cannot see
the scene.
