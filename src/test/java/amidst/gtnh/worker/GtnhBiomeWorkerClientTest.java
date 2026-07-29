package amidst.gtnh.worker;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public class GtnhBiomeWorkerClientTest {
	@Test
	public void waitsForWorkerAndRetriesTheInterruptedRequest() throws Exception {
		int port;
		try (ServerSocket reservation = new ServerSocket(0)) {
			port = reservation.getLocalPort();
		}

		CountDownLatch offlineNotification = new CountDownLatch(1);
		AtomicInteger notificationCount = new AtomicInteger();
		GtnhBiomeWorkerClient client = new GtnhBiomeWorkerClient(
				"127.0.0.1",
				port,
				"",
				50,
				20,
				() -> {
					notificationCount.incrementAndGet();
					offlineNotification.countDown();
				});
		ExecutorService executor = Executors.newCachedThreadPool();
		try {
			Future<int[]> request = executor.submit(
					() -> client.sampleBiomes(123L, 0, 0, 0, 1, 1, 1));

			assertTrue(offlineNotification.await(2, TimeUnit.SECONDS));
			Future<?> server = executor.submit(() -> serveRecoveryAndBiomeRequest(port));

			assertArrayEquals(new int[] { 40 }, request.get(5, TimeUnit.SECONDS));
			server.get(5, TimeUnit.SECONDS);
			assertEquals(1, notificationCount.get());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	public void readsIncrementalOverworldChunkState() throws Exception {
		try (ServerSocket server = new ServerSocket(0)) {
			ExecutorService executor = Executors.newSingleThreadExecutor();
			try {
				Future<?> response = executor.submit(() -> {
					try (Socket socket = server.accept();
							BufferedReader reader = new BufferedReader(
									new InputStreamReader(
											socket.getInputStream(),
											StandardCharsets.UTF_8));
							BufferedWriter writer = new BufferedWriter(
									new OutputStreamWriter(
											socket.getOutputStream(),
											StandardCharsets.UTF_8))) {
						String request = reader.readLine();
						assertTrue(request.contains("\"command\":\"world_state\""));
						assertTrue(request.contains("\"sinceRevision\":7"));
						writer.write("{\"ok\":true,\"protocol\":"
								+ GtnhBiomeSource.PROTOCOL_VERSION
								+ ",\"worldRevision\":10,\"fullRefresh\":false,"
								+ "\"chunkXs\":[2,-3],\"chunkZs\":[4,5]}");
						writer.newLine();
						writer.flush();
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				});

				GtnhBiomeWorkerClient client = new GtnhBiomeWorkerClient(
						"127.0.0.1",
						server.getLocalPort(),
						"",
						1_000);
				GtnhWorldState state = client.getWorldState(7L);

				assertEquals(10L, state.revision());
				assertTrue(!state.fullRefresh());
				assertArrayEquals(new int[] { 2, -3 }, state.chunkXs());
				assertArrayEquals(new int[] { 4, 5 }, state.chunkZs());
				response.get(5, TimeUnit.SECONDS);
			} finally {
				executor.shutdownNow();
			}
		}
	}

	private static void serveRecoveryAndBiomeRequest(int port) {
		try (ServerSocket server = new ServerSocket(port)) {
			serve(server, "\"command\":\"hello\"");
			serve(server, "\"command\":\"biomes\"");
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static void serve(ServerSocket server, String expectedCommand) throws Exception {
		try (Socket socket = server.accept();
				BufferedReader reader = new BufferedReader(
						new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
				BufferedWriter writer = new BufferedWriter(
						new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
			String request = reader.readLine();
			assertTrue(request.contains(expectedCommand));
			writer.write("{\"ok\":true,\"protocol\":"
					+ GtnhBiomeSource.PROTOCOL_VERSION
					+ ",\"ids\":[40]}");
			writer.newLine();
			writer.flush();
		}
	}
}
