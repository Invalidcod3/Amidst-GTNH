package amidst.settings.biomeprofile;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import amidst.documentation.ThreadSafe;
import amidst.logging.AmidstLogger;
import amidst.logging.AmidstMessageBox;
import amidst.mojangapi.world.biome.BiomeColor;
import amidst.mojangapi.world.biome.UnknownBiomeIdException;

@ThreadSafe
public class BiomeProfileSelection {
	private ConcurrentHashMap<Integer, BiomeColor> biomeColors;
	private volatile ConcurrentHashMap<Integer, BiomeColor> runtimeBiomeColors = new ConcurrentHashMap<>();
	private volatile boolean profileOverridesRuntime;
	private Set<Integer> unknownBiomes;

	public BiomeProfileSelection(BiomeProfile biomeProfile) {
		set(biomeProfile);
	}

	public BiomeColor getBiomeColorOrUnknown(int index) {
		try {
			return getBiomeColor(index);
		} catch (UnknownBiomeIdException e) {
			// Only show an error if this is the first time we encounter this biome
			if (unknownBiomes.add(index)) {
				AmidstLogger.error(e);
				AmidstMessageBox.displayError("Error", e);
			}
			return BiomeColor.unknown();
		}
	}

	public BiomeColor getBiomeColor(int index) throws UnknownBiomeIdException {
		BiomeColor color = profileOverridesRuntime
				? biomeColors.get(index)
				: runtimeBiomeColors.get(index);
		if (color == null) {
			color = profileOverridesRuntime
					? runtimeBiomeColors.get(index)
					: biomeColors.get(index);
		}
		if(color != null) {
			return color;
		} else {
			throw new UnknownBiomeIdException("unsupported biome index detected: " + index);
		}
	}

	public void setRuntimeBiomeColors(java.util.Map<Integer, BiomeColor> colors) {
		this.runtimeBiomeColors = new ConcurrentHashMap<>(colors);
		this.profileOverridesRuntime = false;
		this.unknownBiomes = ConcurrentHashMap.newKeySet();
	}

	public void set(BiomeProfile biomeProfile) {
		this.biomeColors = biomeProfile.createBiomeColorMap();
		this.profileOverridesRuntime = true;
		this.unknownBiomes = ConcurrentHashMap.newKeySet();
		AmidstLogger.info("Biome profile activated: " + biomeProfile.getName());
	}
}
