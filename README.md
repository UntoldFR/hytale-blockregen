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
/blockregen "Stone" 0 --remove
```
-> Removes the regeneration rule for "Stone".

```
/blockregen list
```
-> Shows the list of blocks currently configured to regenerate, with the
delay (and floor/radius options) associated with each.

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
notification. Requires the auto-generated permission
`<basePermission>.command.blockregen.admin`.

### Ghost preview of blocks awaiting regeneration

As soon as a configured block is broken, a transparent, collision-free
preview of the upcoming block appears at its location, with floating text
above it showing the remaining time ("Regenerates in 45s"). This preview
is only visible to players with the `<basePermission>.ghosts` permission
(e.g. `com.nopefr.blockregen.ghosts`) - other players simply see the
empty spot, as usual. Grant this permission to your admin/OP group to use
it.

### CustomAreas integration (optional)

If [CustomAreas](https://github.com/UntoldFR/hytale-customareas) is also
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

- `com.nopefr.blockregen.command.blockregen` (and its subcommands, e.g.
  `...blockregen.admin`, `...blockregen.list`): required to use
  `/blockregen`. Grant it to the relevant players/roles, otherwise only
  the console will be able to use it.
- `com.nopefr.blockregen.ghosts`: lets a player see the ghost preview
  described above. Grant it to your admin/OP group.

## Persistence

Rules set via `/blockregen` are saved to disk and reloaded automatically
on server startup. Only regenerations already in progress (block broken,
timer running) and pending Y/N chat confirmations are lost if the server
restarts before they complete.
