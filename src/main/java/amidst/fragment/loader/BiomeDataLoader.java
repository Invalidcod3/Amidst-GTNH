package amidst.fragment.loader;

import java.util.Map;
import java.util.Optional;

import amidst.documentation.AmidstThread;
import amidst.documentation.CalledOnlyBy;
import amidst.documentation.NotThreadSafe;
import amidst.fragment.Fragment;
import amidst.fragment.layer.LayerDeclaration;
import amidst.mojangapi.world.Dimension;
import amidst.mojangapi.world.oracle.BiomeDataOracle;

@NotThreadSafe
public class BiomeDataLoader extends FragmentLoader {
	private final BiomeDataOracle biomeDataOracle;
	private final Optional<BiomeDataOracle> netherBiomeDataOracle;
	private final Optional<BiomeDataOracle> endBiomeDataOracle;
	private final Optional<BiomeDataOracle> moonBiomeDataOracle;
	private final Optional<BiomeDataOracle> twilightForestBiomeDataOracle;
	private final Map<Dimension, Optional<BiomeDataOracle>> spaceBiomeDataOracles;

	public BiomeDataLoader(
			LayerDeclaration declaration,
			BiomeDataOracle biomeDataOracle,
			Optional<BiomeDataOracle> netherBiomeDataOracle,
			Optional<BiomeDataOracle> endBiomeDataOracle,
			Optional<BiomeDataOracle> moonBiomeDataOracle,
			Optional<BiomeDataOracle> twilightForestBiomeDataOracle,
			Map<Dimension, Optional<BiomeDataOracle>> spaceBiomeDataOracles) {
		super(declaration);
		this.biomeDataOracle = biomeDataOracle;
		this.netherBiomeDataOracle = netherBiomeDataOracle;
		this.endBiomeDataOracle = endBiomeDataOracle;
		this.moonBiomeDataOracle = moonBiomeDataOracle;
		this.twilightForestBiomeDataOracle = twilightForestBiomeDataOracle;
		this.spaceBiomeDataOracles = spaceBiomeDataOracles;
	}

	@CalledOnlyBy(AmidstThread.FRAGMENT_LOADER)
	@Override
	public void load(Dimension dimension, Fragment fragment) {
		doLoad(dimension, fragment);
	}

	@CalledOnlyBy(AmidstThread.FRAGMENT_LOADER)
	@Override
	public void reload(Dimension dimension, Fragment fragment) {
		doLoad(dimension, fragment);
	}

	@CalledOnlyBy(AmidstThread.FRAGMENT_LOADER)
	private void doLoad(Dimension dimension, Fragment fragment) {
		if (dimension == Dimension.OVERWORLD) {
			fragment.populateBiomeData(biomeDataOracle);
		} else if (dimension == Dimension.NETHER && netherBiomeDataOracle.isPresent()) {
			fragment.populateBiomeData(netherBiomeDataOracle.get());
		} else if (dimension == Dimension.END && endBiomeDataOracle.isPresent()) {
			fragment.populateBiomeData(endBiomeDataOracle.get());
		} else if (dimension == Dimension.MOON && moonBiomeDataOracle.isPresent()) {
			fragment.populateBiomeData(moonBiomeDataOracle.get());
		} else if (dimension == Dimension.TWILIGHT_FOREST
				&& twilightForestBiomeDataOracle.isPresent()) {
			fragment.populateBiomeData(twilightForestBiomeDataOracle.get());
		} else {
			Optional<BiomeDataOracle> spaceOracle =
					spaceBiomeDataOracles.getOrDefault(dimension, Optional.empty());
			if (spaceOracle.isPresent()) {
				fragment.populateBiomeData(spaceOracle.get());
			}
		}
	}
}
