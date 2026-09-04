# Changelog

All notable changes to the plugin are documented here.

## [1.2.2]

### Fixed
- Fixed a crash on startup/reconnect that could silently break block
  regeneration and the ghost preview entirely whenever there was at
  least one pending regeneration to restore (introduced in 1.2.0). The
  server's `Universe` isn't always ready by the time plugins start, and
  restoring pending regenerations now waits for it instead of crashing.

## [1.2.1]

### Added
- Each admin's `/blockregen admin` bypass state is now saved to disk, so
  it survives both a disconnect/reconnect and a server restart (it used
  to reset on either).

## [1.2.0]

### Fixed
- Placing a block where one was waiting to regenerate no longer
  disconnects the player - the pending regeneration is now cancelled
  through the correct game-safe path instead of corrupting the block
  placement in progress.

### Added
- **Regenerations now survive a server/world restart.** Previously, any
  block still waiting to regenerate was silently lost if the server
  stopped before its timer finished (especially noticeable with long
  delays, or on single-player worlds that restart often). Pending
  regenerations are now saved to disk and resumed automatically:  a
  timer that already ran out while the server was down fires promptly on
  the next startup, and one still counting down (ghost preview included)
  picks up where it left off.
- **"Already configured" indicator** in the "add a new rule" block
  picker: a block that already has a rule in the currently selected
  scope is marked with a `[Set]` label prefix and a tooltip, so a long
  block list stays easy to scan (not relying on color alone, for
  colorblind accessibility).

### Changed
- The list of already-configured blocks in `/blockregen list` is now
  sorted alphabetically by the name shown on screen, rather than by the
  block's internal identifier.

## [1.1.2]

### Changed
- The Floor toggle button (in each rule row and the "add a new rule"
  panel) now shows the label "Floor" instead of "ON"/"OFF" - the
  on/off state is still shown via its color.

## [1.1.1]

### Added
- **`/blockregen help`**: lists every `/blockregen` command with a
  one-line summary.

## [1.1.0]

### Added
- **Optional CustomAreas integration**: if CustomAreas is
  also installed, tag any region with the `BLOCKREGEN` flag
  (`/carea <name> flag add BLOCKREGEN`) to give it its own regeneration
  rules. The `/blockregen list` window gains a Global/area dropdown: an
  area automatically inherits every global rule, but can override or add
  rules for specific blocks. An **Independent** toggle next to the
  dropdown lets an area ignore global rules entirely and use only its
  own. Nothing changes if CustomAreas isn't installed on the server.

## [1.0.3]

### Fixed
- Fixed a corrupted character in the `/blockregen admin` chat message (it
  could show up as `â?"` on some clients).

## [1.0.2]

### Added
- **Anti-abuse protection**: a block placed by a player no longer
  regenerates when broken, even if a regeneration rule exists for its
  type. This stops players from placing a ruled block just to farm it.
  Only naturally-occurring world blocks (never placed by a player) are
  affected by regeneration rules.
- **`/blockregen admin`**: toggles a personal bypass for admins — while
  enabled, blocks placed by that admin stay eligible for regeneration
  (useful for rebuilding an ore vein or a decoration). The new state is
  confirmed with a yellow chat message and an on-screen notification.

## [1.0.1]

### Fixed
- If a player places a new block where a broken block used to be before
  its regeneration timer runs out, the pending regeneration is now
  cancelled instead of later overwriting the block the player just
  placed.

### Added
- **"Need floor" option**: new `--floor` flag on `/blockregen`, plus a
  matching toggle in the `/blockregen list` window. When enabled, the
  block only regenerates if there's a solid block directly beneath the
  target position.
- **Random scatter radius**: new `radius` option on `/blockregen`, plus a
  matching field in the UI. Lets a block regenerate at a random valid spot
  within X blocks of the original break point (e.g. a flower respawning
  slightly further away), never overwriting a block that's already there.

## [1.0.0]

- Initial release: `/blockregen` command, per-block-type configurable
  regeneration delay, `/blockregen list` window, ghost preview with
  countdown.
