package net.mezzdev.atomicfile.integration;

import net.mezzdev.atomicfile.AtomicFiles;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static net.mezzdev.atomicfile.integration.FaultyFileSystem.Operation;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicFilesFailureTest {
	private static final byte[] CONTENTS = "complete new contents".getBytes(StandardCharsets.UTF_8);
	@TempDir Path directory;

	static Stream<Arguments> writeFailures() {
		return Stream.of(WriteMode.values())
			.flatMap(mode -> Stream.of(Operation.CREATE, Operation.OPEN, Operation.WRITE, Operation.FORCE, Operation.CLOSE, Operation.MOVE)
				.map(operation -> Arguments.of(mode, operation)));
	}

	@ParameterizedTest
	@MethodSource("writeFailures")
	void ioFailurePreservesTheTargetAndCleansUpStaging(WriteMode mode, Operation operation) throws IOException {
		Path target = Files.writeString(directory.resolve("target"), "old");
		Path source = Files.write(directory.resolve("source"), CONTENTS);
		try (var fs = new FaultyFileSystem(target)) {
			IOException failure = fs.fail(operation);
			assertSame(failure, assertThrows(IOException.class, () -> write(mode, fs.wrap(target), source, CONTENTS)));
			assertEquals("old", Files.readString(target));
			assertArrayEquals(CONTENTS, Files.readAllBytes(source));
			assertEquals(List.of(source, target), children());
		}
	}

	@ParameterizedTest
	@EnumSource(WriteMode.class)
	void shortChannelWritesStillPublishEveryByte(WriteMode mode) throws IOException {
		byte[] bytes = new byte[1024];
		for (int i = 0; i < bytes.length; i++) {
			bytes[i] = (byte) i;
		}
		Path target = directory.resolve("target");
		Path source = Files.write(directory.resolve("source"), bytes);
		try (var fs = new FaultyFileSystem(target)) {
			fs.maximumWriteSize = 7;
			write(mode, fs.wrap(target), source, bytes);
		}
		assertArrayEquals(bytes, Files.readAllBytes(target));
		assertEquals(List.of(source, target), children());
	}

	@Test
	void failedCleanupPreservesTheOriginalStorageError() throws IOException {
		Path target = Files.writeString(directory.resolve("target"), "old");
		try (var fs = new FaultyFileSystem(target)) {
			IOException storageFailure = fs.fail(Operation.FORCE);
			IOException cleanupFailure = fs.fail(Operation.DELETE);
			IOException actual = assertThrows(IOException.class, () -> AtomicFiles.write(fs.wrap(target), CONTENTS));
			assertSame(storageFailure, actual);
			assertArrayEquals(new Throwable[] {cleanupFailure}, actual.getSuppressed());
			assertEquals("old", Files.readString(target));
			List<Path> abandoned = children().stream().filter(path -> !path.equals(target)).toList();
			assertEquals(1, abandoned.size());
			assertTrue(abandoned.get(0).toString().endsWith(".tmp"));
		}
	}

	@Test
	void sourceReadFailurePreservesBothFilesAndCleansUpTheCopy() throws IOException {
		Path target = Files.writeString(directory.resolve("target"), "old");
		Path source = Files.write(directory.resolve("source"), CONTENTS);
		try (var fs = new FaultyFileSystem(target)) {
			IOException failure = fs.fail(Operation.READ);
			assertSame(failure, assertThrows(IOException.class, () -> AtomicFiles.copy(fs.wrap(source), fs.wrap(target))));
		}
		assertEquals("old", Files.readString(target));
		assertArrayEquals(CONTENTS, Files.readAllBytes(source));
		assertEquals(List.of(source, target), children());
	}

	private static void write(WriteMode mode, Path target, Path source, byte[] bytes) throws IOException {
		switch (mode) {
			case BYTES -> AtomicFiles.write(target, bytes);
			case CALLBACK -> AtomicFiles.write(target, path -> Files.write(path, bytes));
			case COPY -> AtomicFiles.copy(source, target);
		}
	}

	private List<Path> children() throws IOException {
		try (var paths = Files.list(directory)) {
			return paths.sorted().toList();
		}
	}
}
