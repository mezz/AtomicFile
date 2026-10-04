package net.mezzdev.atomicfile;

import net.mezzdev.atomicfile.internal.FileIo;
import net.mezzdev.atomicfile.internal.FilePermissions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Updates files after preparing their complete new contents.
 * Writes and copies use a temporary file in the destination directory. Default replacement is atomic:
 * readers see the complete old file or the complete new file.
 * Use the overloads accepting {@link WriteOptions} to share customized settings across writes and copies.
 * <p>
 * These methods do not lock files. Concurrent writes can replace each other's results.
 */
public final class AtomicFiles {
	private AtomicFiles() {}

	/**
	 * Replaces the destination with the supplied bytes.
	 * Uses {@link WriteOptions#DEFAULT}, which requires atomic replacement.
	 *
	 * @param target the destination file
	 * @param bytes the new contents. Keep the array unchanged until this call returns.
	 * @throws IOException if writing or replacement fails
	 * @throws IllegalArgumentException if the target is a filesystem root
	 */
	public static void write(Path target, byte[] bytes) throws IOException {
		write(target, bytes, WriteOptions.DEFAULT);
	}

	/**
	 * Replaces the destination with the supplied bytes.
	 * The library writes a temporary file before replacing the destination. A failure before replacement
	 * leaves the destination unchanged. On failure, the library tries to delete the temporary file.
	 * {@link AtomicMovePolicy} defines the guarantees during replacement.
	 * <p>
	 * With the default permission policy, a symbolic link at the destination is replaced.
	 * The file referenced by that link is unchanged. Permission preservation rejects symbolic links.
	 *
	 * @param target the destination file
	 * @param bytes the new contents. Keep the array unchanged until this call returns.
	 * @param options the write settings
	 * @throws IOException if writing or replacement fails
	 * @throws IllegalArgumentException if the target is a filesystem root
	 */
	public static void write(Path target, byte[] bytes, WriteOptions options) throws IOException {
		Objects.requireNonNull(bytes, "bytes");
		writeForced(target, temporaryFile -> FileIo.write(temporaryFile, bytes, options.retryTimeout()), options);
	}

	/**
	 * Replaces the destination with contents supplied by the writer.
	 * Uses {@link WriteOptions#DEFAULT}, which requires atomic replacement.
	 * The writer receives an empty temporary file and must follow {@link FileContentWriter}.
	 *
	 * @param target the destination file
	 * @param writer the writer for the new contents
	 * @throws IOException if writing or replacement fails
	 * @throws IllegalArgumentException if the target is a filesystem root
	 */
	public static void write(Path target, FileContentWriter writer) throws IOException {
		write(target, writer, WriteOptions.DEFAULT);
	}

	/**
	 * Replaces the destination with contents supplied by the writer.
	 * The library calls the writer with an empty temporary file. If the writer throws, the destination
	 * is unchanged. {@link FileContentWriter} describes the writer's responsibilities.
	 * Replacement and cleanup follow {@link #write(Path, byte[], WriteOptions)}.
	 *
	 * @param target the destination file
	 * @param writer the writer for the new contents
	 * @param options the write settings
	 * @throws IOException if writing or replacement fails
	 * @throws IllegalArgumentException if the target is a filesystem root
	 */
	public static void write(Path target, FileContentWriter writer, WriteOptions options) throws IOException {
		Objects.requireNonNull(writer, "writer");
		writeForced(target, temporaryFile -> {
			writer.write(temporaryFile);
			FileIo.force(temporaryFile, options.retryTimeout());
		}, options);
	}

	private static void writeForced(Path target, FileContentWriter writer, WriteOptions options) throws IOException {
		Objects.requireNonNull(options, "options");
		Path absoluteTarget = absoluteTarget(target);
		Path parent = absoluteTarget.getParent();
		FilePermissions permissions = FilePermissions.capture(absoluteTarget, options.permissionPolicy(), options.retryTimeout());
		FileIo.prepareParent(parent, options);
		Path temporaryFile = FileIo.retry(parent, options.retryTimeout(), () -> Files.createTempFile(parent, ".atomic-file-", ".tmp"));
		try {
			writer.write(temporaryFile);
			permissions.apply(temporaryFile);
			FileIo.moveReplace(temporaryFile, absoluteTarget, options.atomicMovePolicy(), options.retryTimeout());
		} catch (IOException | RuntimeException | Error failure) {
			FileIo.cleanup(temporaryFile, failure, options.retryTimeout());
			throw failure;
		}
	}

	private static Path absoluteTarget(Path target) {
		Path absoluteTarget = target.toAbsolutePath();
		if (absoluteTarget.getParent() == null) {
			throw new IllegalArgumentException("Target must have a parent directory: " + target);
		}
		return absoluteTarget;
	}

	/**
	 * Replaces the destination with a copy of the source contents.
	 * The source is kept. Uses {@link WriteOptions#DEFAULT}, which requires atomic replacement.
	 *
	 * @param source the file to copy. Keep it unchanged until this call returns.
	 * @param target the destination file
	 * @throws IOException if copying or replacement fails
	 * @throws IllegalArgumentException if the target is a filesystem root
	 */
	public static void copy(Path source, Path target) throws IOException {
		copy(source, target, WriteOptions.DEFAULT);
	}

	/**
	 * Replaces the destination with a copy of the source contents.
	 * The source is kept and may be on another filesystem. Source symbolic links are followed.
	 * Only contents are copied. Source permissions and other attributes are not copied.
	 * Replacement and cleanup follow {@link #write(Path, byte[], WriteOptions)}.
	 *
	 * @param source the file to copy. Keep it unchanged until this call returns.
	 * @param target the destination file
	 * @param options the copy settings
	 * @throws IOException if copying or replacement fails
	 * @throws IllegalArgumentException if the target is a filesystem root
	 */
	public static void copy(Path source, Path target, WriteOptions options) throws IOException {
		Objects.requireNonNull(source, "source");
		writeForced(target, temporaryFile -> FileIo.copy(source, temporaryFile, options.retryTimeout()), options);
	}
}
