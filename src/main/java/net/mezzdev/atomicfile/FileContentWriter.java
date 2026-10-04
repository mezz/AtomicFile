package net.mezzdev.atomicfile;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Writes new file contents to a temporary file.
 * Each write operation calls the writer at most once.
 */
@FunctionalInterface
public interface FileContentWriter {
	/**
	 * Fills an existing, empty temporary file in the destination directory.
	 * Close all streams and channels before returning. Do not move or delete the file.
	 * Do not replace it with a symbolic link. Do not use the supplied path after returning.
	 * If this method throws, the destination is unchanged.
	 * <p>
	 * To flush your channel's writes to storage, call {@link java.nio.channels.FileChannel#force(boolean)}
	 * before closing that channel.
	 *
	 * @param temporaryFile the file to write
	 * @throws IOException if writing fails
	 */
	void write(Path temporaryFile) throws IOException;
}
