# Changelog

Player-facing notes for each GreenCorps X+ release. Downloads: [Releases](https://github.com/qolarr-dev/GreenCorps-X-plus/releases).

## Unreleased

**Fixes**
- Map Duper+ no longer stops with "slot ... is not as planned" when copying bundles on 2b2t. 2b2t puts a stack back at the end of a bundle, not the front, and its answer can come seconds late when the server lags.
- Map Duper+ sends a click again when another mod blocks it instead of stopping with "the cursor is not as planned".
- Map Slot Preview pauses while Map Duper+ is running.

## 0.6.1: everything on 1.21.11, mapart walls without frames

**1.21.11 gets every module.** Tooltip+, Map Slot Preview, Map Duper+, Safe Shulker, Time Machine, Sign Search, Timed Commands, Gateways Esp, End Floor and Book Filler now run on 1.21.11 too. On 1.21.11 the End Floor and Time Machine walls are flat fills instead of the animated forcefield.

**New modules**
- **Item Frame Control**: filled frames lose their border (the map or item stays), empty frames get a small ESP box as an anchor; optional click-through (sneak to edit) and no-render for mapart or items.
- **Auto Loom**: paste a banner `/give` command (Tooltip+ copies one from any banner with Left Alt) and a panel beside the loom walks you through every layer.
- **Anti-Client** (early preview): reads incoming mace dives and crystal bursts and shows a survive-or-leave verdict. It only watches and logs for now.

**Bundles and maps**
- Maps inside a bundle in your inventory load on their own through a free hotbar slot, in order, under 2b2t's click limit.
- A map preview appears next to bundle tooltips (scroll, or pin with Left Alt and hover); size in Tooltip+ → *bundle-map-scale*.
- The selected bundle stack is clearly marked and named inside the tooltip; a pinned bundle looks like the hover one.
- An open shulker gets a **Bundle Sort** button.

**Safe Shulker**
- The preview is tinted in the shulker's colour.
- Middle-click works even when Meteor's Inventory Tweaks sorts on middle-click, and middle-clicking while holding a shulker opens it.
- Inventory Essentials' bulk moves work in the preview instead of crashing the game.

**Fixes**
- Book previews in Tooltip+ show the page again.
- The End gateway module is called **Gateways Esp** again; your settings are kept.
- Stasis Monitor switches itself back on after joining on 1.21.11, as on 1.21.4.

## 0.6.0: first public release

Where X+ stands today: 15 modules in the **GreenCorps** category of the Meteor GUI, for Minecraft **1.21.4** (all modules) and **1.21.11** (finders, Stasis Monitor and Discord Pings so far).

**Finders**
- **Breeze Finder**: trial spawners and ominous vaults in loaded chunks, with one Xaero waypoint per chamber.
- **Outpost Finder**: pillager outposts, scored by how flat the land around them is.
- **Solid Pocket**: dense underground pockets for spawn-proof farms, with history, ESP and alerts.
- **Sign Search (WDL)**: searches sign text and coordinates from the 2b2t.place world download. It needs the separate sign pack, which members can get in our Discord.

**Stasis & safety**
- **Stasis Monitor**: settled pearls per home, quotas, missing-pearl alerts, ESP and a chamber overview (`.stasis`).
- **Discord Pings**: private pings to your own webhook for low rockets or elytra, heavy damage, totem pops and deaths. It never sends coordinates.
- **Safe Shulker** (by fuxkingjosh): use a shulker from your inventory overhead, and it drops right back.

**Base**
- **Time Machine**: geofenced home backups, a timeline, and export to a playable singleplayer world. Item frames, maps, paintings and armour stands are saved too.
- **End Floor**: a void floor and helper grids in the End.
- **Timed Commands**: runs saved commands at a day time, world tick, or sunrise/sunset.

**Maps & items**
- **Map Duper+**: copies maps and bundles, and sorts mapart into reading order. It works in the inventory, crafting table and cartography table, and stays within 2b2t's click limit.
- **Map Slot Preview**: mapart right in your slots.
- **Tooltip+**: shulker and ender chest previews (the ender chest is remembered between sessions), full bundle contents, books, banners, item locations and file size.
- **Book Filler**: book-and-quill templates with title and signer.
- **Gateway ESP**: End gateway ESP, direction vectors, tracers and Xaero pins.

**Works with**: Xaero's Minimap / World Map (waypoints, optional), Baritone (`#` commands in Timed Commands, optional), and God's Eye Companion (a separate jar for the God's Eye map).
