package net.mezzdev.atomicfile.integration;

import net.mezzdev.atomicfile.AtomicFiles;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicFilesConcurrencyTest {
	@TempDir Path directory;

	@Test
	void overwritesAFileCreatedWhileTheWriterWasSerializing() throws IOException {
		Path target = directory.resolve("settings");
		AtomicFiles.write(target, temporary -> {
			Files.writeString(temporary, "staged");
			Files.writeString(target, "created concurrently");
			assertEquals("created concurrently", Files.readString(target));
		});
		assertEquals("staged", Files.readString(target));
		try (var paths = Files.list(directory)) {
			assertEquals(List.of(target), paths.toList());
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@Timeout(20)
	void overlappingWritesHaveIndependentStagingAndLastSuccessfulReplacementWins(boolean firstFails) throws Exception {
		Path target = Files.writeString(directory.resolve("settings"), "old");
		CountDownLatch staged = new CountDownLatch(2);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		CountDownLatch releaseSecond = new CountDownLatch(1);
		var firstPath = new AtomicReference<Path>();
		var secondPath = new AtomicReference<Path>();
		var executor = Executors.newFixedThreadPool(2);
		try {
			var first = executor.submit(() -> {
				AtomicFiles.write(target, path -> {
					firstPath.set(path);
					Files.writeString(path, "first");
					staged.countDown();
					await(releaseFirst);
					if (firstFails) {
						throw new IOException("first writer failed");
					}
				});
				return null;
			});
			var second = executor.submit(() -> {
				AtomicFiles.write(target, path -> {
					secondPath.set(path);
					Files.writeString(path, "second");
					staged.countDown();
					await(releaseSecond);
				});
				return null;
			});
			assertTrue(staged.await(5, TimeUnit.SECONDS));
			assertNotEquals(firstPath.get(), secondPath.get());
			assertEquals("old", Files.readString(target));
			releaseSecond.countDown();
			second.get(5, TimeUnit.SECONDS);
			assertEquals("second", Files.readString(target));
			assertEquals("first", Files.readString(firstPath.get()));
			releaseFirst.countDown();
			if (firstFails) {
				ExecutionException failure = assertThrows(ExecutionException.class, () -> first.get(5, TimeUnit.SECONDS));
				assertEquals("first writer failed", failure.getCause().getMessage());
			} else {
				first.get(5, TimeUnit.SECONDS);
			}
			assertEquals(firstFails ? "second" : "first", Files.readString(target));
			try (var paths = Files.list(directory)) {
				assertEquals(List.of(target), paths.toList());
			}
		} finally {
			releaseFirst.countDown();
			releaseSecond.countDown();
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	private static void await(CountDownLatch latch) throws IOException {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) {
				throw new IOException("timed out waiting for test writer");
			}
		} catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
			throw new IOException(failure);
		}
	}
}
