package net.mezzdev.atomicfile.internal;

import net.mezzdev.atomicfile.AtomicMovePolicy;
import net.mezzdev.atomicfile.ParentDirectoryPolicy;
import net.mezzdev.atomicfile.WriteOptions;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Performs filesystem operations for staged writes and replacement.
 * @hidden
 */
public final class FileIo {
	private static final long INITIAL_RETRY_DELAY_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
	private static final long MAX_RETRY_DELAY_NANOS = TimeUnit.MILLISECONDS.toNanos(800);

	private FileIo() {}

	/**
	 * Replaces the destination by moving the source.
	 * Attempts an atomic move first. The policy may permit a regular move if atomic replacement is unsupported.
	 * The caller must create parent directories and flush source contents beforehand if needed.
	 *
	 * @param source the file to move
	 * @param target the destination file
	 * @param policy the replacement policy
	 * @param retryTimeout the retry timeout for atomic replacement
	 * @throws IOException if replacement fails
	 */
	public static void moveReplace(Path source, Path target, AtomicMovePolicy policy, Duration retryTimeout) throws IOException {
		Objects.requireNonNull(policy, "policy");
		try {
			retry(source, retryTimeout, () -> Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
		} catch (AtomicMoveNotSupportedException | FileAlreadyExistsException unsupported) {
			if (policy == AtomicMovePolicy.REQUIRE_ATOMIC) {
				throw unsupported;
			}
			try {
				Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException failure) {
				failure.addSuppressed(unsupported);
				throw failure;
			}
		}
		Path sourceParent = source.toAbsolutePath().getParent();
		Path targetParent = target.toAbsolutePath().getParent();
		if (targetParent != null) {
			forceDirectory(targetParent);
		}
		if (sourceParent != null && !sourceParent.equals(targetParent)) {
			forceDirectory(sourceParent);
		}
	}

	/**
	 * Deletes a file or empty directory if it exists.
	 *
	 * @param path the entry to delete. Symbolic links are deleted without following them.
	 * @param retryTimeout the retry timeout for deletion
	 * @return {@code true} if deleted, or {@code false} if the path did not exist
	 * @throws IOException if deletion fails
	 */
	public static boolean deleteIfExists(Path path, Duration retryTimeout) throws IOException {
		return retry(path, retryTimeout, () -> Files.deleteIfExists(path));
	}

	/** Deletes staging files even when copied attributes mark them read-only. */
	public static boolean deleteStagedFile(Path path, Duration retryTimeout) throws IOException {
		return retry(path, retryTimeout, () -> {
			try (var ignored = FilePermissions.allowDelete(path)) {
				return Files.deleteIfExists(path);
			} catch (NoSuchFileException missing) {
				return false;
			}
		});
	}

	/**
	 * Writes bytes to an existing temporary file and flushes them to storage.
	 * Uses the default retry timeout.
	 *
	 * @param temporaryFile the writable temporary file
	 * @param bytes the new contents. Keep the array unchanged until this call returns.
	 * @throws IOException if writing or flushing fails
	 */
	public static void write(Path temporaryFile, byte[] bytes) throws IOException {
		write(temporaryFile, bytes, WriteOptions.DEFAULT.retryTimeout());
	}

	/**
	 * Writes bytes to an existing temporary file and flushes them through the writing channel.
	 * Each retry rewrites the file from the beginning. No other operation may use the temporary file during this call.
	 *
	 * @param temporaryFile the writable temporary file
	 * @param bytes the new contents. Keep the array unchanged until this call returns.
	 * @param timeout the retry timeout for the write operation
	 * @throws IOException if writing, flushing, or closing the file fails
	 */
	public static void write(Path temporaryFile, byte[] bytes, Duration timeout) throws IOException {
		// A scanner can deny write sharing after createTempFile closes its handle.
		retry(temporaryFile, timeout, () -> {
			try (FileChannel channel = FileChannel.open(temporaryFile, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
				ByteBuffer buffer = ByteBuffer.wrap(bytes);
				while (buffer.hasRemaining()) {
					channel.write(buffer);
				}
				channel.force(true);
			}
			return temporaryFile;
		});
	}

	/**
	 * Copies source contents to an existing temporary file.
	 * Flushes the contents through the writing channel. Each retry restarts the copy.
	 * Only contents are copied. The temporary file keeps its permissions.
	 *
	 * @param source the file to read. Keep it unchanged until this call returns.
	 * @param temporaryFile the writable temporary file. Do not use it concurrently.
	 * @param timeout the retry timeout for the copy operation
	 * @throws IOException if reading, writing, flushing, or closing a file fails
	 */
	public static void copy(Path source, Path temporaryFile, Duration timeout) throws IOException {
		copy(source, temporaryFile, timeout, false);
	}

	/** Copies and flushes contents, optionally preserving filesystem-supported source attributes. */
	public static void copy(Path source, Path temporaryFile, Duration timeout, boolean copyAttributes) throws IOException {
		if (copyAttributes) {
			retry(temporaryFile, timeout, () -> {
				try (var ignored = FilePermissions.allowDelete(temporaryFile)) {
					Files.copy(source, temporaryFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
					FilePermissions.forceCopiedFile(temporaryFile, timeout);
				}
				return temporaryFile;
			});
			return;
		}
		retry(temporaryFile, timeout, () -> {
			// Keep the staging file's private permissions and force the channel that wrote it.
			try (var input = Files.newInputStream(source);
				 var channel = FileChannel.open(temporaryFile, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
				input.transferTo(Channels.newOutputStream(channel));
				channel.force(true);
			}
			return temporaryFile;
		});
	}

	/**
	 * Flushes an existing file to storage through a new channel.
	 * The filesystem determines whether writes from other channels are also flushed.
	 *
	 * @param path the file to flush
	 * @param timeout the retry timeout for the flush operation
	 * @throws IOException if opening, flushing, or closing the file fails
	 */
	public static void force(Path path, Duration timeout) throws IOException {
		retry(path, timeout, () -> {
			try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
				channel.force(true);
			}
			return path;
		});
	}

	/**
	 * Creates missing parent directories if the options permit it.
	 *
	 * @param directory the destination's parent directory
	 * @param options the directory creation and retry settings
	 * @throws IOException if directory creation fails
	 */
	public static void prepareParent(Path directory, WriteOptions options) throws IOException {
		if (options.parentDirectoryPolicy() == ParentDirectoryPolicy.REQUIRE_EXISTING) {
			// createTempFile will check existence and directory access without creating parents.
			return;
		}
		var missing = new ArrayList<Path>();
		for (Path path = directory; path != null && Files.notExists(path); path = path.getParent()) {
			missing.add(path);
		}
		retry(directory, options.retryTimeout(), () -> Files.createDirectories(directory));
		// A new directory's name must also be persisted in its parent.
		for (Path path : missing) {
			Path parent = path.getParent();
			if (parent != null) {
				forceDirectory(parent);
			}
		}
	}

	/**
	 * Tries to flush directory changes to storage. Failures are ignored.
	 */
	private static void forceDirectory(Path directory) {
		if (directory.getFileSystem() != FileSystems.getDefault() || FileSystems.getDefault().getSeparator().equals("\\")) {
			return;
		}
		try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
			channel.force(true);
		} catch (IOException | UnsupportedOperationException | SecurityException ignored) {
			// Directory channels/fsync are unsupported on some filesystems. File forcing still fails loudly.
		}
	}

	/**
	 * Tries to delete a temporary file after an earlier failure.
	 * Deletion errors are added to the original failure as {@linkplain Throwable#getSuppressed() suppressed exceptions}.
	 *
	 * @param path the temporary file to delete
	 * @param failure the original failure
	 * @param timeout the retry timeout for deletion
	 */
	public static void cleanup(Path path, Throwable failure, Duration timeout) {
		try {
			deleteStagedFile(path, timeout);
		} catch (IOException | RuntimeException | Error cleanupFailure) {
			failure.addSuppressed(cleanupFailure);
		}
	}

	/**
	 * Retries an I/O operation after certain Windows file access errors.
	 * Retries apply only to the default Windows filesystem. The operation must be safe to repeat.
	 * Do not pass a supplied writer or a non-atomic replacement to this method.
	 * The timeout includes attempts and waits. It cannot stop a running operating system call.
	 * Interrupted waits preserve the thread's interrupted status.
	 *
	 * @param <T> the result type
	 * @param path the path that determines which filesystem retry rules apply
	 * @param timeout the retry timeout. Zero disables retries.
	 * @param operation the operation to attempt
	 * @return the successful operation's result
	 * @throws IOException if no attempt succeeds
	 * @throws InterruptedIOException if the thread is interrupted while waiting
	 * @throws IllegalArgumentException if the timeout is negative or too large to express in nanoseconds
	 */
	public static <T> T retry(Path path, Duration timeout, IoOperation<T> operation) throws IOException {
		long budget = retryNanos(timeout);
		long started = System.nanoTime();
		long delay = INITIAL_RETRY_DELAY_NANOS;
		while (true) {
			try {
				return operation.run();
			} catch (FileSystemException failure) {
				long remaining = budget - (System.nanoTime() - started);
				if (remaining <= 0 || !isRetryableFailure(path, failure)) {
					throw failure;
				}
				try {
					TimeUnit.NANOSECONDS.sleep(Math.min(delay, remaining));
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					InterruptedIOException aborted = new InterruptedIOException("Interrupted while accessing " + path);
					aborted.initCause(interrupted);
					aborted.addSuppressed(failure);
					throw aborted;
				}
				delay = Math.min(delay * 2, MAX_RETRY_DELAY_NANOS);
			}
		}
	}

	/**
	 * Converts a valid retry timeout to nanoseconds.
	 *
	 * @param timeout the timeout to check
	 * @return the timeout in nanoseconds
	 * @throws NullPointerException if the timeout is null
	 * @throws IllegalArgumentException if the timeout is negative or too large to express in nanoseconds
	 */
	public static long retryNanos(Duration timeout) {
		Objects.requireNonNull(timeout, "retryTimeout");
		if (timeout.isNegative()) {
			throw new IllegalArgumentException("Retry timeout must not be negative");
		}
		try {
			return timeout.toNanos();
		} catch (ArithmeticException tooLarge) {
			throw new IllegalArgumentException("Retry timeout must fit in nanoseconds", tooLarge);
		}
	}

	private static boolean isRetryableFailure(Path path, FileSystemException failure) {
		// OpenJDK maps Windows access denial to AccessDeniedException and sharing violations
		// to plain FileSystemException. Native error codes are unavailable and reasons are localized.
		// These types can also indicate permanent failures, so retries are bounded.
		return FileSystems.getDefault().getSeparator().equals("\\") &&
			path.getFileSystem() == FileSystems.getDefault() &&
			(failure instanceof AccessDeniedException || failure.getClass() == FileSystemException.class);
	}

	/**
	 * An I/O operation that is safe to repeat after a failed attempt.
	 *
	 * @param <T> the result type
	 */
	@FunctionalInterface
	public interface IoOperation<T> {
		/**
		 * Performs one attempt of the operation.
		 *
		 * @return the operation's result
		 * @throws IOException if the attempt fails
		 */
		T run() throws IOException;
	}
}
