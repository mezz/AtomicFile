package net.mezzdev.atomicfile.integration;

import net.mezzdev.atomicfile.AtomicFiles;
import net.mezzdev.atomicfile.AtomicMovePolicy;
import net.mezzdev.atomicfile.FileContentWriter;
import net.mezzdev.atomicfile.WriteOptions;
import net.mezzdev.atomicfile.internal.FileIo;

import com.sun.nio.file.ExtendedOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class AtomicFilesOsIntegrationTest {
	private static final WriteOptions FALLBACK_OPTIONS = WriteOptions.DEFAULT
		.withAtomicMovePolicy(AtomicMovePolicy.ALLOW_NON_ATOMIC);

	@ParameterizedTest
	@EnumSource(WriteMode.class)
	@EnabledOnOs({OS.LINUX, OS.MAC})
	void replacementUsesPrivateStagingPermissionsEvenForAReadOnlyTarget(WriteMode mode, @TempDir Path directory) throws IOException {
		Path target = Files.writeString(directory.resolve("target"), "old");
		Path source = Files.writeString(directory.resolve("source"), "new");
		Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("r--r--r--"));
		Files.setPosixFilePermissions(source, PosixFilePermissions.fromString("rwxrwxrwx"));
		FileContentWriter writer = path -> {
			assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(path));
			Files.writeString(path, "new");
		};
		switch (mode) {
			case BYTES -> AtomicFiles.write(target, "new".getBytes(StandardCharsets.UTF_8));
			case CALLBACK -> AtomicFiles.write(target, writer);
			case COPY -> AtomicFiles.copy(source, target);
		}
		assertEquals("new", Files.readString(target));
		assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(target));
		assertEquals(PosixFilePermissions.fromString("rwxrwxrwx"), Files.getPosixFilePermissions(source));
	}

	@Test
	@EnabledOnOs({OS.LINUX, OS.MAC})
	void replacingAHardLinkPreservesTheOtherName(@TempDir Path directory) throws IOException {
		Path original = Files.writeString(directory.resolve("original"), "old");
		Path target = Files.createLink(directory.resolve("target"), original);
		AtomicFiles.write(target, "new".getBytes(StandardCharsets.UTF_8));
		assertEquals("old", Files.readString(original));
		assertEquals("new", Files.readString(target));
		assertFalse(Files.isSameFile(original, target));
	}

	@Test
	@EnabledOnOs({OS.LINUX, OS.MAC})
	void unwritableDirectoryPreservesTheTarget(@TempDir Path directory) throws IOException {
		Path target = Files.writeString(directory.resolve("target"), "old");
		var permissions = Files.getPosixFilePermissions(directory);
		try {
			Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-x------"));
			assumeFalse(Files.isWritable(directory), "Requires an account that cannot bypass directory permissions");
			assertThrows(IOException.class, () -> AtomicFiles.write(target, "new".getBytes(StandardCharsets.UTF_8)));
		} finally {
			Files.setPosixFilePermissions(directory, permissions);
		}
		assertEquals("old", Files.readString(target));
		try (var children = Files.list(directory)) {
			assertEquals(List.of(target), children.toList());
		}
	}

	@Test
	@EnabledOnOs({OS.LINUX, OS.MAC})
	void directorySyncPermissionFailureIsBestEffort(@TempDir Path directory) throws IOException {
		Path target = directory.resolve("target");
		var permissions = Files.getPosixFilePermissions(directory);
		try {
			// Search/write permission allows replacement, but opening the directory for sync needs read permission.
			Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("-wx------"));
			assumeFalse(Files.isReadable(directory), "Requires an account that cannot bypass directory permissions");
			AtomicFiles.write(target, "new".getBytes(StandardCharsets.UTF_8));
		} finally {
			Files.setPosixFilePermissions(directory, permissions);
		}
		assertEquals("new", Files.readString(target));
		try (var children = Files.list(directory)) {
			assertEquals(List.of(target), children.toList());
		}
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	@Timeout(10)
	void copyingWaitsForAScannerThatDeniesReadSharing(@TempDir Path tempDir) throws Exception {
		Path source = Files.writeString(tempDir.resolve("source.txt"), "complete");
		Path target = Files.writeString(tempDir.resolve("target.txt"), "old");
		var executor = Executors.newSingleThreadExecutor();
		try {
			Future<?> copy;
			try (var scanner = FileChannel.open(source, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_READ)) {
				CountDownLatch started = new CountDownLatch(1);
				copy = executor.submit(() -> {
					started.countDown();
					AtomicFiles.copy(source, target);
					return null;
				});
				assertTrue(started.await(5, TimeUnit.SECONDS));
				assertThrows(TimeoutException.class, () -> copy.get(100, TimeUnit.MILLISECONDS));
				assertEquals("old", Files.readString(target));
			}
			copy.get(5, TimeUnit.SECONDS);
			assertEquals("complete", Files.readString(source));
			assertEquals("complete", Files.readString(target));
			try (var files = Files.list(tempDir)) {
				assertEquals(2, files.count());
			}
		} finally {
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	@Timeout(10)
	void cleanupFailureDoesNotHideTheOriginalFailure(@TempDir Path tempDir) throws Exception {
		Path target = Files.writeString(tempDir.resolve("target.txt"), "old");
		var scanner = new AtomicReference<FileChannel>();
		var staged = new AtomicReference<Path>();
		IOException original = new IOException("serialization failed");
		try {
			IOException actual = assertThrows(IOException.class, () -> AtomicFiles.write(target, temporaryFile -> {
				staged.set(temporaryFile);
				Files.writeString(temporaryFile, "partial");
				scanner.set(FileChannel.open(temporaryFile, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE));
				throw original;
			}));
			assertSame(original, actual);
			assertInstanceOf(FileSystemException.class, actual.getSuppressed()[0]);
			assertEquals("old", Files.readString(target));
			assertTrue(Files.exists(staged.get()));
		} finally {
			if (scanner.get() != null) {
				scanner.get().close();
			}
			if (staged.get() != null) {
				Files.deleteIfExists(staged.get());
			}
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@EnabledOnOs({OS.LINUX, OS.MAC})
	public void replacementPreservesOpenReadersAfterAnotherProvidersFallback(boolean useFallbackFirst, @TempDir Path tempDir) throws Exception {
		if (useFallbackFirst) {
			moveFromZipFileSystem(tempDir);
		}

		Path source = tempDir.resolve("staged.ini");
		Path target = tempDir.resolve("config.ini");
		Files.writeString(source, "new");
		Files.writeString(target, "old");
		try (var reader = Files.newInputStream(target)) {
			// Unix rename keeps existing readers on the old file after replacement.
			AtomicFiles.moveReplace(source, target, AtomicMovePolicy.ALLOW_NON_ATOMIC, WriteOptions.DEFAULT.retryTimeout());
			assertEquals("old", new String(reader.readAllBytes(), StandardCharsets.UTF_8));
			assertEquals("new", Files.readString(target));
			assertFalse(Files.exists(source));
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@EnabledOnOs(OS.WINDOWS)
	@Timeout(10)
	public void replacementPreservesFilesUntilTargetReaderCloses(boolean useFallbackFirst, @TempDir Path tempDir) throws Exception {
		if (useFallbackFirst) {
			moveFromZipFileSystem(tempDir);
		}
		Path source = Files.writeString(tempDir.resolve("staged.ini"), "new");
		Path target = Files.writeString(tempDir.resolve("config.ini"), "old");
		try (var reader = Files.newInputStream(target)) {
			// Windows can reject replacement even when the reader permits delete sharing.
			assertThrows(FileSystemException.class, () -> AtomicFiles.moveReplace(source, target, AtomicMovePolicy.ALLOW_NON_ATOMIC, WriteOptions.DEFAULT.retryTimeout()));
			assertEquals("old", new String(reader.readAllBytes(), StandardCharsets.UTF_8));
			assertEquals("old", Files.readString(target));
			assertEquals("new", Files.readString(source));
		}

		AtomicFiles.moveReplace(source, target, AtomicMovePolicy.ALLOW_NON_ATOMIC, WriteOptions.DEFAULT.retryTimeout());
		assertEquals("new", Files.readString(target));
		assertFalse(Files.exists(source));
	}

	private static void moveFromZipFileSystem(Path tempDir) throws IOException {
		// A real move between providers requires the non-atomic copy/delete fallback.
		try (var archive = FileSystems.newFileSystem(tempDir.resolve("archive.zip"), Map.of("create", "true"))) {
			Path archivedSource = archive.getPath("/source.ini");
			Files.writeString(archivedSource, "archived");
			Path extracted = tempDir.resolve("extracted.ini");
			AtomicFiles.moveReplace(archivedSource, extracted, AtomicMovePolicy.ALLOW_NON_ATOMIC, WriteOptions.DEFAULT.retryTimeout());
			assertEquals("archived", Files.readString(extracted));
			assertFalse(Files.exists(archivedSource));
		}
	}

	@Test
	public void failedReplacementPreservesTheTargetAndCleansUpStagedOutput(@TempDir Path tempDir) throws Exception {
		Path target = Files.createDirectory(tempDir.resolve("config.ini"));
		Path child = Files.writeString(target.resolve("keep.txt"), "original");

		assertThrows(IOException.class, () -> AtomicFiles.write(target, "new".getBytes(StandardCharsets.UTF_8), FALLBACK_OPTIONS));

		assertEquals("original", Files.readString(child));
		try (var files = Files.list(tempDir)) {
			assertEquals(List.of(target), files.toList());
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@EnabledOnOs(OS.WINDOWS)
	@Timeout(10)
	public void replacementWaitsForSharingLocks(boolean lockSource, @TempDir Path tempDir) throws Exception {
		Path source = Files.writeString(tempDir.resolve("staged.ini"), "new");
		Path target = Files.writeString(tempDir.resolve("config.ini"), "old");
		Path lockedPath = target;
		if (lockSource) {
			lockedPath = source;
		}
		var executor = Executors.newSingleThreadExecutor();
		try {
			Future<?> replacement;
			try (var lock = FileChannel.open(lockedPath, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE)) {
				// Verify the real Windows sharing restriction before asking the utility to wait for it.
				assertThrows(FileSystemException.class, () -> Files.move(source, target, StandardCopyOption.ATOMIC_MOVE));
				CountDownLatch started = new CountDownLatch(1);
				replacement = executor.submit(() -> {
					started.countDown();
					AtomicFiles.moveReplace(source, target, AtomicMovePolicy.ALLOW_NON_ATOMIC, WriteOptions.DEFAULT.retryTimeout());
					return null;
				});
				assertTrue(started.await(5, TimeUnit.SECONDS));
				assertThrows(TimeoutException.class, () -> replacement.get(100, TimeUnit.MILLISECONDS));
				assertEquals("old", Files.readString(target));
				assertEquals("new", Files.readString(source));
			}
			replacement.get(5, TimeUnit.SECONDS);
			assertEquals("new", Files.readString(target));
			assertFalse(Files.exists(source));
		} finally {
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	@Timeout(10)
	public void stagedWriteWaitsForAScannerThatDeniesWriteSharing(@TempDir Path tempDir) throws Exception {
		Path target = Files.writeString(tempDir.resolve("config.ini"), "old");
		Path staged = Files.createTempFile(tempDir, "staged-", ".tmp");
		byte[] bytes = "complete replacement".getBytes(StandardCharsets.UTF_8);
		var executor = Executors.newSingleThreadExecutor();
		try {
			Future<?> save;
			// Model a scanner winning the race between createTempFile's close and the writer's reopen.
			try (var scanner = FileChannel.open(staged, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_WRITE)) {
				CountDownLatch started = new CountDownLatch(1);
				save = executor.submit(() -> {
					started.countDown();
					FileIo.write(staged, bytes);
					AtomicFiles.moveReplace(staged, target, AtomicMovePolicy.REQUIRE_ATOMIC, WriteOptions.DEFAULT.retryTimeout());
					return null;
				});
				assertTrue(started.await(5, TimeUnit.SECONDS));
				assertThrows(TimeoutException.class, () -> save.get(100, TimeUnit.MILLISECONDS));
				assertEquals("old", Files.readString(target));
				assertEquals(0, Files.size(staged));
			}
			save.get(5, TimeUnit.SECONDS);
			assertEquals("complete replacement", Files.readString(target));
			assertFalse(Files.exists(staged));
		} finally {
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	@Timeout(10)
	public void cleanupWaitsForAScannerThatDeniesDeleteSharing(@TempDir Path tempDir) throws Exception {
		Path staged = Files.createTempFile(tempDir, "staged-", ".tmp");
		var executor = Executors.newSingleThreadExecutor();
		try {
			Future<?> cleanup;
			try (var scanner = FileChannel.open(staged, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE)) {
				CountDownLatch started = new CountDownLatch(1);
				cleanup = executor.submit(() -> {
					started.countDown();
					AtomicFiles.deleteIfExists(staged, WriteOptions.DEFAULT.retryTimeout());
					return null;
				});
				assertTrue(started.await(5, TimeUnit.SECONDS));
				assertThrows(TimeoutException.class, () -> cleanup.get(100, TimeUnit.MILLISECONDS));
				assertTrue(Files.exists(staged));
			}
			cleanup.get(5, TimeUnit.SECONDS);
			assertFalse(Files.exists(staged));
		} finally {
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	@Timeout(10)
	public void persistentSharingLockPreservesTheTargetAndCleansUpStagedOutput(@TempDir Path tempDir) throws Exception {
		Path target = Files.writeString(tempDir.resolve("config.ini"), "old");
		try (var lock = FileChannel.open(target, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE)) {
			assertThrows(FileSystemException.class, () -> AtomicFiles.write(target, "new".getBytes(StandardCharsets.UTF_8), FALLBACK_OPTIONS));
			assertEquals("old", Files.readString(target));
			try (var files = Files.list(tempDir)) {
				assertEquals(List.of(target), files.toList());
			}
		}
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	@Timeout(10)
	public void interruptedRetryPreservesBothFiles(@TempDir Path tempDir) throws Exception {
		Path source = Files.writeString(tempDir.resolve("staged.ini"), "new");
		Path target = Files.writeString(tempDir.resolve("config.ini"), "old");
		try (var lock = FileChannel.open(target, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE)) {
			Thread.currentThread().interrupt();
			try {
				InterruptedIOException failure = assertThrows(
					InterruptedIOException.class,
					() -> AtomicFiles.moveReplace(source, target, AtomicMovePolicy.ALLOW_NON_ATOMIC, WriteOptions.DEFAULT.retryTimeout())
				);
				assertTrue(Thread.currentThread().isInterrupted());
				assertTrue(failure.getSuppressed()[0] instanceof FileSystemException);
			} finally {
				Thread.interrupted();
			}
			assertEquals("new", Files.readString(source));
			assertEquals("old", Files.readString(target));
		}
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	@Timeout(10)
	void readOnlyTargetPreservesOldContentsUntilItsAttributeIsCleared(@TempDir Path directory) throws IOException {
		Path target = Files.writeString(directory.resolve("target"), "old");
		byte[] bytes = "new".getBytes(StandardCharsets.UTF_8);
		Files.setAttribute(target, "dos:readonly", true);
		try {
			assertThrows(FileSystemException.class, () -> AtomicFiles.write(target, bytes, FALLBACK_OPTIONS));
			assertEquals("old", Files.readString(target));
			try (var files = Files.list(directory)) {
				List<Path> staged = files.filter(path -> !path.equals(target)).toList();
				assertTrue(staged.isEmpty());
			}
		} finally {
			Files.setAttribute(target, "dos:readonly", false);
		}
		AtomicFiles.write(target, bytes);
		assertEquals("new", Files.readString(target));
		try (var files = Files.list(directory)) {
			assertEquals(List.of(target), files.toList());
		}
	}

}
