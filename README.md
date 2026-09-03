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

## Requirements

- Java 25 (Adoptium/Temurin recommended)
- IntelliJ IDEA
- `HytaleServer.jar` (automatically provided via the
  `com.azuredoom.hytale-tools` Gradle plugin used in `build.gradle.kts`)

## Getting started in IntelliJ

1. Open this folder as a Gradle project in IntelliJ (`File > Open`).
2. Make sure the project SDK is set to Java 25.
3. Adjust in `gradle.properties`: `group`, `mod_id`, `mod_name`,
   `main_class`, `mod_author`.
4. Sync Gradle (IntelliJ will suggest it automatically, otherwise use the
   "Reload All Gradle Projects" button).
5. Prepare the dev environment (downloads the server, generates the
   manifest, etc.):
   ```
   ./gradlew setupHytaleDev
   ```
6. Build the plugin:
   ```
   ./gradlew build
   ```
   The JAR is in `build/libs/`.
7. To test locally:
   ```
   ./gradlew runServer
   ```
   or copy the built JAR into an existing Hytale server's `mods/` folder.

## Project structure

```
hytale-blockregen/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── src/main/java/com/nopefr/blockregen/
│   ├── BlockRegenPlugin.java        # main class: command, listener, chat listening, persistence
│   ├── BlockRegenCommand.java       # /blockregen "<block>" <duration> [--remove] [--floor] [radius] command
│   ├── BlockRegenListCommand.java   # /blockregen list subcommand (opens BlockRegenListPage for a player)
│   ├── BlockRegenAdminCommand.java  # /blockregen admin subcommand (anti-abuse bypass toggle)
│   ├── BlockRegenListPage.java      # custom UI window listing the rules (icon, editable delay, S/M/H, floor, radius, delete)
│   ├── BlockRegenTargetCommand.java # /blockregen <duration> variant (targeted block + confirmation)
│   ├── BlockRegenListener.java      # listens for block breaks, schedules regeneration, spawns the ghost
│   ├── BlockRegenPlaceListener.java # listens for block placement: cancels pending regen, anti-abuse marking
│   ├── BlockRegenGhostMarker.java   # marker component for ghost preview entities
│   ├── BlockRegenGhostVisibilitySystem.java # hides ghosts from players without the dedicated permission
│   └── DurationParser.java          # duration parsing/formatting (h/m/s, minutes by default)
└── src/main/resources/Common/UI/Custom/Pages/BlockRegen/
    ├── BlockRegenListPage.ui        # window layout (container + scrollable list)
    └── BlockRegenEntryRow.ui        # row layout (icon, name, delay, S/M/H, floor, radius, X button)
```

## Things to check / adapt (important)

Hytale is in early access and its modding API evolves fast (regular
updates). This code was compiled and verified against the decompiled
sources + JavaDoc of server 0.6.3, but might need small adjustments on a
future version:

- **`build.gradle.kts`**: the exact syntax of the `hytaleTools { ... }`
  block from the `com.azuredoom.hytale-tools` plugin may differ slightly
  depending on its version. The plugin's wiki
  (`github.com/AzureDoom/Hytale-Gradle-Plugin/wiki`) is the reference.
- **Persistence**: rules (`/blockregen`) are saved to disk
  (`BlockRegenRules.json` file in the plugin's data folder) via the
  plugin's native `Config`/`Codec` system, and reloaded automatically on
  server startup. Only pending regenerations (block broken, timer
  running) and pending Y/N confirmations are lost if the server restarts
  before they complete.
- **Permission**: the command automatically generates the permission node
  `com.nopefr.blockregen.command.blockregen` (adjust based on your
  `group`/`mod_id`). Grant this permission to the relevant players/roles,
  otherwise only the console will be able to use it.

## Possible improvements

- Store the "original" block before replacement (if you want to replace
  it with a different temporary block, e.g. break -> empty spot -> stone
  after 120s).
- Also persist pending regenerations and confirmations (see above).
