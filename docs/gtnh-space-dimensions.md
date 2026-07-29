# GTNH Galacticraft and GalaxySpace dimensions

The worker reads every dimension ID from the running mods rather than assuming
the defaults. Biome and structure requests also carry a stable logical
dimension key, so two configured dimensions may share an integer ID without
selecting the wrong sampler. Integer IDs are retained for mod algorithms and
JourneyMap interoperability. The defaults in the tested GTNH versions are:

| Map | Runtime configuration field | Default |
| --- | --- | ---: |
| Mars | `ConfigManagerMars.dimensionIDMars` | -29 |
| Asteroids | `ConfigManagerAsteroids.dimensionIDAsteroids` | -30 |
| Ceres | `GSConfigDimensions.dimensionIDCeres` | -1007 |
| Pluto | `GSConfigDimensions.dimensionIDPluto` | -1008 |
| Io | `GSConfigDimensions.dimensionIDIo` | -1013 |
| Enceladus | `GSConfigDimensions.dimensionIDEnceladus` | -1016 |
| Proteus (海卫八) | `GSConfigDimensions.dimensionIDProteus` | -1019 |
| Barnarda C | `GSConfigDimensions.dimensionIDBarnardaC` or legacy `BRConfigDimensions.dimensionIDBarnardaC` | -1022 / -1050 |
| Mehen Belt | `AmunRa.config.dimMehen` | 25 |
| Deep Dark | `ExtraUtils.underdarkDimID` | 100 in GTNH |
| Anubis | `AmunRa.config.dimAnubis` | 22 |
| Horus | `AmunRa.config.dimHorus` | 23 |
| Ross 128b | `Configuration.crossModInteractions.ross128BID` | 64 |

## Biome maps

The worker keeps each galaxy dimension's runtime biome IDs internal and
remaps sampled results into a private display-ID range for that dimension.
Moon, Mars, Asteroids, Io, Pluto, Mehen Belt, Barnarda C, Anubis, Horus, and
Ross 128b therefore cannot overwrite one another in AMIDST's global biome
catalog even when their mods register the same integer biome ID. All scoped
display IDs are included in the worker handshake before sampling begins, so
switching repeatedly between galaxy dimensions cannot produce an
unregistered-biome error. Ceres, Enceladus, and Proteus retain their dedicated
synthetic IDs `3100` through `3102`.

Mars and Asteroids each expose their single Galacticraft biome. GalaxySpace
registers Ceres, Enceladus, and Proteus with one shared `Space` biome ID; the
preview instead assigns the three maps private display-only IDs (`3100`,
`3101`, and `3102`). This prevents a registry collision from making Enceladus
appear as another mod's biome, without changing world generation. Io uses
`WorldChunkManagerIo` and displays both `Io` and `IoAsh`; Pluto uses
`WorldChunkManagerPluto` and displays its four seeded Pluto biomes. The worker
constructs the Io and Pluto managers with the selected world seed, so biome
borders are not inferred from the currently loaded server world.
Mehen Belt uses Galacticraft's registered asteroid biome. Ross 128b constructs
the same seeded vanilla `WorldChunkManager` selected by its world provider.
Barnarda C constructs GalaxySpace's `WorldChunkManagerBarnardaC` with the
selected seed and exposes Shores, Hills, Low Plains, Flowers, and Oceans.
The worker detects both GalaxySpace package layouts and supports its `(long)`
and legacy `(long, WorldType)` manager constructors.
The Deep Dark uses its provider's seeded vanilla biome manager. Anubis and
Horus both display the registered `Space` biome.

Barnarda C uses a dedicated high-contrast palette: shores `#32105F`, oceans
`#4B4FB5`, flowers `#D02A9F`, low plains/forest `#7B3FA1`, and hills
`#777982`.

## Structure markers

- Mars caverns replay `MapGenCavernMars`: the world seed produces two long
  multipliers, each source chunk is reseeded with the original XOR formula,
  and a cavern starts when `nextInt(100) == 0`. The icon is the exact X/Z
  cavern-node origin.
- Galacticraft dungeons use 44-by-44-chunk regions, salt `4291754`, and the
  dimension ID in the random seed. This covers Mars, Enceladus, Proteus, and
  Pluto.
- GalaxySpace's Ceres and Io dungeon generator uses the same 44-chunk spacing
  but salt `4291726` and no dimension term. This intentional difference is
  preserved.
- Hollow asteroids replay Galacticraft 1.7.10's `Billowed` density generator,
  integer point hash, asteroid-center random seed, size gate, and all random
  calls made before the hollow test. The marker is the asteroid center.
- Mehen dark-matter asteroids use the same center selection and then replay
  Amun Ra's 110-point core table. Dark matter is the final one-point entry, so
  this is an exact core selection rather than a proximity guess.
- Deep Dark dungeons replay Extra Utilities' `WorldGenCastle`: every
  512-by-512-block region selects one center from the inclusive inner
  390-by-390 area using `worldSeed + regionX + 65535 * regionZ`.
- Anubis robot villages replay Amun Ra's 32-by-32-chunk region selector with
  salt `1098540180186541`, followed by the structure RNG's exact in-chunk X/Z
  offsets.
- Horus obsidian pyramids replay the same region selector with salt
  `549865610521`; their markers use the generator's start-chunk anchor.
- Ross 128b ruins replay its `XSTR` chunk-population seed, biome exclusions,
  ruin chance, and coordinate rolls. Lake population, Forge events, and final
  ground checks depend on generated terrain, so the markers are `Possible`.
- Nether molybdenum and Ross 128b arsenopyrite markers detect the loaded
  GT5U generation API. GTNH 2.8.x replays the legacy XSTR/global-list
  selection (and GalacticGreg's Forge chunk RNG for Ross 128b); newer GT5U
  replays its dimension query, chance, first FNV-1a selection seed, and live
  weighted registry. GT only reaches later selection attempts after the
  chosen vein fails block-level terrain checks. The preview intentionally
  omits those terrain-dependent fallback draws instead of merging every retry
  into an inflated “possibly selected” result. If neither known API is
  present, the worker logs the incompatibility and returns no ore markers
  instead of failing the dimension request. A matching ore seed chunk remains
  `Possible` because GT can still reject it after height and host-stone checks.
- End Naquadah, Scheelite, and Platinum asteroid markers detect the same
  runtime split. Current GalacticGreg replays its per-chunk seed, configured
  probability, central-End/HEE/chaos exclusions, Endstone-or-Marble choice,
  one-in-five small-ore branch, filtered live `WorldgenQuery` weight, and
  center coordinate rolls. Legacy GT5U replays the old configured probability
  plus its global weighted-list retry behavior.

The tested GTNH Galacticraft 1.7.10 build does not contain an abandoned-base
generator. The three abandoned-base layers therefore implement Galacticraft
4's official deterministic logical start-chunk and base-type selection:
36-chunk regions, salt `10387340 + dimension`, followed by the structure RNG's
`nextInt(5)` and `nextInt(3)`. Markers remain stable across map fragments and
are separated as human, bird, or mechanical. They are forward-compatible
predictions rather than a claim that the unmodified 1.7.10 provider will place
those bases.

## Marker textures

- Mars cavern: `galacticraftmars:textures/blocks/vine_0.png`
- Mars dungeon: `galacticraftmars:textures/blocks/brick.png`
- Hollow asteroid: vanilla `grass_side.png`
- Human base: Galacticraft decorative tin (`basicBlock` metadata 4)
- Mechanical base: vanilla iron block
- Bird base: Galacticraft Moon rock
- GalaxySpace dungeons: each world's `*bricks.png`
- Mehen dark matter asteroid: Avaritia Stellar Fuel
- Deep Dark dungeon: vanilla mossy stone bricks
- Anubis robot village: Galacticraft full solar module
- Horus obsidian pyramid: Amun Ra obsidian bricks
- Ross 128b ruin: vanilla red brick block
- Nether molybdenum vein: GT molybdenum dust (`SHINY` material icon)
- Ross 128b arsenopyrite vein: GT indium dust (`METALLIC` material icon)
- End Naquadah asteroid: GT Naquadah dust with a white highlight border
- End Scheelite asteroid: GT Scheelite dust
- End Platinum asteroid: BartWorks Platinum Metallic Powder (`METALLIC`)

The implementation was verified against Galacticraft `3.4.31-GTNH`,
GalaxySpace `1.1.139-GTNH`, the GTNH Amun Ra sources, and Extra Utilities
`1.2.12`.
