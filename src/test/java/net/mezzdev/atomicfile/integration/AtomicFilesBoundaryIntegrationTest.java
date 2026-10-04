package net.mezzdev.atomicfile.integration;

import net.mezzdev.atomicfile.AtomicFiles;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Verifies API boundary behavior on real storage, including rejected targets and callbacks. */
class AtomicFilesBoundaryIntegrationTest {
	enum TargetOperation { BYTES, CALLBACK, COPY }

	@TempDir Path directory;

	@ParameterizedTest
	@EnumSource(TargetOperation.class)
	void rejectsFilesystemRootsWithoutChangingTheirContents(TargetOperation operation) throws IOException {
		// Use an isolated archive so a regression cannot write into the machine's root directory.
		try (var archive = FileSystems.newFileSystem(directory.resolve("root.zip"), Map.of("create", "true"))) {
			Path root = archive.getPath("/");
			Path source = Files.writeString(root.resolve("source"), "keep");
			assertThrows(IllegalArgumentException.class, () -> operate(operation, root, source));
			assertEquals("keep", Files.readString(source));
			assertEquals(List.of(source), children(root));
		}
	}

	@ParameterizedTest
	@EnumSource(TargetOperation.class)
	void aFileInTheParentPathIsAnErrorAndIsNeverOverwritten(TargetOperation operation) throws IOException {
		Path parent = Files.writeString(directory.resolve("parent"), "keep parent");
		Path source = Files.writeString(directory.resolve("source"), "keep source");
		assertThrows(IOException.class, () -> operate(operation, parent.resolve("nested/target"), source));
		assertEquals("keep parent", Files.readString(parent));
		assertEquals("keep source", Files.readString(source));
		assertEquals(List.of(parent, source), children(directory));
	}

	@Test
	void aDeletedStagingFileDoesNotReplaceTheTarget() throws IOException {
		Path target = Files.writeString(directory.resolve("target"), "old");
		assertThrows(IOException.class, () -> AtomicFiles.write(target, Files::delete));
		assertEquals("old", Files.readString(target));
		assertEquals(List.of(target), children(directory));
	}

	private static void operate(TargetOperation operation, Path target, Path source) throws IOException {
		byte[] bytes = "new".getBytes(StandardCharsets.UTF_8);
		switch (operation) {
			case BYTES -> AtomicFiles.write(target, bytes);
			case CALLBACK -> AtomicFiles.write(target, path -> Files.write(path, bytes));
			case COPY -> AtomicFiles.copy(source, target);
		}
	}

	private static List<Path> children(Path parent) throws IOException {
		try (var paths = Files.list(parent)) {
			return paths.sorted().toList();
		}
	}
}
