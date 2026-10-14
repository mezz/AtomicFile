package net.mezzdev.atomicfile;

import net.mezzdev.atomicfile.internal.FileIo;
import net.mezzdev.atomicfile.internal.FilePermissions;

import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
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
		try (StagedFile staged = stageForced(target, writer, options)) {
			staged.commit();
		}
	}

	private static StagedFile stageForced(Path target, FileContentWriter writer, WriteOptions options) throws IOException {
		Objects.requireNonNull(options, "options");
		Path absoluteTarget = absoluteTarget(target);
		Path parent = absoluteTarget.getParent();
		FilePermissions permissions = FilePermissions.capture(absoluteTarget, options.permissionPolicy(), options.retryTimeout());
		FileIo.prepareParent(parent, options);
		Path temporaryFile = FileIo.retry(parent, options.retryTimeout(), () -> Files.createTempFile(parent, ".atomic-file-", ".tmp"));
		try {
			writer.write(temporaryFile);
			permissions.apply(temporaryFile);
			return new StagedFile(temporaryFile, absoluteTarget, options);
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

	/**
	 * Stages bytes with default options.
	 * @see #stage(Path, byte[], WriteOptions)
	 */
	public static StagedFile stage(Path target, byte[] bytes) throws IOException {
		return stage(target, bytes, WriteOptions.DEFAULT);
	}

	/**
	 * Stages flushed bytes without changing the destination.
	 * Permission policies capture destination permissions during staging.
	 *
	 * @param bytes keep unchanged until this call returns
	 * @throws IllegalArgumentException if the target is a filesystem root
	 */
	public static StagedFile stage(Path target, byte[] bytes, WriteOptions options) throws IOException {
		Objects.requireNonNull(bytes, "bytes");
		return stageForced(target, temporaryFile -> FileIo.write(temporaryFile, bytes, options.retryTimeout()), options);
	}

	/**
	 * Stages a copy with default options.
	 * @see #stageCopy(Path, Path, WriteOptions, CopyOption[])
	 */
	public static StagedFile stageCopy(Path source, Path target) throws IOException {
		return stageCopy(source, target, WriteOptions.DEFAULT);
	}

	/**
	 * Stages a flushed copy without changing either file.
	 * Only contents are copied by default; destination permission policies override copied permissions.
	 *
	 * @param source keep unchanged until this call returns
	 * @param copyOptions optionally {@code COPY_ATTRIBUTES}; other options are rejected
	 */
	public static StagedFile stageCopy(Path source, Path target, WriteOptions options, CopyOption... copyOptions) throws IOException {
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(copyOptions, "copyOptions");
		for (CopyOption option : copyOptions) {
			Objects.requireNonNull(option, "copyOption");
			if (option != StandardCopyOption.COPY_ATTRIBUTES) {
				throw new IllegalArgumentException("Unsupported staging copy option: " + option);
			}
		}
		boolean copyAttributes = copyOptions.length != 0;
		return stageForced(target, temporaryFile -> FileIo.copy(source, temporaryFile, options.retryTimeout(), copyAttributes), options);
	}

	/**
	 * Snapshots a file with default options.
	 * @see #snapshot(Path, WriteOptions)
	 */
	public static StagedFile snapshot(Path target) throws IOException {
		return snapshot(target, WriteOptions.DEFAULT);
	}

	/**
	 * Snapshots a file and its supported attributes for restoration at the same path.
	 * Symbolic links are followed; restoration replaces the link with a regular file.
	 *
	 * @param target keep unchanged until this call returns
	 */
	public static StagedFile snapshot(Path target, WriteOptions options) throws IOException {
		return stageCopy(target, target, options, StandardCopyOption.COPY_ATTRIBUTES);
	}

	/**
	 * Moves a file over the destination atomically, using the default retry timeout.
	 * @see #moveReplace(Path, Path, AtomicMovePolicy, Duration)
	 */
	public static void moveReplace(Path source, Path target) throws IOException {
		moveReplace(source, target, WriteOptions.DEFAULT.atomicMovePolicy(), WriteOptions.DEFAULT.retryTimeout());
	}

	/**
	 * Moves a file over the destination, preserving source attributes.
	 * Prepare the destination directory first; flush the source if durability is needed.
	 *
	 * @param retryTimeout timeout per operation; zero disables retries
	 */
	public static void moveReplace(Path source, Path target, AtomicMovePolicy policy, Duration retryTimeout) throws IOException {
		FileIo.moveReplace(Objects.requireNonNull(source, "source"), Objects.requireNonNull(target, "target"), policy, retryTimeout);
	}

	/**
	 * Deletes an entry if present, using the default retry timeout.
	 * @see #deleteIfExists(Path, Duration)
	 */
	public static boolean deleteIfExists(Path path) throws IOException {
		return deleteIfExists(path, WriteOptions.DEFAULT.retryTimeout());
	}

	/**
	 * Deletes a file or empty directory with Windows access retries, without following symbolic links.
	 *
	 * @param retryTimeout timeout per operation; zero disables retries
	 * @return {@code false} if already absent
	 */
	public static boolean deleteIfExists(Path path, Duration retryTimeout) throws IOException {
		return FileIo.deleteIfExists(Objects.requireNonNull(path, "path"), retryTimeout);
	}

	/**
	 * Finds the first differing byte, using the default retry timeout.
	 * @see #mismatch(Path, Path, Duration)
	 */
	public static long mismatch(Path first, Path second) throws IOException {
		return mismatch(first, second, WriteOptions.DEFAULT.retryTimeout());
	}

	/**
	 * Finds the first differing byte with Windows access retries.
	 *
	 * @param retryTimeout timeout per operation; zero disables retries
	 * @return {@code -1} if the contents match
	 */
	public static long mismatch(Path first, Path second, Duration retryTimeout) throws IOException {
		Objects.requireNonNull(first, "first");
		Objects.requireNonNull(second, "second");
		return FileIo.retry(first, retryTimeout, () -> Files.mismatch(first, second));
	}
}
