# GTNH Twilight Forest map

The GTNH worker exposes Twilight Forest as a fourth dedicated AMIDST
dimension. The handshake carries the runtime value of
`TwilightForestMod.dimensionID`, so non-default pack configurations still use
the right Forge dimension.

## Biomes

For arbitrary seeds the worker creates a save-free
`TFWorldChunkManager(long, WorldType)` through reflection. Full-resolution
queries sample the zoomed block-biome layer. AMIDST's displayed
quarter-resolution map reads `TFWorldChunkManager#getBiomesForGeneration`
directly, which is Twilight Forest's raw `unzoomedBiomes` layer. This preserves
the correct 1:4 coordinate scale and the same boundary topology used during
world generation. Twilight biome descriptors carry a `TWILIGHT_FOREST` tag,
which makes AMIDST render `BiomeGenBase.color` exactly as
`TFMagicMapRenderer` does.

## Landmarks

The `Magic Map Landmarks` layer ports the feature forecast from
`TFFeature`/`TFWorldChunkManager`:

- one feature chunk is selected per 16×16-chunk region;
- the same region-center bit mixing is used for positive and negative
  coordinates;
- the biome at the rounded region center selects progression structures;
- the remaining hollow hills, mazes, naga courtyards, and lich towers use the
  same world-seed random stream;
- the legacy `OldMapGen` branch remains supported.

Coordinates identify exact feature-chunk centers. The final generated
structure footprint can extend beyond the marker.

Every landmark type has its own persistent layer toggle under
`Layers > Magic Map Landmarks`; disabling one type does not affect any of the
other feature markers.

Marker PNGs 1 through 19 in
`amidst/gui/main/icon/twilight_feature_*.png` are nearest-neighbor extractions
of their matching feature IDs in Twilight Forest's
`assets/twilightforest/textures/gui/mapicons.png`. The upstream feature ID 20
slot (Mushroom Tower) is fully transparent, so that marker uses the mod's own
`mushgloom.png` texture as a visible fallback. The layer-menu icon comes from
the mod's `magicMap.png`.

Reference implementation:
[GTNewHorizons/twilightforest](https://github.com/GTNewHorizons/twilightforest).
The upstream repository states that its code is LGPL-2.1-or-later and that
textures derived from Team Twilight use CC BY-NC-SA 4.0.
