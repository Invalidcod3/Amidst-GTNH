# GTNH Galacticraft and GalaxySpace dimensions

The worker reads every dimension ID from the running mods rather than assuming
the defaults. The defaults in the tested GTNH versions are:

| Map | Runtime configuration field | Default |
| --- | --- | ---: |
| Mars | `ConfigManagerMars.dimensionIDMars` | -29 |
| Asteroids | `ConfigManagerAsteroids.dimensionIDAsteroids` | -30 |
| Ceres | `GSConfigDimensions.dimensionIDCeres` | -1007 |
| Pluto | `GSConfigDimensions.dimensionIDPluto` | -1008 |
| Io | `GSConfigDimensions.dimensionIDIo` | -1013 |
| Enceladus | `GSConfigDimensions.dimensionIDEnceladus` | -1016 |
| Proteus (海卫八) | `GSConfigDimensions.dimensionIDProteus` | -1019 |
| Mehen Belt | `AmunRa.config.dimMehen` | 25 |
| Ross 128b | `Configuration.crossModInteractions.ross128BID` | 64 |

## Biome maps

Mars and Asteroids each expose their single Galacticraft biome. Ceres,
Enceladus, and Proteus use GalaxySpace's shared single `Space` biome. Io uses
`WorldChunkManagerIo` and displays both `Io` and `IoAsh`; Pluto uses
`WorldChunkManagerPluto` and displays its four seeded Pluto biomes. The worker
constructs the Io and Pluto managers with the selected world seed, so biome
borders are not inferred from the currently loaded server world.
Mehen Belt uses Galacticraft's registered asteroid biome. Ross 128b constructs
the same seeded vanilla `WorldChunkManager` selected by its world provider.

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
- Ross 128b ruins replay its `XSTR` chunk-population seed, biome exclusions,
  ruin chance, and coordinate rolls. Lake population, Forge events, and final
  ground checks depend on generated terrain, so the markers are `Possible`.
- Nether molybdenum and Ross 128b arsenopyrite markers replay GT5U's saved
  ore-grid mode, dimension byte, runtime vein chance and attempt count,
  FNV-1a retry seeds, and live weighted ore registry. A matching ore seed
  chunk is `Possible` because GT can reject or reroll it after height and host
  stone checks.

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
- Ross 128b ruin: vanilla red brick block
- Nether molybdenum vein: GT molybdenum dust (`SHINY` material icon)
- Ross 128b arsenopyrite vein: GT indium dust (`METALLIC` material icon)

The implementation was verified against Galacticraft `3.4.31-GTNH` and
GalaxySpace `1.1.139-GTNH`.
