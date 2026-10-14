package net.mezzdev.atomicfile;

import net.mezzdev.atomicfile.internal.FileIo;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * Prepared, flushed contents for later replacement.
 * {@link #commit()} publishes them; {@link #close()} discards them.
 * Coordinate access to this handle and its destination.
 */
public final class StagedFile implements AutoCloseable {
	private final Path path;
	private final Path target;
	private final WriteOptions options;
	private boolean closed;

	StagedFile(Path path, Path target, WriteOptions options) {
		this.path = path;
		this.target = target;
		this.options = options;
	}

	/**
	 * Returns the staging path, normally absent after commit or close. Do not modify it directly.
	 */
	public Path path() {
		return path;
	}

	/**
	 * Compares staged and destination bytes without changing either file.
	 *
	 * @return whether contents match; {@code false} if the destination is missing
	 * @throws IllegalStateException if already committed or closed
	 */
	public boolean isUnchanged() throws IOException {
		ensureOpen();
		try {
			return AtomicFiles.mismatch(path, target, options.retryTimeout()) == -1;
		} catch (NoSuchFileException missing) {
			return false;
		}
	}

	/**
	 * Replaces the destination using the selected move policy and consumes the staging file.
	 * Failed commits can be retried; non-atomic fallback may change the destination before failing.
	 *
	 * @throws IllegalStateException if already committed or closed
	 */
	public void commit() throws IOException {
		ensureOpen();
		FileIo.moveReplace(path, target, options.atomicMovePolicy(), options.retryTimeout());
		closed = true;
	}

	/**
	 * Discards uncommitted contents. Closing again or after a commit does nothing.
	 * Failed deletion can be retried.
	 */
	@Override
	public void close() throws IOException {
		if (!closed) {
			FileIo.deleteStagedFile(path, options.retryTimeout());
			closed = true;
		}
	}

	private void ensureOpen() {
		if (closed) {
			throw new IllegalStateException("Staged file has been committed or closed: " + path);
		}
	}
}
