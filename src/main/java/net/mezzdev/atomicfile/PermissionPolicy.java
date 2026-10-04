package net.mezzdev.atomicfile;

/**
 * Chooses permissions for the new destination file.
 * Preservation policies copy selected permissions from the existing destination.
 * If the destination is missing, the new file keeps the temporary file's permissions.
 * <p>
 * Preservation fails if the destination is a directory or symbolic link.
 * The filesystem must support the selected permissions. Ownership and other file attributes are not preserved.
 */
public enum PermissionPolicy {
	/**
	 * Uses the temporary file's permissions. These may differ from the old destination's permissions.
	 */
	USE_STAGING_PERMISSIONS,
	/**
	 * Preserves read, write, and execute permissions for the owner, group, and other users.
	 */
	PRESERVE_POSIX_PERMISSIONS,
	/**
	 * Preserves the destination's access-control list from {@link java.nio.file.attribute.AclFileAttributeView}.
	 * This list specifies permissions for users and groups.
	 */
	PRESERVE_ACL
}
