# Changelog

All notable changes to the plugin are documented here.

## [1.0.1]

### Fixed
- **Ghost regeneration**: if a player places a new block where a broken
  block used to be before its regeneration delay ends, the pending
  regeneration is now cancelled (the ghost marker and its scheduled task
  are removed) instead of later overwriting the block the player just
  placed.

### Added
- **"Need floor" option**: new `--floor` flag on `/blockregen`
  (`/blockregen "Poppy" 120 --floor`), plus a matching ON/OFF button in the
  `/blockregen list` window (per row and in the add panel). When enabled,
  the block only regenerates if there's a support (a non-air block)
  directly beneath the target position; otherwise that regeneration cycle
  is simply skipped.
- **Random scatter radius**: new `radius` argument on `/blockregen`
  (`/blockregen "Poppy" 120 --radius 3`), plus a matching number field in
  the UI. Lets a block regenerate at a random valid spot within X blocks of
  the original break point (e.g. a flower respawning slightly further
  away), respecting "need floor" when enabled and never overwriting a
  block that's already there.

### Technical
- New file `BlockRegenPlaceListener.java`: listens for block placements
  (`PlaceBlockEvent`) to trigger the cancellation above.
- `BlockRegenPlugin` now tracks each pending regeneration by position
  (world + coordinates) so it can be cancelled individually.
- New optional `NeedFloor` and `Radius` keys in `BlockRegenRules.json`,
  alongside the unchanged `Rules` (delays) key: no migration needed,
  existing configs keep working as-is.

## [1.0.0]

- Initial release: `/blockregen` command, per-block-type configurable
  regeneration delay, `/blockregen list` window, ghost preview with
  countdown.
