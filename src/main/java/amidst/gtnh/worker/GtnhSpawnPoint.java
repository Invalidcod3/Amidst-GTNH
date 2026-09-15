package amidst.gtnh.worker;

import amidst.i18n.I18n;
import amidst.mojangapi.world.coordinates.CoordinatesInWorld;

/** Source accompanies coordinates so a seed estimate can never masquerade as a saved spawn. */
public record GtnhSpawnPoint(int x, Integer y, int z, String source) {
    public CoordinatesInWorld coordinates() {
        return CoordinatesInWorld.from(x, z);
    }

    public String label() {
        return I18n.text("RECORDED".equals(source) ? "Saved world spawn" : "Estimated world spawn");
    }
}
