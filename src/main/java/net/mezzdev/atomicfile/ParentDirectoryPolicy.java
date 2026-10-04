package net.mezzdev.atomicfile;

/**
 * Controls whether writes and copies may create missing parent directories.
 */
public enum ParentDirectoryPolicy {
	/**
	 * Creates any missing parent directories before writing.
	 */
	CREATE_IF_MISSING,
	/**
	 * Requires the destination's parent directory to exist.
	 * A missing directory causes failure before the writer is called.
	 */
	REQUIRE_EXISTING
}
