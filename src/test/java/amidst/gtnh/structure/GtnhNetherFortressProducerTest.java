package amidst.gtnh.structure;

import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import amidst.mojangapi.world.coordinates.CoordinatesInWorld;
import amidst.mojangapi.world.coordinates.Resolution;
import amidst.mojangapi.world.icon.WorldIcon;
import amidst.mojangapi.world.icon.producer.NetherFortressProducer_Original;

public class GtnhNetherFortressProducerTest {

	@Test
	public void matchesVanillaAlgorithmInNativeNetherCoordinates() {
		long seed = -734892347598234L;
		GtnhNetherFortressProducer nativeProducer =
				new GtnhNetherFortressProducer(seed);
		NetherFortressProducer_Original overlayProducer =
				new NetherFortressProducer_Original(seed);

		int checkedIcons = 0;
		for (int z = -1024; z <= 1024; z += 512) {
			for (int x = -1024; x <= 1024; x += 512) {
				CoordinatesInWorld nativeCorner = CoordinatesInWorld.from(x, z);
				List<WorldIcon> nativeIcons = nativeProducer.getAt(nativeCorner, null);

				for (WorldIcon nativeIcon : nativeIcons) {
					checkedIcons++;
					long scaledX = Resolution.NETHER.convertFromThisToWorld(
							nativeIcon.getCoordinates().getX());
					long scaledZ = Resolution.NETHER.convertFromThisToWorld(
							nativeIcon.getCoordinates().getY());
					CoordinatesInWorld overlayCorner = CoordinatesInWorld.from(
							Math.floorDiv(scaledX, 512L) * 512L,
							Math.floorDiv(scaledZ, 512L) * 512L);
					List<WorldIcon> overlayIcons =
							overlayProducer.getAt(overlayCorner, null);
					assertTrue(overlayIcons.stream().anyMatch(icon ->
							icon.getCoordinates().getX() == scaledX
									&& icon.getCoordinates().getY() == scaledZ));
				}
			}
		}
		assertTrue(checkedIcons > 0);
	}
}
