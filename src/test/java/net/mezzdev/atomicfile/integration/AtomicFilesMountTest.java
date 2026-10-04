package net.mezzdev.atomicfile.integration;

import net.mezzdev.atomicfile.AtomicFiles;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@EnabledOnOs(OS.LINUX)
class AtomicFilesMountTest {
	@Test
	void writesAndCopiesToAnotherMountedFilesystem(@TempDir Path directory) throws IOException {
		Path otherMount = Path.of("/dev/shm");
		assumeTrue(Files.isDirectory(otherMount) && Files.isWritable(otherMount), "Requires a writable /dev/shm mount");
		assumeTrue(!Files.getFileStore(directory).equals(Files.getFileStore(otherMount)), "Requires distinct mounts");
		Path otherDirectory = Files.createTempDirectory(otherMount, "atomicfile-test-");
		Path source = Files.writeString(directory.resolve("source.txt"), "complete");
		Path target = otherDirectory.resolve("target.txt");
		try {
			// Prove a JVM-temp-style staging location would cross an actual mount boundary.
			assertThrows(AtomicMoveNotSupportedException.class, () -> Files.move(source, target, StandardCopyOption.ATOMIC_MOVE));
			AtomicFiles.write(target, temporaryFile -> {
				assertEquals(Files.getFileStore(otherDirectory), Files.getFileStore(temporaryFile));
				Files.writeString(temporaryFile, "written");
			});
			assertEquals("written", Files.readString(target));
			AtomicFiles.copy(source, target);
			assertEquals("complete", Files.readString(source));
			assertEquals("complete", Files.readString(target));
			try (var children = Files.list(otherDirectory)) {
				assertEquals(1, children.count());
			}
		} finally {
			// Also clean up any staging files if the regression test fails.
			try (var children = Files.list(otherDirectory)) {
				for (Path child : children.toList()) {
					Files.deleteIfExists(child);
				}
			}
			Files.delete(otherDirectory);
		}
	}
}
