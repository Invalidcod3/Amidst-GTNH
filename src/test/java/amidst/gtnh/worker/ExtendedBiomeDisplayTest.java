package amidst.gtnh.worker;

import static org.junit.Assert.*;

import amidst.fragment.Fragment;
import amidst.fragment.colorprovider.BiomeColorProvider;
import amidst.gui.main.viewer.BiomeSelection;
import amidst.mojangapi.world.*;
import amidst.mojangapi.world.coordinates.CoordinatesInWorld;
import amidst.mojangapi.world.oracle.BiomeDataOracle;
import amidst.settings.biomeprofile.BiomeProfile;
import amidst.settings.biomeprofile.BiomeProfileSelection;

import com.google.gson.Gson;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class ExtendedBiomeDisplayTest {
    @Test(timeout = 10000)
    public void extendedIdsSurviveHandshakeSamplingFragmentAndRendering() throws Exception {
        int[] ids = {255, 256, 300, 329, 3000, 32768, 65534, 65536, 65636, 131372, 721196, 1048905};
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(3000);
            Future<?> serving =
                    executor.submit(
                            () -> {
                                Gson gson = new Gson();
                                for (String command :
                                        List.of("hello", "prospecting_catalog", "biomes")) {
                                    try (Socket socket = server.accept()) {
                                        socket.setSoTimeout(3000);
                                        var reader =
                                                new BufferedReader(
                                                        new InputStreamReader(
                                                                socket.getInputStream(),
                                                                StandardCharsets.UTF_8));
                                        var request =
                                                JsonParser.parseString(reader.readLine())
                                                        .getAsJsonObject();
                                        assertEquals(command, request.get("command").getAsString());
                                        Map<String, Object> response = new HashMap<>();
                                        response.put("ok", true);
                                        response.put("protocol", GtnhBiomeSource.PROTOCOL_VERSION);
                                        if (command.equals("hello")) {
                                            response.put("worldType", "RWG2_CONTINENTS");
                                            response.put("providerClass", "test.Provider");
                                            response.put("chunkManagerClass", "test.Manager");
                                            response.put(
                                                    "biomes",
                                                    Arrays.stream(ids)
                                                            .mapToObj(
                                                                    id ->
                                                                            Map.of(
                                                                                    "id",
                                                                                    id,
                                                                                    "name",
                                                                                    "Extended "
                                                                                            + id,
                                                                                    "mapColor",
                                                                                    id))
                                                            .toList());
                                        } else if (command.equals("biomes")) {
                                            assertEquals(
                                                    "RWG2_CONTINENTS",
                                                    request.get("generator").getAsString());
                                            response.put("ids", ids);
                                        }
                                        socket.getOutputStream()
                                                .write(
                                                        (gson.toJson(response) + "\n")
                                                                .getBytes(StandardCharsets.UTF_8));
                                    } catch (IOException failure) {
                                        throw new UncheckedIOException(failure);
                                    }
                                }
                            });
            var backend =
                    new GtnhMinecraftInterface(
                            new GtnhBiomeWorkerClient(
                                    "127.0.0.1", server.getLocalPort(), "", 2000));
            var accessor =
                    backend.createWorldAccessor(
                            new WorldOptions(WorldSeed.fromUserInput("47"), WorldType.RWG2_SMOOTH));
            var oracle =
                    new BiomeDataOracle(
                            accessor,
                            Dimension.OVERWORLD,
                            backend.getBiomeList(),
                            new BiomeDataOracle.Config());
            Fragment fragment = new Fragment(1);
            fragment.setCorner(CoordinatesInWorld.origin());
            fragment.initBiomeData(6, 2);
            fragment.populateBiomeData(oracle);
            var profile = new BiomeProfileSelection(BiomeProfile.getDefaultProfile());
            profile.setRuntimeBiomeColors(backend.getRuntimeBiomeColors());
            var colors = new BiomeColorProvider(new BiomeSelection(), profile);
            for (int i = 0; i < ids.length; i++) {
                int id = fragment.getBiomeDataAt(i % 6, i / 6);
                assertEquals(ids[i], id);
                assertEquals("Extended " + ids[i], backend.getBiomeList().getById(id).getName());
                assertEquals(
                        backend.getRuntimeBiomeColors().get(ids[i]).getRGB(),
                        colors.getColorAt(Dimension.OVERWORLD, fragment, 0, 0, i % 6, i / 6));
            }
            serving.get(2, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }
}
