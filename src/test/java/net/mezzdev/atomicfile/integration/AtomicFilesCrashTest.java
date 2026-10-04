package net.mezzdev.atomicfile.integration;

import net.mezzdev.atomicfile.AtomicFiles;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicFilesCrashTest {
	enum CrashCheckpoint { EMPTY, WRITING, FORCED, PUBLISHED }

	@TempDir Path directory;

	static Stream<Arguments> crashStates() {
		return Stream.of(false, true).flatMap(targetExists ->
			Stream.of(CrashCheckpoint.values()).map(checkpoint -> Arguments.of(targetExists, checkpoint)));
	}

	@ParameterizedTest
	@MethodSource("crashStates")
	@Timeout(20)
	void abruptTerminationNeverExposesPartialContents(boolean targetExists, CrashCheckpoint checkpoint) throws Exception {
		Path target = directory.resolve("data/settings");
		Files.createDirectories(target.getParent());
		if (targetExists) {
			Files.writeString(target, "old");
		}
		runCrashWriter(target, checkpoint);
		if (checkpoint == CrashCheckpoint.PUBLISHED) {
			assertEquals("12345", Files.readString(target));
			assertEquals(List.of(target), children(target.getParent()));
		} else {
			List<Path> staged = children(target.getParent()).stream().filter(path -> !path.equals(target)).toList();
			assertEquals(1, staged.size());
			assertTrue(staged.get(0).toString().endsWith(".tmp"));
			assertEquals(checkpoint == CrashCheckpoint.EMPTY ? "" : checkpoint == CrashCheckpoint.WRITING ? "123" : "12345",
				Files.readString(staged.get(0)));
			if (targetExists) {
				assertEquals("old", Files.readString(target));
			} else {
				assertFalse(Files.exists(target));
			}
		}
	}

	private void runCrashWriter(Path target, CrashCheckpoint checkpoint) throws Exception {
		Path log = directory.resolve("child.log");
		String executable = FileSystems.getDefault().getSeparator().equals("\\") ? "java.exe" : "java";
		String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
		Process child = new ProcessBuilder(
			Path.of(System.getProperty("java.home"), "bin", executable).toString(),
			"-cp", classpath, CrashWriter.class.getName(), target.toString(), checkpoint.name()
		).redirectErrorStream(true).redirectOutput(log.toFile()).start();
		try {
			assertTrue(child.waitFor(10, TimeUnit.SECONDS), "child timed out");
			assertEquals(23, child.exitValue(), () -> "child failed: " + readLog(log));
		} finally {
			if (child.isAlive()) {
				child.destroyForcibly();
				assertTrue(child.waitFor(5, TimeUnit.SECONDS));
			}
		}
	}

	private static List<Path> children(Path parent) throws IOException {
		try (var children = Files.list(parent)) {
			return children.sorted().toList();
		}
	}

	private static String readLog(Path path) {
		try {
			return Files.readString(path);
		} catch (IOException failure) {
			return failure.toString();
		}
	}

	/** Halts a child JVM during staging or after replacement without running cleanup. */
	public static class CrashWriter {
		/**
		 * Writes to the requested checkpoint and halts with exit code 23.
		 *
		 * @param args target path and checkpoint, in that order
		 * @throws IOException if writing fails before the requested checkpoint
		 */
		public static void main(String[] args) throws IOException {
			Path target = Path.of(args[0]);
			CrashCheckpoint checkpoint = CrashCheckpoint.valueOf(args[1]);
			if (checkpoint == CrashCheckpoint.PUBLISHED) {
				AtomicFiles.write(target, "12345".getBytes(StandardCharsets.UTF_8));
			} else {
				AtomicFiles.write(target, path -> {
					if (checkpoint == CrashCheckpoint.WRITING) {
						Files.writeString(path, "123");
					} else if (checkpoint == CrashCheckpoint.FORCED) {
						try (var channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
							ByteBuffer buffer = ByteBuffer.wrap("12345".getBytes(StandardCharsets.UTF_8));
							while (buffer.hasRemaining()) {
								channel.write(buffer);
							}
							channel.force(true);
						}
					}
					Runtime.getRuntime().halt(23);
				});
			}
			Runtime.getRuntime().halt(23);
		}
	}
}
