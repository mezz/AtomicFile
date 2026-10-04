package net.mezzdev.atomicfile.integration;

import net.mezzdev.atomicfile.AtomicFiles;
import net.mezzdev.atomicfile.AtomicMovePolicy;
import net.mezzdev.atomicfile.WriteOptions;
import net.mezzdev.atomicfile.internal.FileIo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AtomicFilesTest {
	@TempDir
	Path directory;

	@ParameterizedTest
	@ValueSource(ints = {0, 1, 1_000_000})
	void writesCompleteBinaryContentsAndCreatesParents(int size) throws IOException {
		Path target = directory.resolve("nested/more/data.bin");
		byte[] expected = new byte[size];
		for (int i = 0; i < expected.length; i++) {
			expected[i] = (byte) i;
		}

		AtomicFiles.write(target, expected);

		assertArrayEquals(expected, Files.readAllBytes(target));
		assertOnlyContains(target.getParent(), target);
	}

	@Test
	void stagesBesideTargetWithoutExposingPartialContents() throws IOException {
		Path target = Files.writeString(directory.resolve("data.txt"), "old");
		AtomicFiles.write(target, temporaryFile -> {
			assertEquals(target.getParent(), temporaryFile.getParent());
			assertEquals(Files.getFileStore(target), Files.getFileStore(temporaryFile));
			Files.writeString(temporaryFile, "partial");
			assertEquals("old", Files.readString(target));
			Files.writeString(temporaryFile, " complete", StandardOpenOption.APPEND);
			assertEquals("old", Files.readString(target));
		});

		assertEquals("partial complete", Files.readString(target));
		assertOnlyContains(directory, target);
	}

	@ParameterizedTest
	@ValueSource(strings = {"data.txt", "nested/data.txt", "/absolute/data.txt"})
	void stagesOnTheTargetsProviderIncludingParentlessPaths(String name) throws IOException {
		Path archivePath = directory.resolve("data.zip");
		try (var archive = FileSystems.newFileSystem(archivePath, Map.of("create", "true"))) {
			Path target = archive.getPath(name);
			AtomicFiles.write(target, temporaryFile -> {
				assertSame(archive, temporaryFile.getFileSystem());
				assertEquals(target.toAbsolutePath().getParent(), temporaryFile.getParent());
				Files.writeString(temporaryFile, "café ☃");
			});
			AtomicFiles.write(target, "replacement".getBytes(StandardCharsets.UTF_8));
			assertEquals("replacement", Files.readString(target));
			assertOnlyContains(target.toAbsolutePath().getParent(), target.toAbsolutePath());
		}
		try (var archive = FileSystems.newFileSystem(archivePath)) {
			assertEquals("replacement", Files.readString(archive.getPath(name)));
		}
	}

	@Test
	void supportsRelativePathsOnTheDefaultFilesystem() throws IOException {
		Path target = directory.resolve("data.txt");
		Path relative = Path.of("").toAbsolutePath().relativize(target);
		AtomicFiles.write(relative, temporaryFile -> {
			assertEquals(directory.toRealPath(), temporaryFile.getParent().toRealPath());
			Files.writeString(temporaryFile, "complete");
		});
		assertEquals("complete", Files.readString(target));
		assertOnlyContains(directory, target);
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void failedCallbackPreservesTargetAndCleansUp(boolean targetExists) throws IOException {
		Path target = directory.resolve("data.txt");
		if (targetExists) {
			Files.writeString(target, "old");
		}
		for (Throwable failure : List.of(new IOException("disk full"), new IllegalStateException("serializer failed"), new AssertionError("failed"))) {
			AtomicInteger calls = new AtomicInteger();
			Throwable actual = assertThrows(failure.getClass(), () -> AtomicFiles.write(target, temporaryFile -> {
				calls.incrementAndGet();
				Files.writeString(temporaryFile, "partial");
				if (failure instanceof IOException io) {
					throw io;
				} else if (failure instanceof RuntimeException runtime) {
					throw runtime;
				} else {
					throw (Error) failure;
				}
			}));
			assertSame(failure, actual);
			assertEquals(1, calls.get(), "user callbacks must never be retried");
			if (targetExists) {
				assertEquals("old", Files.readString(target));
				assertOnlyContains(directory, target);
			} else {
				assertOnlyContains(directory);
			}
		}
	}

	@Test
	void copiesAcrossFilesystemsByStagingAtDestination() throws IOException {
		Path target = Files.writeString(directory.resolve("data.txt"), "old");
		try (var archive = FileSystems.newFileSystem(directory.resolve("data.zip"), Map.of("create", "true"))) {
			Path source = Files.writeString(archive.getPath("/source"), "complete");
			AtomicFiles.copy(source, target);
			assertEquals("complete", Files.readString(source));
			assertEquals("complete", Files.readString(target));
			Path reverseTarget = archive.getPath("reverse/target");
			AtomicFiles.copy(target, reverseTarget);
			assertEquals("complete", Files.readString(reverseTarget));
			assertOnlyContains(reverseTarget.toAbsolutePath().getParent(), reverseTarget.toAbsolutePath());
		}
		assertOnlyContains(directory, target, directory.resolve("data.zip"));
	}

	@Test
	void failedCopyPreservesTargetAndCleansUp() throws IOException {
		Path target = Files.writeString(directory.resolve("data.txt"), "old");
		assertThrows(IOException.class, () -> AtomicFiles.copy(directory.resolve("missing"), target));
		assertEquals("old", Files.readString(target));
		assertOnlyContains(directory, target);
	}

	@Test
	void copyingOntoItselfDoesNotTruncateTheSource() throws IOException {
		Path target = Files.writeString(directory.resolve("data.txt"), "complete");
		AtomicFiles.copy(target, target);
		assertEquals("complete", Files.readString(target));
		assertOnlyContains(directory, target);
	}

	@Test
	void strictMoveAcrossProvidersPreservesBothFiles() throws IOException {
		Path target = Files.writeString(directory.resolve("data.txt"), "old");
		try (var archive = FileSystems.newFileSystem(directory.resolve("data.zip"), Map.of("create", "true"))) {
			Path source = Files.writeString(archive.getPath("/source"), "new");
			assertThrows(AtomicMoveNotSupportedException.class, () -> FileIo.moveReplace(source, target, AtomicMovePolicy.REQUIRE_ATOMIC, WriteOptions.DEFAULT.retryTimeout()));
			assertEquals("old", Files.readString(target));
			assertEquals("new", Files.readString(source));
			FileIo.moveReplace(source, target, AtomicMovePolicy.ALLOW_NON_ATOMIC, WriteOptions.DEFAULT.retryTimeout());
			assertEquals("new", Files.readString(target));
			assertFalse(Files.exists(source));
		}
	}

	@Test
	void failedFallbackRetainsTheUnsupportedAtomicMoveAsEvidence() throws IOException {
		Path target = Files.createDirectory(directory.resolve("target"));
		Path child = Files.writeString(target.resolve("child"), "old");
		try (var archive = FileSystems.newFileSystem(directory.resolve("data.zip"), Map.of("create", "true"))) {
			Path source = Files.writeString(archive.getPath("/source"), "new");
			IOException failure = assertThrows(IOException.class, () -> FileIo.moveReplace(source, target, AtomicMovePolicy.ALLOW_NON_ATOMIC, WriteOptions.DEFAULT.retryTimeout()));
			assertInstanceOf(AtomicMoveNotSupportedException.class, failure.getSuppressed()[0]);
			assertEquals("old", Files.readString(child));
			assertEquals("new", Files.readString(source));
		}
	}

	@Test
	@EnabledOnOs({OS.LINUX, OS.MAC})
	void followsSymlinkedParentsWithoutNormalizingAwayTheirMeaning() throws IOException {
		Path actual = Files.createDirectories(directory.resolve("actual/child"));
		Path link = Files.createSymbolicLink(directory.resolve("link"), actual);
		Path target = link.resolve("../data.txt");
		AtomicFiles.write(target, temporaryFile -> {
			assertEquals(actual.getParent().toRealPath(), temporaryFile.getParent().toRealPath());
			Files.writeString(temporaryFile, "complete");
		});
		assertEquals("complete", Files.readString(actual.getParent().resolve("data.txt")));
		assertFalse(Files.exists(directory.resolve("data.txt")));
	}

	@Test
	@EnabledOnOs({OS.LINUX, OS.MAC})
	void replacesTargetSymlinkWithoutWritingThroughIt() throws IOException {
		Path original = Files.writeString(directory.resolve("original.txt"), "original");
		Path target = Files.createSymbolicLink(directory.resolve("link.txt"), original);
		AtomicFiles.write(target, "replacement".getBytes(StandardCharsets.UTF_8));
		assertFalse(Files.isSymbolicLink(target));
		assertEquals("replacement", Files.readString(target));
		assertEquals("original", Files.readString(original));
	}

	private static void assertOnlyContains(Path directory, Path... expected) throws IOException {
		try (var files = Files.list(directory)) {
			assertEquals(Arrays.stream(expected).sorted().toList(), files.sorted().toList());
		}
	}
}
