package amidst.logging;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Windows associates executable JARs with javaw.exe, which has no console.
 * Relaunch only that case, using the same Java installation and JVM/app arguments.
 * Windows PowerShell opens a native console and holds it after Java exits.
 * No file associations, global settings or external libraries are needed.
 */
public final class WindowsConsoleLauncher {
	private static final String RELAUNCHED_PROPERTY = "amidst.nativeConsole";

	private WindowsConsoleLauncher() {
	}

	public static boolean relaunchIfNeeded(boolean useGtnhWorker, String[] args) throws IOException {
		if (!useGtnhWorker || !System.getProperty("os.name", "").startsWith("Windows")
				|| System.console() != null || Boolean.getBoolean(RELAUNCHED_PROPERTY)) {
			return false;
		}
		String executable = ProcessHandle.current().info().command().orElse("");
		if (!executable.toLowerCase(Locale.ROOT).endsWith("\\javaw.exe")
				&& !executable.toLowerCase(Locale.ROOT).endsWith("/javaw.exe")) {
			return false;
		}
		Path jar;
		try {
			jar = Path.of(WindowsConsoleLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		} catch (URISyntaxException e) {
			throw new IOException("Unable to locate the Viewer JAR", e);
		}
		if (!Files.isRegularFile(jar)) {
			return false; // IDE/class-directory launches are managed by their caller.
		}
		Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
		Path powershell = powershellExecutable();
		if (!Files.isRegularFile(java) || !Files.isRegularFile(powershell)) {
			throw new IOException("The Java console executable or Windows PowerShell is unavailable");
		}
		List<String> javaArguments = new ArrayList<>(ManagementFactory.getRuntimeMXBean().getInputArguments());
		javaArguments.add("-D" + RELAUNCHED_PROPERTY + "=true");
		javaArguments.add("-jar");
		javaArguments.add(jar.toString());
		javaArguments.addAll(List.of(args));
		String consoleScript = "$ErrorActionPreference = 'Stop'\n"
				+ "$Host.UI.RawUI.WindowTitle = 'GTNH SeedViewer'\n"
				+ "[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)\n"
				+ "try {\n"
				+ processStartInfoScript(java, javaArguments, Path.of(System.getProperty("user.dir")))
				+ "$process = [System.Diagnostics.Process]::Start($info)\n"
				+ "$process.WaitForExit()\n"
				+ "Write-Host ('Viewer exited with code ' + $process.ExitCode)\n"
				+ "} catch { Write-Host $_ -ForegroundColor Red }\n"
				+ "Write-Host 'You can close this console window when done.'\n";
		// Only an encoded script crosses the bootstrap command line. User paths,
		// JVM options and tokens are never interpolated into cmd.exe commands.
		String bootstrap = "$ErrorActionPreference = 'Stop'\nStart-Process -FilePath "
				+ powershellLiteral(powershell.toString()) + " -WindowStyle Normal -ArgumentList "
				+ powershellLiteral("-NoLogo -NoProfile -NoExit -EncodedCommand " + encodeScript(consoleScript)) + "\n";
		Process process = new ProcessBuilder(powershell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive",
				"-EncodedCommand", encodeScript(bootstrap)).redirectErrorStream(true)
				.redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
		try {
			if (!process.waitFor(30, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				throw new IOException("Timed out opening the Windows console");
			}
			if (process.exitValue() != 0) {
				// PowerShell diagnostics may include the encoded command (and a token).
				// Report the exit status without copying the payload into shared logs.
				throw new IOException("Windows console launcher exited with code " + process.exitValue());
			}
		} catch (InterruptedException e) {
			process.destroyForcibly();
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while opening the Windows console", e);
		}
		return true;
	}

	static Path powershellExecutable() {
		return Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
	}

	static String processStartInfoScript(Path java, List<String> arguments, Path workingDirectory) {
		return "$info = New-Object System.Diagnostics.ProcessStartInfo\n"
				+ "$info.FileName = " + powershellLiteral(java.toString()) + "\n"
				+ "$info.Arguments = " + powershellLiteral(arguments.stream()
						.map(WindowsConsoleLauncher::quoteWindowsArgument).collect(Collectors.joining(" "))) + "\n"
				+ "$info.WorkingDirectory = " + powershellLiteral(workingDirectory.toString()) + "\n"
				+ "$info.UseShellExecute = $false\n";
	}

	static String encodeScript(String script) {
		return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
	}

	private static String powershellLiteral(String value) {
		return "'" + value.replace("'", "''") + "'";
	}

	/** Quote one argument for the Windows Java launcher, not for a command shell. */
	private static String quoteWindowsArgument(String value) {
		StringBuilder result = new StringBuilder("\"");
		int backslashes = 0;
		for (char character : value.toCharArray()) {
			if (character == '\\') {
				backslashes++;
			} else {
				result.append("\\".repeat(character == '"' ? backslashes * 2 + 1 : backslashes));
				result.append(character);
				backslashes = 0;
			}
		}
		return result.append("\\".repeat(backslashes * 2)).append('"').toString();
	}
}
