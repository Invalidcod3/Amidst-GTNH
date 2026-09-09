package amidst.gtnh.worker;

import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.junit.Test;
import com.google.gson.*;
import amidst.mojangapi.minecraftinterface.MinecraftInterfaceException;

public class ProspectingProtocolTest {
    @Test public void catalogCarriesLocalizedChoicesAndRejectsMissingOptions() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> responder = executor.submit(() -> {
                    for (int i = 0; i < 2; i++) try (Socket socket = server.accept()) {
                        var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                        assertEquals("prospecting_catalog", JsonParser.parseString(input.readLine()).getAsJsonObject().get("command").getAsString());
                        String options = i == 0 ? ",\"oreOptions\":[{\"id\":\"mix.copper\",\"name\":\"铜矿脉\",\"materials\":\"Copper, Iron\"}],\"fluidOptions\":[{\"id\":\"oil\",\"name\":\"原油\"}]" : "";
                        String response = "{\"ok\":true,\"protocol\":" + GtnhBiomeSource.PROTOCOL_VERSION
                                + ",\"prospectingDimensions\":[{\"key\":\"Overworld\",\"id\":0" + options + "}]}\n";
                        socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                    } catch (IOException e) { throw new UncheckedIOException(e); }
                });
                var client = new GtnhBiomeWorkerClient("127.0.0.1", server.getLocalPort(), "", 3000);
                var info = client.prospectingCatalog().get(0);
                assertEquals("mix.copper", info.oreOptions.get(0).id);
                assertEquals("铜矿脉", info.oreOptions.get(0).name);
                assertEquals("原油", info.fluidOptions.get(0).name);
                assertThrows(MinecraftInterfaceException.class, client::prospectingCatalog);
                responder.get(5, TimeUnit.SECONDS);
            } finally { executor.shutdownNow(); }
        }
    }
    @Test public void carriesNativeDimensionAndRejectsMalformedFluidFields() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> responder = executor.submit(() -> {
                    try (Socket socket = server.accept()) {
                        var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                        var request = JsonParser.parseString(input.readLine()).getAsJsonObject();
                        assertEquals("prospecting", request.get("command").getAsString());
                        assertEquals("FLUID", request.get("markerMode").getAsString());
                        assertEquals(-1081, request.get("dimension").getAsInt());
                        assertEquals(-256, request.get("x").getAsInt());
                        assertEquals("oil",request.getAsJsonObject("prospectingFilter").get("id").getAsString());
                        assertEquals(150,request.getAsJsonObject("prospectingFilter").get("minimumFluid").getAsInt());
                        String response = "{\"ok\":true,\"protocol\":" + GtnhBiomeSource.PROTOCOL_VERSION
                                + ",\"prospecting\":{\"deposits\":[{\"id\":\"oil\",\"name\":\"Oil\",\"source\":\"INITIAL\",\"size\":128,\"amounts\":[1]}]}}\n";
                        socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                    } catch (IOException e) { throw new UncheckedIOException(e); }
                });
                GtnhBiomeWorkerClient client = new GtnhBiomeWorkerClient("127.0.0.1", server.getLocalPort(), "", 3000);
                var filter = new amidst.gtnh.prospecting.ProspectingData.QueryFilter(); filter.id="oil"; filter.minimumFluid=150;
                assertThrows(MinecraftInterfaceException.class, () -> client.prospectFiltered(1234L,-1081,-256,0,256,256,"FLUID",filter));
                responder.get(5,TimeUnit.SECONDS);
            } finally { executor.shutdownNow(); }
        }
    }
}
