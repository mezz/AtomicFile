package net.mezzdev.atomicfile;

/**
 * Controls whether file replacement must be atomic.
 * Atomic replacement changes the destination in one step. Readers see the old file or the new file.
 */
public enum AtomicMovePolicy {
	/**
	 * Requires atomic replacement. The operation fails if the filesystem does not support it.
	 */
	REQUIRE_ATOMIC,

	/**
	 * Allows a non-atomic move when atomic moving or replacement is unsupported.
	 * The destination may be missing or partly written during this move. If the move fails, the old
	 * file may be lost. The non-atomic move is attempted once. File access errors do not trigger it.
	 * Recoverable writes do not allow this policy.
	 */
	ALLOW_NON_ATOMIC
}
