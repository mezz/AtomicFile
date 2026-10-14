package net.mezzdev.atomicfile.integration;

import net.mezzdev.atomicfile.AtomicFiles;
import net.mezzdev.atomicfile.AtomicMovePolicy;
import net.mezzdev.atomicfile.StagedFile;
import net.mezzdev.atomicfile.WriteOptions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import static net.mezzdev.atomicfile.integration.FaultyFileSystem.Operation;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StagedFileTest {
	private static final byte[] CONTENTS = "complete".getBytes(StandardCharsets.UTF_8);
	@TempDir Path directory;

	@Test
	void preparingSeveralFilesLeavesTheirDestinationsUntouchedUntilCommit() throws IOException {
		Path first = Files.writeString(directory.resolve("first"), "old");
		Path second = directory.resolve("nested/second");
		try (StagedFile a = AtomicFiles.stage(first, CONTENTS);
			 StagedFile b = AtomicFiles.stage(second, CONTENTS)) {
			assertEquals("old", Files.readString(first));
			assertFalse(Files.exists(second));
			a.commit();
			assertEquals("complete", Files.readString(first));
			assertFalse(Files.exists(second));
			b.commit();
			assertEquals("complete", Files.readString(second));
			assertThrows(IllegalStateException.class, a::commit);
		}
		assertOnlyContains(first.getParent(), first, second.getParent());
		assertOnlyContains(second.getParent(), second);
	}

	@Test
	void closingUncommittedFilesDiscardsPreparedContents() throws IOException {
		Path target = Files.writeString(directory.resolve("target"), "old");
		Path source = Files.write(directory.resolve("source"), CONTENTS);
		try (StagedFile prepared = AtomicFiles.stageCopy(source, target)) {
			assertEquals("old", Files.readString(target));
			assertArrayEquals(CONTENTS, Files.readAllBytes(prepared.path()));
		}
		assertEquals("old", Files.readString(target));
		assertArrayEquals(CONTENTS, Files.readAllBytes(source));
		assertOnlyContains(directory, source, target);
	}

	@Test
	void snapshotRestoresEarlierContentsAndModificationTime() throws IOException {
		Path target = Files.writeString(directory.resolve("target"), "original");
		FileTime modified = FileTime.fromMillis(1_600_000_000_000L);
		Files.setLastModifiedTime(target, modified);

		try (StagedFile rollback = AtomicFiles.snapshot(target)) {
			AtomicFiles.write(target, CONTENTS);
			assertEquals("complete", Files.readString(target));
			rollback.commit();
		}

		assertEquals("original", Files.readString(target));
		assertEquals(modified, Files.getLastModifiedTime(target));
		assertOnlyContains(directory, target);
	}

	@Test
	void failedCommitRetainsContentsAndCanBeRetried() throws IOException {
		Path target = Files.writeString(directory.resolve("target"), "old");
		try (var fs = new FaultyFileSystem(target);
			 StagedFile prepared = AtomicFiles.stage(fs.wrap(target), CONTENTS)) {
			IOException failure = fs.fail(Operation.MOVE);
			assertSame(failure, assertThrows(IOException.class, prepared::commit));
			assertEquals("old", Files.readString(target));
			assertArrayEquals(CONTENTS, Files.readAllBytes(prepared.path()));
			fs.clearFailure(Operation.MOVE);
			prepared.commit();
		}
		assertEquals("complete", Files.readString(target));
		assertOnlyContains(directory, target);
	}

	@Test
	void comparisonTracksMissingAndChangedDestinations() throws IOException {
		Path target = directory.resolve("target");
		try (StagedFile prepared = AtomicFiles.stage(target, CONTENTS)) {
			assertFalse(prepared.isUnchanged());
			Files.write(target, CONTENTS);
			assertTrue(prepared.isUnchanged());
			Files.writeString(target, "complete plus trailing bytes");
			assertFalse(prepared.isUnchanged());
		}
		assertEquals("complete plus trailing bytes", Files.readString(target));
		assertOnlyContains(directory, target);
	}

	@Test
	void cleanupFailureIsSuppressedWhenClosingAfterAnEarlierFailure() throws IOException {
		Path target = Files.writeString(directory.resolve("target"), "old");
		IOException original = new IOException("transaction failed");
		try (var fs = new FaultyFileSystem(target)) {
			StagedFile prepared = AtomicFiles.stage(fs.wrap(target), CONTENTS);
			IOException cleanup = fs.fail(Operation.DELETE);
			IOException actual = assertThrows(IOException.class, () -> {
				try (prepared) {
					throw original;
				}
			});
			assertSame(original, actual);
			assertArrayEquals(new Throwable[] {cleanup}, actual.getSuppressed());
			assertEquals("old", Files.readString(target));
			assertTrue(Files.exists(prepared.path()));
			fs.clearFailure(Operation.DELETE);
			prepared.close();
		}
		assertOnlyContains(directory, target);
	}

	@Test
	@EnabledOnOs({OS.LINUX, OS.MAC})
	void copyingAttributesPreservesReadOnlyPermissionsAndModificationTime() throws IOException {
		Path source = Files.write(directory.resolve("source"), CONTENTS);
		Path target = directory.resolve("target");
		FileTime modified = FileTime.fromMillis(1_600_000_000_000L);
		var permissions = PosixFilePermissions.fromString("r--r-----");
		Files.setLastModifiedTime(source, modified);
		Files.setPosixFilePermissions(source, permissions);
		try (StagedFile prepared = AtomicFiles.stageCopy(source, target, WriteOptions.DEFAULT, StandardCopyOption.COPY_ATTRIBUTES)) {
			assertFalse(Files.exists(target));
			prepared.commit();
		}
		assertArrayEquals(CONTENTS, Files.readAllBytes(target));
		assertEquals(modified, Files.getLastModifiedTime(target));
		assertEquals(permissions, Files.getPosixFilePermissions(target));
		assertEquals(permissions, Files.getPosixFilePermissions(source));
	}

	@Test
	void stagedCopyUsesTheDestinationProviderAndSupportsFallback() throws IOException {
		Path source = Files.write(directory.resolve("source"), CONTENTS);
		WriteOptions options = WriteOptions.DEFAULT.withAtomicMovePolicy(AtomicMovePolicy.ALLOW_NON_ATOMIC);
		try (var archive = FileSystems.newFileSystem(directory.resolve("archive.zip"), Map.of("create", "true"))) {
			Path target = archive.getPath("/nested/target");
			try (StagedFile prepared = AtomicFiles.stageCopy(source, target, options)) {
				assertSame(archive, prepared.path().getFileSystem());
				assertFalse(Files.exists(target));
				prepared.commit();
			}
			assertArrayEquals(CONTENTS, Files.readAllBytes(target));
			assertOnlyContains(target.getParent(), target);
		}
		assertArrayEquals(CONTENTS, Files.readAllBytes(source));
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@EnabledOnOs(OS.WINDOWS)
	void readOnlyCopiesCanBeCommittedOrDiscarded(boolean commit) throws IOException {
		Path source = Files.write(directory.resolve("source"), CONTENTS);
		Path target = Files.writeString(directory.resolve("target"), "old");
		DosFileAttributeView sourceAttributes = Files.getFileAttributeView(source, DosFileAttributeView.class);
		DosFileAttributeView targetAttributes = Files.getFileAttributeView(target, DosFileAttributeView.class);
		sourceAttributes.setReadOnly(true);
		try {
			try (StagedFile prepared = AtomicFiles.stageCopy(source, target, WriteOptions.DEFAULT, StandardCopyOption.COPY_ATTRIBUTES)) {
				if (commit) {
					prepared.commit();
				}
			}
			assertEquals(commit ? "complete" : "old", Files.readString(target));
			assertEquals(commit, targetAttributes.readAttributes().isReadOnly());
			assertTrue(sourceAttributes.readAttributes().isReadOnly());
			assertOnlyContains(directory, source, target);
		} finally {
			sourceAttributes.setReadOnly(false);
			targetAttributes.setReadOnly(false);
		}
	}

	private static void assertOnlyContains(Path parent, Path... expected) throws IOException {
		try (var children = Files.list(parent)) {
			assertEquals(List.of(expected).stream().sorted().toList(), children.sorted().toList());
		}
	}
}
