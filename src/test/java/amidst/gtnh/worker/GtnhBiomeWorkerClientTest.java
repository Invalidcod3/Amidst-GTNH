package amidst.gtnh.worker;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CancellationException;

import org.junit.Test;

import amidst.mojangapi.minecraftinterface.MinecraftInterfaceException;
import amidst.logging.AmidstLogger;
import amidst.threading.TaskCancellation;

public class GtnhBiomeWorkerClientTest {
    @Test(timeout = 5000)
    public void structureGroupsAreSentWithoutChangingLegacyRequests() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(2000);
            GtnhBiomeWorkerClient client = new GtnhBiomeWorkerClient(
                    "127.0.0.1", server.getLocalPort(), "", 2000, 20, () -> {});
            for (String group : new String[] {"standard", "thaumcraft", null}) {
                Future<?> query = executor.submit(() -> client.sampleStructureGroup(42, 0, null, -512, 0, 512, 512, group));
                try (Socket socket = server.accept()) {
                    String line = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
                    com.google.gson.JsonObject request = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
                    assertEquals("structures", request.get("command").getAsString());
                    if (group == null) assertTrue(!request.has("structureGroup") || request.get("structureGroup").isJsonNull());
                    else assertEquals(group, request.get("structureGroup").getAsString());
                    socket.getOutputStream().write("{\"ok\":true,\"protocol\":21,\"structures\":[]}\n".getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                }
                assertEquals(java.util.List.of(), query.get(2, TimeUnit.SECONDS));
            }
        } finally { executor.shutdownNow(); }
    }

	@Test(timeout = 5000)
	public void resultAlreadyInFlightSurvivesPanButNextQueryIsCancelled() throws Exception {
		AtomicBoolean cancelled = new AtomicBoolean();
		AtomicReference<int[]> saved = new AtomicReference<>();
		AtomicInteger offlineNotifications = new AtomicInteger();
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try (ServerSocket server = new ServerSocket(0)) {
			server.setSoTimeout(2000);
			GtnhBiomeWorkerClient client = new GtnhBiomeWorkerClient(
					"127.0.0.1", server.getLocalPort(), "", 2000, 20, offlineNotifications::incrementAndGet);
			Future<?> query = executor.submit(() -> TaskCancellation.run(cancelled::get, () -> {
				try {
					saved.set(client.sampleBiomes(1, 0, 0, 0, 1, 1, 4));
					client.sampleBiomes(1, 0, 512, 0, 1, 1, 4);
				} catch (MinecraftInterfaceException e) { throw new AssertionError(e); }
			}));
			try (Socket socket = server.accept()) {
				BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
				assertTrue(reader.readLine().contains("\"command\":\"biomes\""));
				cancelled.set(true); // Pan after the game has already started this query.
				socket.getOutputStream().write("{\"ok\":true,\"protocol\":21,\"ids\":[40]}\n".getBytes(StandardCharsets.UTF_8));
				socket.getOutputStream().flush();
			}
			ExecutionException failure = assertThrows(ExecutionException.class, () -> query.get(2, TimeUnit.SECONDS));
			assertTrue(failure.getCause() instanceof CancellationException);
			assertArrayEquals(new int[] {40}, saved.get());
			assertEquals(0, offlineNotifications.get());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test(timeout = 5000)
	public void cancellingOfflineViewportTaskReleasesRecoveryForTheNextTask() throws Exception {
		int port;
		try (ServerSocket reservation = new ServerSocket(0)) {
			port = reservation.getLocalPort();
		}
		CountDownLatch waiting = new CountDownLatch(1);
		AtomicBoolean cancelled = new AtomicBoolean();
		GtnhBiomeWorkerClient client = new GtnhBiomeWorkerClient(
				"127.0.0.1", port, "", 200, 2000, waiting::countDown);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<?> oldView = executor.submit(() -> TaskCancellation.run(cancelled::get, () -> {
				try { client.getWorkerInfo(); }
				catch (MinecraftInterfaceException e) { throw new AssertionError(e); }
			}));
			assertTrue(waiting.await(2, TimeUnit.SECONDS));
			cancelled.set(true);
			ExecutionException failure = assertThrows(ExecutionException.class, () -> oldView.get(1, TimeUnit.SECONDS));
			assertTrue(failure.getCause() instanceof CancellationException);
			try (ServerSocket server = new ServerSocket(port)) {
				server.setSoTimeout(2000);
				// Same executor thread: cancellation scope must also have been removed.
				Future<GtnhWorkerInfo> currentView = executor.submit(client::getWorkerInfo);
				respondToHello(server, HELLO_RESPONSE);
				assertEquals("RWG", currentView.get(2, TimeUnit.SECONDS).worldType());
			}
		} finally {
			cancelled.set(true);
			executor.shutdownNow();
		}
	}

	@Test(timeout = 5000)
	public void cancelledTaskCannotStartAnotherWorkerQuery() {
		AtomicInteger offlineNotifications = new AtomicInteger();
		GtnhBiomeWorkerClient client = new GtnhBiomeWorkerClient(
				"127.0.0.1", 1, "", 200, 20, offlineNotifications::incrementAndGet);
		AtomicBoolean cancelled = new AtomicBoolean();
		assertThrows(CancellationException.class, () -> TaskCancellation.run(cancelled::get, () -> {
			cancelled.set(true);
			try { client.sampleBiomes(1, 0, 0, 0, 1, 1, 4); }
			catch (MinecraftInterfaceException e) { throw new AssertionError(e); }
		}));
		assertEquals(0, offlineNotifications.get());
		TaskCancellation.check();
	}

	@Test
	public void optionalProfilingRequestsAndLogsQueueAndComputeTimes() throws Exception {
		String previous = System.getProperty("amidst.gtnh.profileQueries");
		CopyOnWriteArrayList<String> messages = new CopyOnWriteArrayList<>();
		AmidstLogger.addListener("test-query-profile", (tag, message) -> messages.add(message));
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try (ServerSocket server = new ServerSocket(0)) {
			System.setProperty("amidst.gtnh.profileQueries", "true");
			Future<?> serving = executor.submit(() -> {
				try (Socket socket = server.accept();
						BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
						BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
					assertTrue(reader.readLine().contains("\"profile\":true"));
					writer.write("{\"ok\":true,\"protocol\":21,\"worldRevision\":1,\"queueMillis\":12.5,\"computeMillis\":3.25}\n");
					writer.flush();
				} catch (Exception e) { throw new RuntimeException(e); }
			});
			GtnhBiomeWorkerClient client = new GtnhBiomeWorkerClient("127.0.0.1", server.getLocalPort(), "", 1000);
			assertEquals(1L, client.getWorldState(0).revision());
			serving.get(2, TimeUnit.SECONDS);
			assertTrue(messages.stream().anyMatch(message -> message.contains("GTNH performance: world_state")
					&& message.contains("queue=12.500 ms compute=3.250 ms")));
		} finally {
			executor.shutdownNow();
			AmidstLogger.removeListener("test-query-profile");
			if (previous == null) System.clearProperty("amidst.gtnh.profileQueries");
			else System.setProperty("amidst.gtnh.profileQueries", previous);
		}
	}

	private static final String HELLO_RESPONSE = "{\"ok\":true,\"protocol\":"
			+ GtnhBiomeSource.PROTOCOL_VERSION
			+ ",\"worldType\":\"RWG\",\"providerClass\":\"test.Provider\","
			+ "\"chunkManagerClass\":\"test.ChunkManager\",\"biomes\":[{\"id\":40,\"name\":\"Test biome\"}]}";

	@Test
	public void startupWaitsForLateWorkerAndUnfinishedGameInitialization() throws Exception {
		int port;
		try (ServerSocket reservation = new ServerSocket(0)) {
			port = reservation.getLocalPort();
		}
		CountDownLatch waiting = new CountDownLatch(1);
		AtomicInteger notifications = new AtomicInteger();
		GtnhBiomeWorkerClient client = new GtnhBiomeWorkerClient("127.0.0.1", port, "", 250, 20, () -> {
			notifications.incrementAndGet();
			waiting.countDown();
		});
		ExecutorService executor = Executors.newCachedThreadPool();
		try {
			// This is the same backend construction used before displaying the main window.
			Future<GtnhMinecraftInterface> startup = executor.submit(() -> new GtnhMinecraftInterface(client));
			assertTrue(waiting.await(2, TimeUnit.SECONDS));
			assertTrue(!startup.isDone());
			try (ServerSocket server = new ServerSocket(port)) {
				server.setSoTimeout(3000);
				Future<?> serving = executor.submit(() -> {
					try (Socket stalled = server.accept()) {
						// Keep the first handshake open. Accepting the next one proves
						// a read timeout did not terminate startup recovery.
						respondToHello(server, "{\"ok\":false,\"protocol\":" + GtnhBiomeSource.PROTOCOL_VERSION
								+ ",\"error\":\"server thread did not process the query in time\"}");
						respondToHello(server, null); // Worker closes an unfinished handshake.
						respondToHello(server, HELLO_RESPONSE); // Recovery probe.
						respondToHello(server, HELLO_RESPONSE); // Original startup request.
                        try (Socket catalog = server.accept()) {
                            BufferedReader reader = new BufferedReader(new InputStreamReader(catalog.getInputStream(), StandardCharsets.UTF_8));
                            assertTrue(reader.readLine().contains("prospecting_catalog"));
                            catalog.getOutputStream().write(("{\"ok\":true,\"protocol\":" + GtnhBiomeSource.PROTOCOL_VERSION
                                    + ",\"prospectingDimensions\":[]}\n").getBytes(StandardCharsets.UTF_8));
                        }
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				});
				assertEquals("RWG", startup.get(5, TimeUnit.SECONDS).getWorkerInfo().worldType());
				serving.get(5, TimeUnit.SECONDS);
				assertEquals(1, notifications.get());
			}
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	public void startupWaitsWhenWorkerAlreadyListensButHasNotAnsweredHello() throws Exception {
		try (ServerSocket server = new ServerSocket(0)) {
			server.setSoTimeout(3000);
			CountDownLatch waiting = new CountDownLatch(1);
			ExecutorService executor = Executors.newCachedThreadPool();
			try {
				GtnhBiomeWorkerClient client = new GtnhBiomeWorkerClient(
						"127.0.0.1", server.getLocalPort(), "", 250, 20, waiting::countDown);
				Future<GtnhWorkerInfo> startup = executor.submit(client::getWorkerInfo);
				try (Socket stalled = server.accept()) {
					assertTrue(waiting.await(2, TimeUnit.SECONDS));
					respondToHello(server, HELLO_RESPONSE);
					respondToHello(server, HELLO_RESPONSE);
					assertEquals("RWG", startup.get(3, TimeUnit.SECONDS).worldType());
				}
			} finally {
				executor.shutdownNow();
			}
		}
	}

	@Test
	public void recoveryReportsAuthenticationAndProtocolErrorsInsteadOfWaitingForever() throws Exception {
		String[] responses = {
				"{\"ok\":false,\"protocol\":" + GtnhBiomeSource.PROTOCOL_VERSION + ",\"error\":\"authentication failed\"}",
				"{\"ok\":true,\"protocol\":-1}"
		};
		String[] errors = { "authentication failed", "protocol is -1" };
		for (int i = 0; i < responses.length; i++) {
			int port;
			try (ServerSocket reservation = new ServerSocket(0)) {
				port = reservation.getLocalPort();
			}
			CountDownLatch waiting = new CountDownLatch(1);
			GtnhBiomeWorkerClient client = new GtnhBiomeWorkerClient("127.0.0.1", port, "", 1000, 20, waiting::countDown);
			ExecutorService executor = Executors.newSingleThreadExecutor();
			try {
				Future<GtnhWorkerInfo> startup = executor.submit(client::getWorkerInfo);
				assertTrue(waiting.await(2, TimeUnit.SECONDS));
				try (ServerSocket server = new ServerSocket(port)) {
					server.setSoTimeout(3000);
					respondToHello(server, responses[i]);
				}
				ExecutionException failure = assertThrows(ExecutionException.class, () -> startup.get(3, TimeUnit.SECONDS));
				assertTrue(failure.getCause() instanceof MinecraftInterfaceException);
				assertTrue(failure.getCause().getMessage().contains(errors[i]));
			} finally {
				executor.shutdownNow();
			}
		}
	}

	private static void respondToHello(ServerSocket server, String response) throws Exception {
		try (Socket socket = server.accept()) {
			socket.setSoTimeout(3000);
			BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
			assertTrue(reader.readLine().contains("\"command\":\"hello\""));
			if (response != null) {
				BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
				writer.write(response);
				writer.newLine();
				writer.flush();
			}
		}
	}

	@Test
	public void reachableButStalledWorkerIsNotReportedAsOffline() throws Exception {
		try (ServerSocket server = new ServerSocket(0)) {
			ExecutorService executor = Executors.newSingleThreadExecutor();
			CountDownLatch releaseServer = new CountDownLatch(1);
			AtomicInteger offlineNotifications = new AtomicInteger();
			try {
				Future<?> serving = executor.submit(() -> {
					try (Socket socket = server.accept()) {
						releaseServer.await(3, TimeUnit.SECONDS);
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				});
				GtnhBiomeWorkerClient client = new GtnhBiomeWorkerClient(
						"127.0.0.1", server.getLocalPort(), "", 100, 20,
						offlineNotifications::incrementAndGet);
				MinecraftInterfaceException failure = assertThrows(
						MinecraftInterfaceException.class, () -> client.getWorldState(0));
				assertTrue(failure.getMessage().contains("Connected to GTNH worker"));
				assertTrue(failure.getMessage().contains("world_state"));
				assertTrue(failure.getMessage().contains("timed out"));
				assertEquals(0, offlineNotifications.get());
				releaseServer.countDown();
				serving.get(2, TimeUnit.SECONDS);
			} finally {
				releaseServer.countDown();
				executor.shutdownNow();
			}
		}
	}

	@Test
	public void workerLinkageErrorIsPreservedInsteadOfStartingOfflineRecovery() throws Exception {
		try (ServerSocket server = new ServerSocket(0)) {
			ExecutorService executor = Executors.newSingleThreadExecutor();
			AtomicInteger offlineNotifications = new AtomicInteger();
			try {
				Future<?> serving = executor.submit(() -> {
					try (Socket socket = server.accept();
							BufferedReader reader = new BufferedReader(new InputStreamReader(
									socket.getInputStream(), StandardCharsets.UTF_8));
							BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
									socket.getOutputStream(), StandardCharsets.UTF_8))) {
						reader.readLine();
						writer.write("{\"ok\":false,\"protocol\":" + GtnhBiomeSource.PROTOCOL_VERSION
								+ ",\"error\":\"NoSuchFieldError: isRemote\"}\n");
						writer.flush();
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				});
				GtnhBiomeWorkerClient client = new GtnhBiomeWorkerClient(
						"127.0.0.1", server.getLocalPort(), "", 1000, 20,
						offlineNotifications::incrementAndGet);
				MinecraftInterfaceException failure = assertThrows(
						MinecraftInterfaceException.class, () -> client.getWorldState(0));
				assertTrue(failure.getMessage().contains("NoSuchFieldError: isRemote"));
				assertEquals(0, offlineNotifications.get());
				serving.get(2, TimeUnit.SECONDS);
			} finally {
				executor.shutdownNow();
			}
		}
	}

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
