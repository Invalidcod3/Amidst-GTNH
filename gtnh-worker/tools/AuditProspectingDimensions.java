import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import amidst.mojangapi.world.Dimension;

/** Run with the release Viewer on the classpath and the installed-reference report as argument. */
class AuditProspectingDimensions {
    public static void main(String[] args) throws Exception {
        Set<String> installed = new TreeSet<>(Arrays.asList(new Gson().fromJson(Files.readString(Path.of(args[0])), String[].class)));
        if (!installed.remove("EndAsteroids")) throw new AssertionError("GT End alias missing");
        Set<String> viewer = new TreeSet<>();
        for (Dimension dimension : Dimension.values()) {
            if (!viewer.add(dimension.prospectingKey())) throw new AssertionError("Duplicate Viewer dimension");
        }
        if (!installed.equals(viewer)) {
            Set<String> missing = new TreeSet<>(installed); missing.removeAll(viewer);
            Set<String> extra = new TreeSet<>(viewer); extra.removeAll(installed);
            throw new AssertionError("Missing: " + missing + "; extra: " + extra);
        }
        String report = new GsonBuilder().setPrettyPrinting().create().toJson(Map.of(
                "verifiedViewerDimensions", viewer.size(), "sharedEndAlias", "EndAsteroids", "keys", viewer));
        Files.writeString(Path.of(args[1]), report);
        System.out.println("All " + viewer.size() + " installed GT prospecting dimensions are registered in the release Viewer.");
    }
}
