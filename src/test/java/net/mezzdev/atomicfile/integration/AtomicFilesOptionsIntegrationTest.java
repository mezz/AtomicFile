package net.mezzdev.atomicfile.integration;

import net.mezzdev.atomicfile.AtomicFiles;
import net.mezzdev.atomicfile.AtomicMovePolicy;
import net.mezzdev.atomicfile.FileContentWriter;
import net.mezzdev.atomicfile.ParentDirectoryPolicy;
import net.mezzdev.atomicfile.PermissionPolicy;
import net.mezzdev.atomicfile.WriteOptions;

import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicFilesOptionsIntegrationTest {
	enum Access { READ_ONLY, SHARED_EXECUTABLE }
	enum InvalidTimeout { NEGATIVE, OVERFLOW }
	@TempDir Path directory;

	@ParameterizedTest
	@EnumSource(WriteMode.class)
	void requiringParentsFailsWithoutCreatingDirectoriesOrCallingTheWriter(WriteMode mode) throws IOException {
		Path source = Files.writeString(directory.resolve("source"), "new");
		Path target = directory.resolve("missing/nested/target");
		WriteOptions options = WriteOptions.DEFAULT.withParentDirectoryPolicy(ParentDirectoryPolicy.REQUIRE_EXISTING);
		AtomicBoolean called = new AtomicBoolean();
		assertThrows(IOException.class, () -> write(mode, source, target, options, path -> {
			called.set(true);
			Files.writeString(path, "new");
		}));
		assertFalse(called.get());
		assertEquals(List.of(source), children(directory));
		assertEquals("new", Files.readString(source));

		Files.createDirectories(target.getParent());
		write(mode, source, target, options, path -> Files.writeString(path, "new"));
		assertEquals("new", Files.readString(target));
		assertEquals(List.of(target), children(target.getParent()));
	}

	@ParameterizedTest
	@EnumSource(WriteMode.class)
	void explicitDirectoryCreationWorksWithPermissionPreservationForANewTarget(WriteMode mode) throws IOException {
		Path source = Files.writeString(directory.resolve("source"), "new");
		Path target = directory.resolve("created/nested/target");
		WriteOptions options = WriteOptions.DEFAULT
			.withParentDirectoryPolicy(ParentDirectoryPolicy.CREATE_IF_MISSING)
			.withPermissionPolicy(PermissionPolicy.PRESERVE_POSIX_PERMISSIONS)
			.withRetryTimeout(Duration.ZERO);
		// There is no existing target to preserve, including on providers without POSIX attributes.
		write(mode, source, target, options, path -> Files.writeString(path, "new"));
		assertEquals("new", Files.readString(target));
		assertEquals(List.of(target), children(target.getParent()));
	}

	static Stream<Arguments> posixWrites() {
		return Arrays.stream(WriteMode.values()).flatMap(mode -> Arrays.stream(Access.values()).map(access -> Arguments.of(mode, access)));
	}

	@ParameterizedTest
	@MethodSource("posixWrites")
	@EnabledOnOs({OS.LINUX, OS.MAC})
	void preservesDestinationModeOnlyAfterStagingCompletes(WriteMode mode, Access access) throws IOException {
		Path source = Files.writeString(directory.resolve("source"), "new");
		Path target = Files.writeString(directory.resolve("target"), "old");
		var permissions = PosixFilePermissions.fromString(access == Access.READ_ONLY ? "r--r-----" : "rwxr-x---");
		Files.setPosixFilePermissions(target, permissions);
		Files.setPosixFilePermissions(source, PosixFilePermissions.fromString("rw-------"));
		WriteOptions options = WriteOptions.DEFAULT.withPermissionPolicy(PermissionPolicy.PRESERVE_POSIX_PERMISSIONS);
		write(mode, source, target, options, path -> {
			assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(path));
			assertEquals("old", Files.readString(target));
			Files.writeString(path, "new");
		});
		assertEquals("new", Files.readString(target));
		assertEquals(permissions, Files.getPosixFilePermissions(target));
		assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(source));
		assertEquals(List.of(source, target), children(directory));
	}

	@ParameterizedTest
	@EnumSource(WriteMode.class)
	@EnabledOnOs({OS.LINUX, OS.MAC})
	void permissionUpdateFailurePreservesTheTargetAndCleansUp(WriteMode mode) throws IOException {
		Path source = Files.writeString(directory.resolve("source"), "new");
		Path target = Files.writeString(directory.resolve("target"), "old");
		var permissions = Files.getPosixFilePermissions(target);
		try (var fs = new FaultyFileSystem(target)) {
			IOException failure = fs.fail(FaultyFileSystem.Operation.SET_PERMISSIONS);
			assertSame(failure, assertThrows(IOException.class, () -> write(mode, fs.wrap(source), fs.wrap(target),
				WriteOptions.DEFAULT.withPermissionPolicy(PermissionPolicy.PRESERVE_POSIX_PERMISSIONS), path -> Files.writeString(path, "new"))));
		}
		assertEquals("old", Files.readString(target));
		assertEquals(permissions, Files.getPosixFilePermissions(target));
		assertEquals(List.of(source, target), children(directory));
	}

	static Stream<Arguments> unsupportedPermissions() {
		return Arrays.stream(WriteMode.values()).flatMap(mode -> Stream.of(PermissionPolicy.PRESERVE_POSIX_PERMISSIONS, PermissionPolicy.PRESERVE_ACL)
			.map(policy -> Arguments.of(mode, policy)));
	}

	@ParameterizedTest
	@MethodSource("unsupportedPermissions")
	void unsupportedPermissionPreservationFailsBeforeStaging(WriteMode mode, PermissionPolicy policy) throws IOException {
		try (var archive = FileSystems.newFileSystem(directory.resolve("attributes.zip"), Map.of("create", "true"))) {
			Path source = Files.writeString(archive.getPath("/source"), "new");
			Path target = Files.writeString(archive.getPath("/target"), "old");
			AtomicBoolean called = new AtomicBoolean();
			WriteOptions options = WriteOptions.DEFAULT.withPermissionPolicy(policy);
			assertThrows(IOException.class, () -> write(mode, source, target, options, path -> called.set(true)));
			assertFalse(called.get());
			assertEquals("old", Files.readString(target));
			assertEquals(List.of(source, target), children(target.getParent()));
		}
	}

	@ParameterizedTest
	@EnumSource(value = WriteMode.class, names = {"BYTES", "CALLBACK", "COPY"})
	void explicitReplacementOptionsWorkOnZipFilesystem(WriteMode mode) throws IOException {
		try (var archive = FileSystems.newFileSystem(directory.resolve("fallback.zip"), Map.of("create", "true"))) {
			Path source = Files.writeString(archive.getPath("/source"), "new");
			Path target = Files.writeString(archive.getPath("/target"), "old");
			WriteOptions options = WriteOptions.DEFAULT.withAtomicMovePolicy(AtomicMovePolicy.ALLOW_NON_ATOMIC);
			write(mode, source, target, options, path -> Files.writeString(path, "new"));
			assertEquals("new", Files.readString(target));
			assertEquals("new", Files.readString(source));
			assertEquals(List.of(source, target), children(target.getParent()));
		}
	}

	@ParameterizedTest
	@EnumSource(value = PermissionPolicy.class, names = {"PRESERVE_POSIX_PERMISSIONS", "PRESERVE_ACL"})
	@EnabledOnOs({OS.LINUX, OS.MAC})
	void permissionPreservationNeverFollowsATargetSymlink(PermissionPolicy policy) throws IOException {
		Path original = Files.writeString(directory.resolve("original"), "keep");
		Path target = Files.createSymbolicLink(directory.resolve("target"), original);
		assertThrows(IOException.class, () -> AtomicFiles.write(target, path -> {
			throw new AssertionError("Writer must not run for a symlink target");
		}, WriteOptions.DEFAULT.withPermissionPolicy(policy)));
		assertTrue(Files.isSymbolicLink(target));
		assertEquals("keep", Files.readString(original));
		assertEquals(List.of(original, target), children(directory));
	}

	@ParameterizedTest
	@EnumSource(InvalidTimeout.class)
	void invalidTimeoutsCannotModifyFiles(InvalidTimeout invalid) throws IOException {
		Duration timeout = invalid == InvalidTimeout.NEGATIVE ? Duration.ofNanos(-1) : Duration.ofSeconds(Long.MAX_VALUE);
		Path source = Files.writeString(directory.resolve("source"), "new");
		Path target = Files.writeString(directory.resolve("target"), "old");
		assertThrows(IllegalArgumentException.class, () -> AtomicFiles.write(target, "new".getBytes(StandardCharsets.UTF_8), WriteOptions.DEFAULT.withRetryTimeout(timeout)));
		assertEquals("old", Files.readString(target));
		assertEquals("new", Files.readString(source));
		assertEquals(List.of(source, target), children(directory));
	}

	@ParameterizedTest
	@EnumSource(WriteMode.class)
	@EnabledOnOs(OS.WINDOWS)
	void preservesDestinationAclEntries(WriteMode mode) throws IOException {
		Path source = Files.writeString(directory.resolve("source"), "new");
		Path target = Files.writeString(directory.resolve("target"), "old");
		AclFileAttributeView view = Files.getFileAttributeView(target, AclFileAttributeView.class);
		var acl = new ArrayList<>(view.getAcl());
		acl.add(0, AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(view.getOwner())
			.setPermissions(AclEntryPermission.EXECUTE).build());
		view.setAcl(acl);
		var expected = view.getAcl();
		write(mode, source, target, WriteOptions.DEFAULT.withPermissionPolicy(PermissionPolicy.PRESERVE_ACL), path -> Files.writeString(path, "new"));
		assertEquals(expected, Files.getFileAttributeView(target, AclFileAttributeView.class).getAcl());
		assertEquals("new", Files.readString(target));
		assertEquals(List.of(source, target), children(directory));
	}

	static void write(WriteMode mode, Path source, Path target, WriteOptions options, FileContentWriter writer) throws IOException {
		switch (mode) {
			case BYTES -> AtomicFiles.write(target, "new".getBytes(StandardCharsets.UTF_8), options);
			case CALLBACK -> AtomicFiles.write(target, writer, options);
			case COPY -> AtomicFiles.copy(source, target, options);
		}
	}

	private static List<Path> children(Path parent) throws IOException {
		try (var files = Files.list(parent)) {
			return files.sorted().toList();
		}
	}
}
