# Portal Director

Build a field of Nether portals, line your players up behind them and let them all walk out at once.
Then, if you want to, send TNT minecarts out of the same portals. Made for video creators who want the huge
portal scenes from Minecraft invasion videos without building them by hand.

* **Paper 26.3, Java 25.** Server side only. Nobody needs a mod.
* Portals look and glow like real ones (4x5 obsidian frame, purple 2x3 interior), but they are decoration:
  nothing sends anyone to the Nether. Entrances are done by moving players, not by vanilla portal linking.

## Install

1. Download `PortalDirector-1.0.0.jar` and put it in your server's `plugins` folder.
2. Start the server. Operators can use everything straight away (permission `portaldirector.admin`).

## Five minute tour: 30 players, 30 portals, one command

Stand where you want the scene to be, then:

```
/pd generate 30 mixed            30 portals around you, on the ground and floating
/pd group create NULL            a group for the players who will take part
/pd group block set emerald_block    players who step on an emerald block join automatically
```

Place an emerald block (or a path of them) and have everybody walk over it. They are added once, with a single
action bar line and no chat spam, and each one gets the next free portal. When everybody is in:

```
/pd group assign NULL auto       portal 1 -> first player, portal 2 -> second, ... (optional, it already happened)
/pd group prepare NULL           everybody is moved into their own portal and held there
/pd group release NULL           all of them walk out into the Overworld at the same moment
/pd group reset NULL             cancel and send everyone back to where they were
```

Players walk out of the purple portal in the direction it faces, for a second or so. Players coming out of
floating portals are protected from fall damage for 20 seconds (`/pd set entrance.fall-protection-seconds 30`).

Want waves instead? `/pd group release NULL staggered 10` releases 5 players every 10 seconds;
`/pd group release NULL staggered 10 8` makes the waves 8 players.

Extra portals stay empty. Extra players simply have no portal (`/pd group assignments` shows who is where).

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

## TNT minecart attacks

Nothing is ever launched unless you type a command.

```
/pd tnt preview on                 safe mode: minecarts fly and land but never explode
/pd tnt launch all                 one minecart out of every portal, all at the same moment
/pd tnt launch all 3               three per portal
/pd tnt launch 1,4,10-15 2         only these portals
/pd tnt launch floating            or ground, or: group NULL
/pd tnt launch all 2 stagger 0.5   half a second between portals
/pd tnt wave 5 20                  5 waves, 20 seconds apart (add a count and portals: /pd tnt wave 5 20 2 ground)
/pd tnt stop                       stop sending more
/pd tnt clear                      remove every minecart that is still around
/pd tnt preview off                real explosions again
```

By default explosions hurt players and mobs but do not break any blocks (`tnt.protect-terrain`), and
the portals themselves are always protected (`tnt.protect-portals`). At most 300 minecarts exist at once
(`tnt.max-active`) and leftovers disappear after 60 seconds.

Minecarts leave through the portal in the direction it faces and then follow normal Minecraft physics.

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
