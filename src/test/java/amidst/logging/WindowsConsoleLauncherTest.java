package amidst.logging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class WindowsConsoleLauncherTest {
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void consoleExitsWithViewerWithoutWaitingForInput() throws Exception {
		assumeTrue(System.getProperty("os.name").startsWith("Windows"));
		for (int exitCode : new int[] { 0, 7 }) {
			String script = WindowsConsoleLauncher.consoleScript(
					Path.of(System.getProperty("java.home"), "bin", "java.exe"),
					List.of("-cp", Path.of(ExitProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
							ExitProbe.class.getName(), Integer.toString(exitCode)), temporaryFolder.getRoot().toPath());
			Process process = new ProcessBuilder(WindowsConsoleLauncher.powershellExecutable().toString(),
					"-NoProfile", "-NonInteractive", "-EncodedCommand", WindowsConsoleLauncher.encodeScript(script))
					.redirectErrorStream(true).start();
			try {
				assertTrue("Console did not exit with Viewer", process.waitFor(15, TimeUnit.SECONDS));
				assertEquals(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8), exitCode, process.exitValue());
			} finally {
				process.destroyForcibly();
			}
		}
	}

	public static class ExitProbe {
		public static void main(String[] args) { System.exit(Integer.parseInt(args[0])); }
	}

	@Test
	public void nativeJavaReceivesExactArgumentsThroughPowerShell() throws Exception {
		assumeTrue(System.getProperty("os.name").startsWith("Windows"));
		Path directory = temporaryFolder.newFolder("目录 with spaces & ' $").toPath();
		Path result = directory.resolve("arguments.txt");
		List<String> expected = List.of("", "space separated", "quote\"inside", "trailing\\", "two\\\\",
				"slash\\\"quote", "中文路径", "$(throw 'expanded') %PATH% ! & | <> ^", "line\nbreak");
		List<String> args = new ArrayList<>(List.of("-cp",
				Path.of(ArgumentEcho.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
				ArgumentEcho.class.getName(), result.toString()));
		args.addAll(expected);
		String script = "$ErrorActionPreference = 'Stop'\n"
				+ WindowsConsoleLauncher.processStartInfoScript(Path.of(System.getProperty("java.home"), "bin", "java.exe"), args, directory)
				+ "$info.CreateNoWindow = $true\n"
				+ "$process = [System.Diagnostics.Process]::Start($info)\n"
				+ "$process.WaitForExit()\nexit $process.ExitCode\n";
		Process process = new ProcessBuilder(WindowsConsoleLauncher.powershellExecutable().toString(),
				"-NoProfile", "-NonInteractive", "-EncodedCommand", WindowsConsoleLauncher.encodeScript(script))
				.redirectErrorStream(true).start();
		try {
			assertTrue("PowerShell argument round trip timed out", process.waitFor(15, TimeUnit.SECONDS));
			assertEquals(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8), 0, process.exitValue());
			List<String> actual = Files.readAllLines(result).stream()
					.map(line -> new String(Base64.getDecoder().decode(line), StandardCharsets.UTF_8)).toList();
			assertEquals(expected, actual);
		} finally {
			process.destroyForcibly();
		}
	}

	public static class ArgumentEcho {
		public static void main(String[] args) throws Exception {
			Files.write(Path.of(args[0]), Arrays.stream(args).skip(1)
					.map(value -> Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))).toList());
		}
	}
}
