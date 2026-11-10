# PortalDirector

PortalDirector is a Minecraft server plugin designed to make creating large-scale cinematic scenes easier, especially for content creators working with groups of players. Imagine wanting dozens of Nether portals to appear across the sky, an entire army to emerge from them, or TNT minecarts to launch from different directions. Setting up scenes like these manually can take a lot of time and coordination, particularly when you're recording with a large group.

PortalDirector brings these mechanics together in one place. You can generate and customize entire portal formations, coordinate player appearances, and create TNT attacks without having to manage every portal or player individually. It also includes ready-made sequences that handle multiple actions with a single command, allowing creators to spend less time setting things up and more time focusing on the actual scene.

## Features

1) Customizable portal formations. Generate dozens of Nether portals using four different layouts: Random, Grid, Square, and Wave. You can adjust their spacing, direction, height, and placement to fit the scene you're trying to create, whether that means portals scattered across the ground or floating in the sky.

2) Cinematic portal reveals. Instead of having every portal appear instantly, you can make them generate one after another with adjustable timing, different reveal orders, and optional sounds and particles.

3) Reliable Nether connections. Each generated portal has its own corresponding portal in the Nether, allowing players to travel between the two dimensions without dozens of nearby portals sending everyone to the same destination.

4) Player groups and coordinated appearances. Organize players into groups, assign them to individual portals, and prepare them for a scene. You can also hide players while they're inside their portals and reveal them with short delays to create the effect of an army suddenly appearing.

5) Ready-made cinematic sequences. Run more complex scenes with a single command. Nether Arrival prepares a group to enter through one portal, Sky Army creates floating portals and reveals players inside them, and TNT Strike generates portals that launch waves of TNT minecarts. Each sequence has its own customizable settings.

6) TNT minecart attacks. Launch TNT minecarts from selected portals or entire formations, control their speed and timing, and choose whether they actually explode or simply fly out for visual effect.

7) Easy cleanup and restoration. Remove individual portals or clear an entire formation when you're done. PortalDirector remembers the blocks it changed, even after a server restart, and restores the original terrain when the portals are removed.

8) Safer world generation. Portals are placed with surrounding terrain and existing structures in mind, helping avoid unwanted damage. Larger formations are also generated gradually to reduce the impact on server performance.

## Download and installation

**[Download PortalDirector from GitHub Releases](https://github.com/irev1ak1n/portal-director/releases/latest)**

On that page, scroll to **Assets** and click `PortalDirector-<version>.jar`. That file is the whole plugin.

**Requirements:** a **Paper** server for **Minecraft 26.3** and **Java 25**. Other Minecraft versions, Spigot and Folia are not supported.

Only the **server owner or admin** installs PortalDirector. **Players don't download or install anything.**

1. Download the jar from the link above.
2. Put it in your server's **`plugins`** folder (hosting panel: File Manager, `plugins`, Upload; own server: copy it next to `paper.jar`).
3. **Restart** the server.
4. Run `/plugins`. **PortalDirector** should be green. Then run `/pd help`.

**Upgrading:** stop the server, replace the old jar with the new one and start again. Your settings, groups and portals are kept, and renamed settings are migrated automatically. See [Upgrading](docs/REFERENCE.md#upgrading) for what changed in each version.

## Quick start

You need to be an operator (see [Permissions](#permissions)). Stand somewhere open and type:

```
/pd create 30            30 scattered portals around you
/pd list                 their names and positions
/pd clear                remove everything and restore the ground
```

Pick a formation and reveal it one portal at a time:

```
/pd set layout grid
/pd set generation delayed
/pd create
```

One named portal, and a whole scene with one command:

```
/pd create portal EntranceA here north
/pd remove EntranceA
/pd group create Group1
/pd group assign-to-group Steve Group1
/pd sequence sky-army run here
```

`/pd help` has short clickable pages, and Tab after `/pd` lists every command in alphabetical order.

## Commands

Base command: `/pd` (alias `/portaldirector`). `<name>` is required, `[name]` is optional, and `a|b` means choose one.

### Main commands

| Command | Description |
|---|---|
| `/pd create [count] [more\|replacement] [skip-blocked]` | Build the chosen formation around you. A count is for the random layout; `more` keeps existing portals, `replacement` removes them first, `skip-blocked` builds only what fits |
| `/pd create portal <name> here <north\|south\|east\|west> [force]` | Build one named portal where you stand |
| `/pd create portal <name> <x> <y> <z> <direction> [world] [force]` | Build one named portal at exact coordinates (add the world from the console) |
| `/pd list [page]` | Every portal's name, position, facing and Nether partner |
| `/pd remove <name>` | Remove one portal and its Nether partner, and restore the blocks |
| `/pd remove <x> <y> <z>` | The same, by the portal's anchor coordinates from `/pd list` |
| `/pd clear` | Remove all portals and restore the ground |
| `/pd stop` | Cancel a build, reveal or removal in progress |
| `/pd settings [tnt\|all]` | Show the current settings |
| `/pd help [page or topic]` | Clickable help; topics are create, examples, group, layouts, portals, reveal, scenes, sequence, set and tnt |
| `/pd reload` | Reload `config.yml` |

### `/pd set`

These shape what `/pd create` builds. They are separate from the settings of each sequence.

| Command | Description |
|---|---|
| `/pd set layout random\|grid\|square\|wave` | Choose the formation |
| `/pd set center here\|<x> <y> <z>\|reset` | Where it is built (default: where you stand) |
| `/pd set count <n>` | Random layout: how many portals |
| `/pd set grid rows <n>` / `columns <n>` | Grid size |
| `/pd set square per-side <n>` / `layers <n>` | Square size |
| `/pd set wave rows <n>` / `per-row <n>` | Wave size |
| `/pd set facing north\|south\|east\|west\|random` | Which way portals face, and which way TNT leaves. `inward\|outward` for the square |
| `/pd set mode ground\|air\|mixed` | Portals on the ground, floating, or half and half |
| `/pd set radius <n>` | How far from the center portals may spread (an automatic 5% is added) |
| `/pd set spacing horizontal <n>` / `vertical <n>` | Least gap between portals |
| `/pd set altitude-min <n>` / `altitude-max <n>` | Floating portals: height above the ground |
| `/pd set world-y-min <n>` / `world-y-max <n>` | Lowest and highest Minecraft Y any portal may use |
| `/pd set generation instant\|delayed` | Build everything at once, or one portal at a time |
| `/pd set generation delay <seconds>` | Seconds between portals when delayed |
| `/pd set generation order <order>` | Which portal appears next: `auto`, `bottom-to-top`, `top-to-bottom`, `left-to-right`, `clockwise`, `counterclockwise` or `random` |
| `/pd set generation effects on\|off` | Sound and particles as each portal appears |
| `/pd set <name> <value>` | Any other option, such as `max-portals` or `max-chunks`; `/pd settings all` lists them |

### `/pd group`

Commands that don't name a group use the only group.

| Command | Description |
|---|---|
| `/pd group create <name>` / `delete <name>` | Make or delete a group (portals are not touched) |
| `/pd group list` | Every group and its players |
| `/pd group info [name]` | Each player and the portal they are linked to |
| `/pd group assign-to-group <player> <group>` | Add a player to a group |
| `/pd group remove-from-group <player> <group>` | Remove a player and clear their portal link |
| `/pd group block <block>` | Players who step on this block join the group, for example `obsidian` |
| `/pd group link-to-portal <player> <portal>` | Give a player a portal, by name or by anchor `x y z` |
| `/pd group unlink-from-portal <player> <portal>` | Take the portal away from the player |
| `/pd group prepare [name]` | Teleport every online linked player into the middle of their portal |
| `/pd group visibility-in-portal true\|false` | `false` hides prepared players from others, `true` shows them in small staggered groups (default `true`) |
| `/pd group visibility-in-portal-delay <seconds>` | Pause between appearing groups (default 0.35) |
| `/pd group nether-travel true\|false` | `false` keeps linked players inside their own portal instead of traveling (default `false`) |

### `/pd sequence`

| Command | Description |
|---|---|
| `/pd sequence list` | The sequences, what they do and an example for each |
| `/pd sequence <name> info` | What it does, how to run it and its current settings |
| `/pd sequence <name> run <x y z \| here> ...` | Run it (`here` is the block you stand on; add a world name from the console) |
| `/pd sequence <name> set <option> <value>` | Change a setting of that sequence only |
| `/pd sequence <name> reset` | Restore its default settings |
| `/pd sequence status` | What is running or waiting, with progress |
| `/pd sequence stop` | Cancel the running sequence and release the players it holds |

### `/pd tnt`

| Command | Description |
|---|---|
| `/pd tnt launch <all\|floating\|ground\|side <direction>\|square\|portal names>` | Send a minecart out of the chosen portals, for example `/pd tnt launch EntranceA,EntranceB` |
| `/pd tnt wave <waves> <seconds>` | Several launches in a row, for example `/pd tnt wave 5 20` |
| `/pd tnt mode safe\|real` | Safe: minecarts fly but never explode. Real: they explode |
| `/pd tnt fuse <seconds\|off>` | Seconds after launch until they explode |
| `/pd tnt limit <n>` | Most minecarts launched in total, exploded or not (default 300) |
| `/pd tnt speed <n>` | Launch speed |
| `/pd tnt terrain-damage on\|off` | Whether explosions break blocks (default off) |
| `/pd tnt portal-protection on\|off` | Whether explosions can destroy portals (default on) |
| `/pd tnt status` | Mode, minecarts launched against the limit, and current settings |
| `/pd tnt stop` | Stop launching; minecarts already out keep going |
| `/pd tnt clear` | Remove every TNT minecart and start the launch count again |

## Built-in sequences

A sequence is a ready-made scene you run with one command. Each keeps **its own settings**, separate from `/pd set` and from the other sequences: look at them with `/pd sequence <name> info` and change them with `/pd sequence <name> set <option> <value>`.

| Sequence | What it does | Example |
|---|---|---|
| `nether-arrival` | Creates one portal and teleports a group beside its Nether-side partner. Players walk in when you tell them to. | `/pd sequence nether-arrival run here north` |
| `sky-army` | Generates floating portals and gradually reveals every group member inside them. | `/pd sequence sky-army run 100 120 200` |
| `tnt-strike` | Generates floating portals that launch randomized TNT minecarts (5 to 10 each by default). | `/pd sequence tnt-strike run here` |

`nether-arrival` and `sky-army` use a group: make one with `/pd group create <name>` and add players, then `/pd sequence <name> set group <group>` (not needed when only one group exists). `/pd sequence stop` ends a scene and sends players back where they were. Full details are in the [reference](docs/REFERENCE.md#sequences).

## Good to know

- **TNT:** `/pd tnt` launches real, exploding minecarts by default. Blocks and portals are protected unless you turn that off, but minecarts still hurt players and mobs. Use `/pd tnt mode safe` to rehearse. `tnt-strike` is safe unless you set `tnt-mode real`.
- **Portal travel:** `sky-army` players can't use portals until you run `/pd sequence stop`. Other portals work like vanilla portals, except that group members with `nether-travel false` stay inside their own linked portal.
- **Far portals:** the server only sends land up to its view distance, so very wide formations may look empty from a distance. Use a smaller formation or raise `view-distance`.

## Permissions

Administrative commands require **OP** by default. Regular players don't need OP to join the server, to step on a group's joining block or to take part in a scene as a group member. To make a trusted creator an operator, open the server console and type `op PlayerName` (no slash). OP grants broad server powers, so keep it for people you trust.

To give limited access instead, use a permissions plugin such as [LuckPerms](https://luckperms.net/), for example `lp user PlayerName permission set portaldirector.sequence true`.

| Permission | Default | Grants |
|---|---|---|
| `portaldirector.admin` | op | Everything below, and `/pd reload` |
| `portaldirector.generate` | op | `/pd create`, `list`, `remove`, `set`, `settings`, `stop` |
| `portaldirector.group` | op | `/pd group` |
| `portaldirector.scene` | op | `/pd clear` |
| `portaldirector.sequence` | op | `/pd sequence` |
| `portaldirector.tnt` | op | `/pd tnt` |

`/pd help` is available to everyone.

## More documentation

For developers: See the [Developer Guide](docs/DEVELOPER_GUIDE.md) for source code, building, and testing.
