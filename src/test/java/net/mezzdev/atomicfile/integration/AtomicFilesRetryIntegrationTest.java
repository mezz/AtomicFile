package net.mezzdev.atomicfile.integration;

import net.mezzdev.atomicfile.AtomicFiles;
import net.mezzdev.atomicfile.AtomicMovePolicy;
import net.mezzdev.atomicfile.WriteOptions;
import net.mezzdev.atomicfile.internal.FileIo;

import com.sun.nio.file.ExtendedOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
@Timeout(10)
class AtomicFilesRetryIntegrationTest {
	enum Budget {
		DISABLED(Duration.ZERO), SHORT(Duration.ofMillis(200));
		final Duration timeout;
		Budget(Duration timeout) { this.timeout = timeout; }
	}
	enum Operation { MOVE, DELETE }
	@TempDir Path directory;

	static Stream<Arguments> writes() {
		return Arrays.stream(WriteMode.values()).flatMap(mode -> Arrays.stream(Budget.values()).map(budget -> Arguments.of(mode, budget)));
	}

	@ParameterizedTest
	@MethodSource("writes")
	void writeBudgetBoundsSharingRetriesAndPreservesOldContents(WriteMode mode, Budget budget) throws IOException {
		Path source = Files.writeString(directory.resolve("source"), "new");
		Path target = Files.writeString(directory.resolve("target"), "old");
		WriteOptions options = WriteOptions.DEFAULT.withRetryTimeout(budget.timeout);
		try (var lock = FileChannel.open(target, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE)) {
			long started = System.nanoTime();
			assertTimeout(Duration.ofSeconds(1), () -> assertThrows(FileSystemException.class, () ->
				AtomicFilesOptionsIntegrationTest.write(mode, source, target, options, path -> Files.writeString(path, "new"))));
			if (budget == Budget.SHORT) {
				assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(180));
			}
			assertEquals("old", Files.readString(target));
		}
		try (var files = Files.list(directory)) {
			assertEquals(List.of(source, target), files.sorted().toList());
		}
	}

	@ParameterizedTest
	@EnumSource(Operation.class)
	void disabledRetriesDoNotSleepOrConsumeAnInterrupt(Operation operation) throws IOException {
		Path source = Files.writeString(directory.resolve("source"), "new");
		Path target = Files.writeString(directory.resolve("target"), "old");
		try (var lock = FileChannel.open(source, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE)) {
			Thread.currentThread().interrupt();
			try {
				assertThrows(FileSystemException.class, () -> {
					switch (operation) {
						case MOVE -> FileIo.moveReplace(source, target, AtomicMovePolicy.REQUIRE_ATOMIC, Duration.ZERO);
						case DELETE -> FileIo.deleteIfExists(source, Duration.ZERO);
					}
				});
				assertTrue(Thread.currentThread().isInterrupted());
			} finally {
				Thread.interrupted();
			}
		}
		assertEquals("new", Files.readString(source));
		assertEquals("old", Files.readString(target));
	}

	@Test
	void copyUsesTheConfiguredBudgetWhenSourceReadingIsBlocked() throws IOException {
		Path source = Files.writeString(directory.resolve("source"), "new");
		Path target = Files.writeString(directory.resolve("target"), "old");
		try (var lock = FileChannel.open(source, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_READ)) {
			assertTimeout(Duration.ofSeconds(1), () -> assertThrows(FileSystemException.class, () ->
				AtomicFiles.copy(source, target, WriteOptions.DEFAULT.withRetryTimeout(Duration.ZERO))));
		}
		assertEquals("old", Files.readString(target));
		try (var files = Files.list(directory)) {
			assertEquals(List.of(source, target), files.sorted().toList());
		}
	}

	@Test
	void cleanupUsesTheConfiguredBudgetAndKeepsTheWritersException() throws IOException {
		Path target = Files.writeString(directory.resolve("target"), "old");
		var lock = new AtomicReference<FileChannel>();
		var staged = new AtomicReference<Path>();
		IOException failure = new IOException("serialization failed");
		try {
			IOException actual = assertTimeout(Duration.ofSeconds(1), () -> assertThrows(IOException.class, () ->
				AtomicFiles.write(target, path -> {
					staged.set(path);
					lock.set(FileChannel.open(path, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE));
					throw failure;
				}, WriteOptions.DEFAULT.withRetryTimeout(Duration.ZERO))));
			assertSame(failure, actual);
			assertInstanceOf(FileSystemException.class, actual.getSuppressed()[0]);
			assertEquals("old", Files.readString(target));
		} finally {
			if (lock.get() != null) {
				lock.get().close();
			}
			if (staged.get() != null) {
				Files.delete(staged.get());
			}
		}
	}
}
