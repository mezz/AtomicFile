package net.mezzdev.atomicfile.internal;

import net.mezzdev.atomicfile.PermissionPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Stores destination permissions for later application to a new file.
 * @hidden
 */
@FunctionalInterface
public interface FilePermissions {
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
