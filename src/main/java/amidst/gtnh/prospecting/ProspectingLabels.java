package amidst.gtnh.prospecting;

import amidst.i18n.I18n;

/** User-facing labels for wire resource kinds. */
final class ProspectingLabels {
    private ProspectingLabels() {}

    static String kind(String kind) {
        return I18n.text("ASTEROID_SMALL".equals(kind) ? "Small-ore asteroid" : "Ore-mix asteroid");
    }
}
