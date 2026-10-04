package net.mezzdev.atomicfile;

import net.mezzdev.atomicfile.internal.FileIo;

import java.time.Duration;
import java.util.Objects;

/**
 * Configures file writes and copies.
 * Start with {@link #DEFAULT}. Each {@code with...} method returns a copy with one setting changed.
 * The original options remain unchanged. Options can be shared across files and threads.
 * <p>
 * Retries apply only to file access errors on the default Windows filesystem.
 * The retry timeout applies separately to each filesystem operation. A complete write can exceed this timeout.
 * The timeout cannot stop a running operating system call.
 *
 * @param atomicMovePolicy the replacement policy
 * @param parentDirectoryPolicy whether to create missing parent directories
 * @param permissionPolicy the permissions to use for the new destination file
 * @param retryTimeout the retry timeout for each filesystem operation. {@link Duration#ZERO} disables retries.
 */
public record WriteOptions(
	AtomicMovePolicy atomicMovePolicy,
	ParentDirectoryPolicy parentDirectoryPolicy,
	PermissionPolicy permissionPolicy,
	Duration retryTimeout
) {
	/**
	 * Requires atomic replacement, creates missing parent directories, and uses temporary file permissions.
	 * The retry timeout is 2 seconds for each filesystem operation.
	 */
	public static final WriteOptions DEFAULT = new WriteOptions(
		AtomicMovePolicy.REQUIRE_ATOMIC,
		ParentDirectoryPolicy.CREATE_IF_MISSING,
		PermissionPolicy.USE_STAGING_PERMISSIONS,
		Duration.ofSeconds(2)
	);

	/**
	 * Creates options with the supplied settings.
	 *
	 * @param atomicMovePolicy the replacement policy
	 * @param parentDirectoryPolicy whether to create missing parent directories
	 * @param permissionPolicy the permissions to use for the new destination file
	 * @param retryTimeout the retry timeout for each filesystem operation. Zero disables retries.
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if the timeout is negative or too large to express in nanoseconds
	 */
	public WriteOptions {
		Objects.requireNonNull(atomicMovePolicy, "atomicMovePolicy");
		Objects.requireNonNull(parentDirectoryPolicy, "parentDirectoryPolicy");
		Objects.requireNonNull(permissionPolicy, "permissionPolicy");
		FileIo.retryNanos(retryTimeout);
	}

	/**
	 * Returns a copy with the given replacement policy.
	 *
	 * @param policy the replacement policy
	 * @return new options with the given policy
	 * @throws NullPointerException if the policy is null
	 */
	public WriteOptions withAtomicMovePolicy(AtomicMovePolicy policy) {
		return new WriteOptions(policy, parentDirectoryPolicy, permissionPolicy, retryTimeout);
	}

	/**
	 * Returns a copy with the given parent directory policy.
	 *
	 * @param policy the parent directory policy
	 * @return new options with the given policy
	 * @throws NullPointerException if the policy is null
	 */
	public WriteOptions withParentDirectoryPolicy(ParentDirectoryPolicy policy) {
		return new WriteOptions(atomicMovePolicy, policy, permissionPolicy, retryTimeout);
	}

	/**
	 * Returns a copy with the given permission policy.
	 *
	 * @param policy the permission policy
	 * @return new options with the given policy
	 * @throws NullPointerException if the policy is null
	 */
	public WriteOptions withPermissionPolicy(PermissionPolicy policy) {
		return new WriteOptions(atomicMovePolicy, parentDirectoryPolicy, policy, retryTimeout);
	}

	/**
	 * Returns a copy with the given retry timeout.
	 *
	 * @param timeout the retry timeout for each filesystem operation. {@link Duration#ZERO} disables retries.
	 * @return new options with the given timeout
	 * @throws NullPointerException if the timeout is null
	 * @throws IllegalArgumentException if the timeout is negative or too large to express in nanoseconds
	 */
	public WriteOptions withRetryTimeout(Duration timeout) {
		return new WriteOptions(atomicMovePolicy, parentDirectoryPolicy, permissionPolicy, timeout);
	}
}
