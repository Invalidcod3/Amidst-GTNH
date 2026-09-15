# GTNH Galacticraft Moon map

> 历史记录：安装要求、协议号及验证结论属于当时版本。当前状态见[发布说明](../release-v0.3.1.md)。

The GTNH worker exposes Galacticraft's Moon as a dedicated AMIDST dimension.
The protocol handshake carries the runtime value of
`ConfigManagerCore.idDimensionMoon` (default `-28`), so a pack or server that
changes the dimension ID still queries the correct provider.

## Single Moon biome

Galacticraft's `WorldChunkManagerMoon` always returns
`BiomeGenBaseMoon.moonFlat`. The worker therefore samples the registered
runtime biome directly and fills every requested map cell with that ID,
without loading or generating chunks. Its descriptor is normalized to the
display name `Moon`, tagged `MOON`, and rendered with the stable color
`#8C8C84`.

The GUI opens this view through `Layers -> Dimension -> Moon`; the shortcut is
`Ctrl/Cmd+Shift+5`. The headless preview accepts `--dimension moon`.

## Moon dungeons

`MapGenDungeon` divides the Moon into 44-by-44-chunk regions. For each region,
the predictor uses Java's `Random` with Galacticraft's exact seed:

```text
regionX * 341873128712
+ regionZ * 132897987541
+ worldSeed
+ 4291754
+ moonDimensionId
```

Both chunk offsets are selected with `nextInt(44)`. AMIDST marks the selected
chunk center (`chunk * 16 + 8`), which is the dungeon entrance coordinate
returned by Galacticraft's own `getDungeonNear` method.

## Moon villages

`MapGenVillageMoon` uses 32-chunk regions, separation 8, and salt `10387312`.
The predictor preserves the original 1.7.10 negative-coordinate adjustment
and the exact `World.setRandomSeed` formula. `StructureVillageStartMoon`
always reports itself as sizeable, and the Moon has no biome rejection step,
so selected starts are expected villages rather than terrain-dependent
candidates.

The worker reads `ConfigManagerCore.disableMoonVillageGen` for every structure
query. When the running pack disables Moon villages, the village layer
returns no markers while dungeon prediction remains active.

The implementation follows the GTNH-maintained Galacticraft sources:

- `core/world/gen/MapGenVillageMoon.java`
- `core/world/gen/dungeon/MapGenDungeon.java`
- `core/world/gen/ChunkProviderMoon.java`
- `core/world/gen/WorldChunkManagerMoon.java`
