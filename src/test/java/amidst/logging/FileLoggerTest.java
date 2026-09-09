package amidst.logging;

import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FileLoggerTest {
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void closeFlushesPendingUnicodeMessagesAndKeepsEarlierRuns() throws Exception {
		Path file = temporaryFolder.getRoot().toPath().resolve("viewer.log");
		try (FileLogger logger = new FileLogger(file)) {
			logger.log("info", "等待 Worker 启动");
		}
		try (FileLogger logger = new FileLogger(file)) {
			logger.log("error", "Final message immediately before exit");
		}
		String contents = Files.readString(file);
		assertTrue(contents.contains("[info] 等待 Worker 启动"));
		assertTrue(contents.contains("[error] Final message immediately before exit"));
	}
}
