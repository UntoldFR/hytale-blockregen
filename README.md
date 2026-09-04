# BlockRegen (Hytale plugin)

Hytale server plugin that lets you, via the `/blockregen` command,
automatically regenerate a block type after a delay once it has been
broken.

Compatible with Hytale `>=0.6.3 <0.7.0`.

## In-game usage

```
/blockregen "Stone" 120
```
-> Every broken "Stone" block reappears 120 seconds later (120 = minutes
by default when no unit is specified -> here, 120 minutes).

```
/blockregen "Stone" 90s
/blockregen "Stone" 5m
/blockregen "Stone" 2h
```
-> Same thing, with a delay explicitly expressed in seconds (`s`),
minutes (`m`) or hours (`h`).

```
/blockregen "Poppy" 120 --floor --radius 3
```
-> Same thing, but the block only regenerates if there's a solid block
directly beneath it (`--floor`), and can respawn at a random valid spot
within 3 blocks of the original break point (`--radius`).

```
/blockregen "Wood_Oak_Trunk" 300 --regrowth
```
-> Same thing, but instead of respawning the exact log, it tries to plant
a matching sapling if there's still a grass floor underneath at the time
it regenerates (see [Tree regrowth](#tree-regrowth-regrowth) below).

```
/blockregen "Stone" 0 --remove
```
-> Removes the regeneration rule for "Stone".

```
/blockregen list
```
-> Shows the list of blocks currently configured to regenerate, with the
delay (and floor/radius options) associated with each. In the "add a new
rule" block picker, any block that already has a rule in the currently
selected scope is marked with a `[Set]` label prefix and a tooltip, so a
long block list stays easy to scan.

```
/blockregen 90s
```
-> If no block name is given (only a delay), the plugin looks up the
block the player is currently looking at (within 10 blocks) and asks for
confirmation in chat:
`Confirm setting a regeneration delay of 90s on block "Stone"? Reply Y to confirm or N to cancel.`
The player must reply `Y` (yes) or `N` (no) in chat to confirm or cancel.
This variant requires being run by a player (not the console).

The block name must match the exact identifier used by the game (e.g.
`Stone`, `Dirt`, `MyPlugin_MossyBlock`...). If the name is invalid, the
command returns an error.

```
/blockregen help
```
-> Lists every `/blockregen` command with a one-line summary.

### Anti-abuse: blocks placed by a player don't regenerate

By default, if a player places a block whose type has an active
regeneration rule, that block will never be scheduled to regenerate if
later broken (by anyone) - even though a rule exists for its type. This
prevents a player from placing then repeatedly breaking a ruled block to
farm it for free. "Natural" blocks (already present in the world,
normally generated) are not affected and regenerate as usual.

```
/blockregen admin
```
-> Toggles, for the player running the command, a "bypass" mode: while
it's enabled, blocks they place remain eligible for regeneration (useful
for an admin who wants to rebuild an ore vein or a decoration). The new
state is confirmed with a yellow chat message and an on-screen
notification. See [Permissions](#permissions) below for the required
permission node.

### Ghost preview of blocks awaiting regeneration

As soon as a configured block is broken, a transparent, collision-free
preview of the upcoming block appears at its location, with floating text
above it showing the remaining time ("Regenerates in 45s"). This preview
is only visible to players with the dedicated permission (see
[Permissions](#permissions) below) - other players simply see the empty
spot, as usual.

### Tree regrowth (`--regrowth`)

When enabled on a log's rule, a broken log doesn't respawn as the exact
same log - instead, at regen time, BlockRegen tries to plant a sapling
of the matching tree species:

- The log's species is read straight from its block id (`Wood_<Species>_Trunk...`)
  and matched to a sapling with the same species (`Plant_Sapling_<Species>`),
  verified against the game's own block registry - no manual list to
  maintain, and it just works for any tree species the game adds later.
- A sapling only gets planted if there's still a "grass" floor block
  (any `Soil_Grass*` variant) directly beneath the target spot at the
  moment the timer fires - plain dirt doesn't count. If the floor isn't
  grass, or the log's species has no matching sapling (a few don't, e.g.
  fir), it just falls back to respawning the plain log as usual.
- Works together with `--floor`/`radius`: the target spot is picked by
  the same existing logic, and the sapling (or log) is placed wherever
  that lands - so a scattered log rule scatters saplings too.
- Toggle it per row (or in the add panel) in `/blockregen list` the same
  way as the Floor toggle.

### CustomAreas integration (optional)

If CustomAreas is also
installed on the server, BlockRegen automatically registers a
`BLOCKREGEN` flag for it - nothing to configure, and nothing changes if
CustomAreas isn't installed.

Tag an area with it (`/carea <name> flag add BLOCKREGEN`) and it gets its
own rule set in the `/blockregen list` window: a dropdown at the top lets
you switch between "Global" and any `BLOCKREGEN`-flagged area. An area
**inherits every global rule**, but can override the delay/floor/radius
for specific blocks, or add rules for blocks that have no global rule at
all. Toggling **Independent** next to the dropdown makes that area ignore
global rules entirely and use only its own.

Example: `Stone` regenerates after 120s everywhere by default; setting
`Stone` to 5s while "Area1" is selected makes only that area's `Stone`
blocks regenerate in 5s - everywhere else stays at 120s.

## Permissions

Each command/subcommand generates its **own** permission node (chained
off the base one), so a player may need more than just the root node
depending on what your permission plugin does with dotted hierarchies -
if it supports wildcards, granting `com.nopefr.blockregen.*` covers
everything below at once.

| Permission | Grants |
|---|---|
| `com.nopefr.blockregen.command.blockregen` | `/blockregen "<block>" <duration> [...]` and the `/blockregen <duration>` targeted variant |
| `com.nopefr.blockregen.command.blockregen.list` | `/blockregen list` |
| `com.nopefr.blockregen.command.blockregen.admin` | `/blockregen admin` |
| `com.nopefr.blockregen.command.blockregen.help` | `/blockregen help` |
| `com.nopefr.blockregen.ghosts` | Seeing the ghost preview markers (independent of the command permissions above - handy for admins/OPs who shouldn't necessarily edit rules) |

Without any permission granted, only the console can use `/blockregen`.

## Persistence

Rules set via `/blockregen` are saved to disk and reloaded automatically
on server startup. Regenerations already in progress (block broken,
timer running) are saved too and resume automatically on the next
startup - a block whose timer fully elapsed while the server was down
regenerates promptly once it comes back up. Each admin's `/blockregen
admin` bypass state is saved as well, so it survives both a
disconnect/reconnect and a server restart. The one thing still lost on
restart is a pending Y/N chat confirmation (`/blockregen <duration>`).

Known limitation: a world that hasn't finished loading yet by the time
the plugin starts has its pending regenerations skipped rather than
retried later - not an issue for the default/only world on a typical
single-world server, but worth knowing if you run multiple worlds that
load lazily.
