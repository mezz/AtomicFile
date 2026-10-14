package net.mezzdev.atomicfile.internal;

import net.mezzdev.atomicfile.PermissionPolicy;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Stores destination permissions for later application to a new file.
 * @hidden
 */
@FunctionalInterface
public interface FilePermissions {
	/** Allows deletion of DOS read-only staging files, restoring the flag if deletion fails. */
	static Closeable allowDelete(Path path) throws IOException {
		DosFileAttributeView dos = Files.getFileAttributeView(path, DosFileAttributeView.class);
		boolean readOnly;
		try {
			readOnly = dos != null && dos.readAttributes().isReadOnly();
		} catch (NoSuchFileException missing) {
			return () -> {};
		}
		if (readOnly) {
			dos.setReadOnly(false);
			return () -> {
				try {
					dos.setReadOnly(true);
				} catch (NoSuchFileException deleted) {
					// A successful deletion consumes the staging file.
				}
			};
		}
		return () -> {};
	}

	/** Flushes a copied file while retaining read-only POSIX or DOS attributes. */
	static void forceCopiedFile(Path path, Duration timeout) throws IOException {
		try (Closeable ignored = allowWrite(path)) {
			FileIo.force(path, timeout);
		}
	}

	private static Closeable allowWrite(Path path) throws IOException {
		PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
		if (posix != null) {
			Set<PosixFilePermission> original = Set.copyOf(posix.readAttributes().permissions());
			if (!original.contains(PosixFilePermission.OWNER_WRITE)) {
				Set<PosixFilePermission> writable = new HashSet<>(original);
				writable.add(PosixFilePermission.OWNER_WRITE);
				posix.setPermissions(writable);
				return () -> posix.setPermissions(original);
			}
		} else {
			DosFileAttributeView dos = Files.getFileAttributeView(path, DosFileAttributeView.class);
			if (dos != null && dos.readAttributes().isReadOnly()) {
				dos.setReadOnly(false);
				return () -> dos.setReadOnly(true);
			}
		}
		return () -> {};
	}

	/**
	 * Applies the stored permissions to a completed temporary file.
	 *
	 * @param staging the file that will replace the destination
	 * @throws IOException if applying permissions fails
	 */
	void apply(Path staging) throws IOException;

	/**
	 * Reads the selected destination permissions and returns an action that applies them to a new file.
	 * If the destination is missing, the action leaves permissions unchanged.
	 * {@link PermissionPolicy#USE_STAGING_PERMISSIONS} also leaves permissions unchanged.
	 *
	 * @param target the destination file
	 * @param policy the permissions to preserve
	 * @param timeout the retry timeout for each filesystem operation
	 * @return an action that applies the saved permissions
	 * @throws IOException if permission preservation fails
	 */
	static FilePermissions capture(Path target, PermissionPolicy policy, Duration timeout) throws IOException {
		if (policy == PermissionPolicy.USE_STAGING_PERMISSIONS) {
			return staging -> {};
		}
		BasicFileAttributes attributes;
		try {
			attributes = FileIo.retry(target, timeout, () -> Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
		} catch (NoSuchFileException missing) {
			return staging -> {};
		}
		if (!attributes.isRegularFile()) {
			throw new IOException("Cannot preserve permissions of a non-regular target: " + target);
		}
		if (policy == PermissionPolicy.PRESERVE_POSIX_PERMISSIONS) {
			var permissions = FileIo.retry(target, timeout, () -> Set.copyOf(view(target, PosixFileAttributeView.class).readAttributes().permissions()));
			return staging -> FileIo.retry(staging, timeout, () -> {
				view(staging, PosixFileAttributeView.class).setPermissions(permissions);
				return staging;
			});
		}
		var acl = FileIo.retry(target, timeout, () -> List.copyOf(view(target, AclFileAttributeView.class).getAcl()));
		return staging -> FileIo.retry(staging, timeout, () -> {
			view(staging, AclFileAttributeView.class).setAcl(acl);
			return staging;
		});
	}

	private static <V extends FileAttributeView> V view(Path path, Class<V> type) throws IOException {
		V view = Files.getFileAttributeView(path, type, LinkOption.NOFOLLOW_LINKS);
		if (view == null) {
			throw new IOException("Filesystem does not support " + type.getSimpleName() + ": " + path);
		}
		return view;
	}
}
