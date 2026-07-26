
---

>[!IMPORTANT]
>:warning: This project is searching for a new maintainer. If you are interested, please get in touch. See [#1144](https://github.com/toolbox4minecraft/amidst/issues/1144).

---

Amidst
======

[![Build Status](https://travis-ci.org/toolbox4minecraft/amidst.svg?branch=master)](https://travis-ci.org/toolbox4minecraft/amidst)

## What is Amidst?

Amidst is a tool to display an overview of a Minecraft world, without actually creating it.

## GTNH overworld biome prototype

The `codex/gtnh-overworld-biomes` branch contains the first GT New Horizons
integration slice. It renders the overworld biome distribution returned by a
running GTNH/RWG instance, including biome registration and configuration
changes made by Biomes O' Plenty and other installed mods. See
[docs/gtnh-overworld-biomes.md](docs/gtnh-overworld-biomes.md) for the design,
build and launch instructions.

After packaging, start this branch with:

```text
java -jar target/amidst-gtnh-biomes-v0-1-v13.jar -gtnh-worker
```

The main application requires Java 17 or newer. On Windows,
`run-gtnh-amidst.bat` adds `-gtnh-worker` automatically and forwards any extra
worker port/token arguments. Set `AMIDST_JAVA_HOME` when GTNH itself uses a
different Java runtime.

GTNH maps use a readable built-in terrain palette. Pass
`-gtnh-colors gtnh-biome-colors.json` to override colors by biome name or ID;
see `gtnh-biome-colors.example.json`. Forge's `OCEAN` tag and Ocean names never
trigger blue because modded land biomes can carry that classification.
`RIVER` biomes use a light-blue-to-teal temperature scale, including RWG's ice,
cold, temperate, hot, wet, and oasis rivers. The worker samples RWG's final
river replacement without generating or saving chunks. Default land colors,
including snowy land, avoid blue hues so rivers remain visually distinct.
The worker starts at the GTNH client main menu, so no save has to be opened.
Amidst initially displays a random seed; `New From Seed` and
`New From Random Seed` can replace and fully refresh the biome map at any time.
Arbitrary previews use save-free, chunk-free RWG sampling contexts.

The dimension menu also includes a dedicated Twilight Forest view. Its biome
map reads the mod's raw quarter-resolution generation layer at the correct
1:4 world-coordinate scale and uses the same biome colors as the Magic Map.
The `Magic Map Landmarks` submenu forecasts exact feature-chunk centers and
lets every landmark type be switched independently. Its markers come from
Twilight Forest's own Magic Map and block textures, covering hollow hills,
mazes, boss structures, progression landmarks, and the final castle. The
worker reports the pack's configured Twilight Forest dimension ID rather than
assuming that it is always dimension 7. Implementation notes and texture
attribution are in
[docs/gtnh-twilight-forest.md](docs/gtnh-twilight-forest.md).

The same map can now display the BOP Nether biome distribution from
`Layers -> Dimension -> Nether` (shortcut: Ctrl/Cmd+Shift+2). Nether queries
are isolated from the RWG overworld path. When GTNH registers
`WorldProviderBOPHell` for dimension `-1`, arbitrary seeds use BOP's complete
`BiomeLayerHell` chain through its save-free
`WorldChunkManagerBOPHell(long, WorldType)` constructor. Loaded Nether chunks
remain authoritative. Non-BOP providers retain the vanilla Hell fallback.
The command-line preview accepts `--dimension nether`.
It also accepts `--dimension end`.

The Nether view now has dedicated structure toggles for vanilla Nether
fortresses, Tinkers' Construct Nether slime-island candidates, and Automagy
Nether Spires. Fortress positions use Minecraft 1.7.10's exact 16-chunk
region algorithm. Tinkers' markers replay the running pack's dimension
whitelist and island rarity, but show an approximate center because the mod's
island shape uses an independently seeded random field. Automagy markers
replay the configured `worldgen_nether_spire_chance` and exact seeded X/Z
choice; they remain `Possible` because the final generator requires a
suitable lava pool and enough air above it.

The End is available from `Layers -> Dimension -> End` (shortcut:
Ctrl/Cmd+Shift+3). Its biome map distinguishes the central End, void, and
Hardcore Ender Expansion's Infested Forest, Burning Mountains, and Enchanted
Island variants. Dedicated layers show HEE biome-island centers, HEE dungeon
towers, and Draconic Evolution chaos islands. HEE locations and biome choices
exactly replay the mod's random stream for a fresh End (zero previous dragon
deaths); the colored 208-block island footprint is an approximate map outline
because the final terrain is procedurally carved. Chaos-island centers use the
running pack's enable flags and configured grid separation exactly.

The Galacticraft Moon is available from `Layers -> Dimension -> Moon`
(shortcut: Ctrl/Cmd+Shift+5), and the command-line preview accepts
`--dimension moon`. The worker reads Galacticraft's configured Moon dimension
ID and displays its runtime-registered single biome as `Moon` with a stable
lunar-gray color. Dedicated layers locate Moon dungeons and Moon villages.
Dungeon entrance chunks exactly replay Galacticraft's 44-chunk grid, including
the world seed, dimension ID, and dungeon salt. Village starts replay the
mod's own 32-chunk selection rule and disappear when
`disableMoonVillageGen` is enabled. Details are in
[docs/gtnh-moon.md](docs/gtnh-moon.md).

The dimension menu also exposes Mars, Asteroids, Ceres, Io, Enceladus,
Proteus (海卫八), and Pluto using the configured runtime dimension IDs. Mars
locates cavern starts and dungeons; Asteroids locates hollow asteroids and
separates abandoned bases into human, mechanical, and bird markers; the five
GalaxySpace worlds expose their own dungeon markers. Io and Pluto use their
real seeded multi-biome managers, while the genuinely single-biome space
worlds display their registered biome across the map. Every marker uses the
requested Galacticraft, GalaxySpace, or vanilla block texture. Algorithm and
compatibility details are in
[docs/gtnh-space-dimensions.md](docs/gtnh-space-dimensions.md).

The dimension menu additionally exposes Amun Ra's Mehen Belt and BartWorks'
Ross 128b using their runtime-configured IDs. Mehen displays the Galacticraft
asteroid biome and locates asteroids whose selected core is dark matter, using
Avaritia's Stellar Fuel as the marker. Ross 128b displays its seeded vanilla
biome map and has independent layers for possible ruins and possible
arsenopyrite ore veins. The Nether also has a possible molybdenum-vein layer.
GT ore markers replay the loaded world's ore-grid mode, dimension chance,
attempt count, and live weighted ore-mix registry; they remain `Possible`
because terrain and stone placement checks occur after ore-mix selection.

GTNH maps also predict possible Roguelike Dungeons 1.6.6-GTNH entrances.
Each marker includes the selected dungeon terrain/type and entrance style
(Desert/Pyramid, Forest/House, Ice/Bunker, Jungle, Mesa/Etho,
Mountain/Eniko, Plains/Rogue, or Swamp/Witch). Open
`Layers -> Possible Roguelike Dungeons` to show or hide each type
independently. These markers replay the seed, configuration, RWG biome and
dungeon-settings selection without generating chunks. They are intentionally
labelled “Possible”: the mod's final 9x9 ground/air test reads generated
terrain blocks and can move or reject an entrance.
The same worker predicts the structures RWG itself enables: its 24-chunk
village grid with RWG's terrain-flatness check, vanilla-style mineshafts
(when enabled in `RWG.cfg`), and RWG's three unrelocated strongholds.
Each category has its own `Layers` toggle. Vanilla temple/witch-hut markers
remain disabled because RWG's chunk generator does not invoke the vanilla
scattered-feature generator.

LootGames game dungeons and Tinkers' Construct blue slime islands are also
predicted from the running pack's real configuration. Their independent
`Layers` toggles use the LootGames dungeon-wall texture and Tinkers' blue
edible-slime-ball texture. LootGames markers are labelled `Possible` because
the final generator checks terrain blocks. Slime-island markers show the
stable approximate center of the selected generation footprint.

The `Layers` menu also contains GTNH-specific predictions for vanilla
spawner dungeons, Thaumcraft 4 aura nodes, and Thaumcraft Eldritch Altars.
Spawner-dungeon labels include the predicted Y level and use the vanilla
spawner texture. Their expensive cave-mask query is independent and disabled
by default. Aura nodes use Thaumcraft's node research icon; Eldritch Altars
use the matching obsidian-tile texture. These three markers are labelled
`Possible`: block-level cave, terrain, tree, and mod-event checks can still
reject or move the final generated structure.

AE2 meteorites are predicted from the running pack's feature flags,
dimension whitelist, per-dimension minimum distance and spawn-depth chance.
The marker uses AE2's Certus Quartz Crystal item texture. Candidate X/Z positions
exactly replay AE2's seeded 707-block grid in the default GTNH configuration;
they remain labelled `Possible` because AE2's final height and block checks
require generated terrain. GTNH arbitrary-seed previews also show a predicted
RWG world spawn on a dedicated, default-enabled `GTNH World Spawn` layer.
Saved worlds continue to use the authoritative spawn stored in `level.dat`.
Quarter-resolution map pixels are internally supersampled at four positions,
improving RWG biome borders and narrow river visibility without quadrupling
the rendered fragment size.

Amidst **can**:

* render an overview of a world from a given seed and Minecraft version
* save an image of the map
* use a save game
* display biome information
* display slime chunks
* display end islands
* display the following structures
  * world spawn
  * strongholds
  * villages
  * witch huts
  * pillager outposts
  * jungle temples
  * desert temples
  * igloos
  * abandoned mine shafts
  * ocean monuments
  * ocean ruins
  * shipwrecks
  * buried treasures
  * nether fortresses
  * end cities

Amidst **cannot**:

* display changes that were applied to a save game like
  * changes made by world editors like MCEdit
  * changes made while loading the world in Minecraft
* find individual blocks or mobs like
  * diamond ore
  * cows

## Amidst has found a new home

Amidst was moved to a new location, since Skidoodle aka skiphs is too busy to maintain it. It has also found some new developers. One of them is DrFrankenstone, a.k.a. Treer, who is the developer of AmidstExporter. Skidoodle is still an owner of Amidst and agreed to move the project.

### Links

* [Download](https://github.com/toolbox4minecraft/amidst/releases)
* [FAQ](https://github.com/toolbox4minecraft/amidst/wiki/FAQ)
* [Wiki](https://github.com/toolbox4minecraft/amidst/wiki)
* [Reporting a Bug](https://github.com/toolbox4minecraft/amidst/wiki/Supporting-the-Development#reporting-a-bug) - please report bugs, so we can fix them
* [Requesting a Feature](https://github.com/toolbox4minecraft/amidst/wiki/Supporting-the-Development#requesting-a-feature)
* [Thread in the minecraftforum](https://www.minecraftforum.net/forums/mapping-and-modding-java-edition/minecraft-tools/2970854-amidst-map-explorer-for-minecraft-1-14)
* [Project Page](https://github.com/toolbox4minecraft/amidst)
* [Supporting the Development](https://github.com/toolbox4minecraft/amidst/wiki/Supporting-the-Development)
* [License Text](https://github.com/toolbox4minecraft/amidst/blob/master/LICENSE.txt)

## What is my internet connection used for?

* Amidst **will** use web services provided by Mojang, e.g. to
  * display information about Minecraft versions.
  * display information about players like the name or the skin.
* Amidst **will** check for updates on every start.
* Amidst **will not** track you with Google Analytics, which was the case in older versions.

## Legal Information

* Amidst is **not** owned by or related to Mojang in any way.
* Amidst comes with **absolutely no warranty**.
* Amidst is free and open source software, licensed under the GPLv3.

## Screenshots

These screenshots are created from the seed 24922 using Amidst v4.0 and Minecraft 1.9.

![default](https://raw.githubusercontent.com/wiki/toolbox4minecraft/amidst/screenshots/screenshot_default_24922_default.png)

### The End Dimension

![The End Dimension](https://raw.githubusercontent.com/wiki/toolbox4minecraft/amidst/screenshots/screenshot_default_24922_end.png)

### Biome Highlighter

![Biome Highlighter](https://raw.githubusercontent.com/wiki/toolbox4minecraft/amidst/screenshots/screenshot_default_24922_biome-highlighter.png)

### Grid

![Grid](https://raw.githubusercontent.com/wiki/toolbox4minecraft/amidst/screenshots/screenshot_default_24922_grid.png)

### Slime Chunks

![Slime Chunks](https://raw.githubusercontent.com/wiki/toolbox4minecraft/amidst/screenshots/screenshot_default_24922_slime.png)
