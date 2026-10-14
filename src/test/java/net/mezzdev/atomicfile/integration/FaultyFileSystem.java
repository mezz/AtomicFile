package net.mezzdev.atomicfile.integration;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Wraps default-filesystem disk I/O to inject failures at selected NIO operations and limit channel write sizes. */
final class FaultyFileSystem extends FileSystem {
	enum Operation { CREATE, OPEN, READ, WRITE, FORCE, CLOSE, MOVE, DELETE, SET_PERMISSIONS }

	private final FileSystem delegate = FileSystems.getDefault();
	private final FileSystemProvider provider = new Provider();
	private final Map<Operation, IOException> failures = new EnumMap<>(Operation.class);
	private final Path target;
	int maximumWriteSize = Integer.MAX_VALUE;

	FaultyFileSystem(Path target) {
		this.target = target;
	}

	IOException fail(Operation operation) {
		IOException failure = new IOException("Injected " + operation + " failure");
		failures.put(operation, failure);
		return failure;
	}

	void clearFailure(Operation operation) { failures.remove(operation); }

	Path wrap(Path path) { return new WrappedPath(path); }
	private Path unwrap(Path path) { return ((WrappedPath) path).path; }

	private void check(Operation operation) throws IOException {
		IOException failure = failures.get(operation);
		if (failure != null) {
			throw failure;
		}
	}

	@Override public FileSystemProvider provider() { return provider; }
	@Override public void close() { /* Does not own the default filesystem. */ }
	@Override public boolean isOpen() { return delegate.isOpen(); }
	@Override public boolean isReadOnly() { return delegate.isReadOnly(); }
	@Override public String getSeparator() { return delegate.getSeparator(); }
	@Override public Iterable<Path> getRootDirectories() { return () -> wrapIterator(delegate.getRootDirectories().iterator()); }
	@Override public Iterable<FileStore> getFileStores() { return delegate.getFileStores(); }
	@Override public Set<String> supportedFileAttributeViews() { return delegate.supportedFileAttributeViews(); }
	@Override public Path getPath(String first, String... more) { return wrap(delegate.getPath(first, more)); }
	@Override public PathMatcher getPathMatcher(String syntaxAndPattern) {
		PathMatcher matcher = delegate.getPathMatcher(syntaxAndPattern);
		return path -> matcher.matches(unwrap(path));
	}
	@Override public UserPrincipalLookupService getUserPrincipalLookupService() { return delegate.getUserPrincipalLookupService(); }
	@Override public WatchService newWatchService() throws IOException { return delegate.newWatchService(); }

	private Iterator<Path> wrapIterator(Iterator<Path> iterator) {
		return new Iterator<>() {
			@Override public boolean hasNext() { return iterator.hasNext(); }
			@Override public Path next() { return wrap(iterator.next()); }
		};
	}

	private final class Provider extends FileSystemProvider {
		private final FileSystemProvider original = delegate.provider();

		@Override public String getScheme() { return "faulty"; }
		@Override public FileSystem newFileSystem(URI uri, Map<String, ?> env) { throw new UnsupportedOperationException(); }
		@Override public FileSystem getFileSystem(URI uri) { return FaultyFileSystem.this; }
		@Override public Path getPath(URI uri) { throw new UnsupportedOperationException(); }
		@Override public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
			return newFileChannel(path, options, attrs);
		}
		@Override public FileChannel newFileChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
			if (options.contains(StandardOpenOption.CREATE_NEW)) {
				check(Operation.CREATE);
			} else {
				check(Operation.OPEN);
			}
			return new Channel(original.newFileChannel(unwrap(path), options, attrs));
		}
		@Override public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
			DirectoryStream<Path> stream = original.newDirectoryStream(unwrap(dir), path -> filter.accept(wrap(path)));
			return new DirectoryStream<>() {
				@Override public Iterator<Path> iterator() { return wrapIterator(stream.iterator()); }
				@Override public void close() throws IOException { stream.close(); }
			};
		}
		@Override public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException { original.createDirectory(unwrap(dir), attrs); }
		@Override public void delete(Path path) throws IOException {
			check(Operation.DELETE);
			original.delete(unwrap(path));
		}
		@Override public void copy(Path source, Path target, CopyOption... options) throws IOException { original.copy(unwrap(source), unwrap(target), options); }
		@Override public void move(Path source, Path destination, CopyOption... options) throws IOException {
			if (unwrap(destination).equals(target)) {
				check(Operation.MOVE);
			}
			original.move(unwrap(source), unwrap(destination), options);
		}
		@Override public boolean isSameFile(Path path, Path path2) throws IOException { return original.isSameFile(unwrap(path), unwrap(path2)); }
		@Override public boolean isHidden(Path path) throws IOException { return original.isHidden(unwrap(path)); }
		@Override public FileStore getFileStore(Path path) throws IOException { return original.getFileStore(unwrap(path)); }
		@Override public void checkAccess(Path path, AccessMode... modes) throws IOException { original.checkAccess(unwrap(path), modes); }
		@Override public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
			if (type == PosixFileAttributeView.class) {
				var view = original.getFileAttributeView(unwrap(path), PosixFileAttributeView.class, options);
				if (view != null) {
					return type.cast(new PosixFileAttributeView() {
						@Override public String name() { return view.name(); }
						@Override public PosixFileAttributes readAttributes() throws IOException { return view.readAttributes(); }
						@Override public UserPrincipal getOwner() throws IOException { return view.getOwner(); }
						@Override public void setOwner(UserPrincipal owner) throws IOException { view.setOwner(owner); }
						@Override public void setGroup(GroupPrincipal group) throws IOException { view.setGroup(group); }
						@Override public void setTimes(FileTime modified, FileTime accessed, FileTime created) throws IOException { view.setTimes(modified, accessed, created); }
						@Override public void setPermissions(Set<PosixFilePermission> permissions) throws IOException {
							check(Operation.SET_PERMISSIONS);
							view.setPermissions(permissions);
						}
					});
				}
			}
			return original.getFileAttributeView(unwrap(path), type, options);
		}
		@Override public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) throws IOException {
			return original.readAttributes(unwrap(path), type, options);
		}
		@Override public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
			return original.readAttributes(unwrap(path), attributes, options);
		}
		@Override public void setAttribute(Path path, String attribute, Object value, LinkOption... options) throws IOException {
			original.setAttribute(unwrap(path), attribute, value, options);
		}
	}

	private final class Channel extends FileChannel {
		private final FileChannel original;
		private boolean wrote;

		Channel(FileChannel original) { this.original = original; }

		@Override public int write(ByteBuffer source) throws IOException {
			int limit = source.limit();
			int maximum = failures.containsKey(Operation.WRITE) ? Math.min(7, maximumWriteSize) : maximumWriteSize;
			try {
				source.limit(source.position() + Math.min(source.remaining(), maximum));
				int written = original.write(source);
				wrote = true;
				// Fail after some bytes reached a real file, modelling disk exhaustion mid-write.
				check(Operation.WRITE);
				return written;
			} finally {
				source.limit(limit);
			}
		}
		@Override public void force(boolean metadata) throws IOException {
			check(Operation.FORCE);
			original.force(metadata);
		}
		@Override protected void implCloseChannel() throws IOException {
			original.close();
			if (wrote) {
				check(Operation.CLOSE);
			}
		}
		@Override public int read(ByteBuffer dst) throws IOException {
			int read = original.read(dst);
			check(Operation.READ);
			return read;
		}
		@Override public long read(ByteBuffer[] dsts, int offset, int length) throws IOException { return original.read(dsts, offset, length); }
		@Override public long write(ByteBuffer[] srcs, int offset, int length) throws IOException { return original.write(srcs, offset, length); }
		@Override public long position() throws IOException { return original.position(); }
		@Override public FileChannel position(long position) throws IOException { original.position(position); return this; }
		@Override public long size() throws IOException { return original.size(); }
		@Override public FileChannel truncate(long size) throws IOException { original.truncate(size); return this; }
		@Override public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
			// Newer JDKs can copy streams through channel transfers without calling read.
			long transferred = original.transferTo(position, count, target);
			check(Operation.READ);
			return transferred;
		}
		@Override public long transferFrom(ReadableByteChannel src, long position, long count) throws IOException { return original.transferFrom(src, position, count); }
		@Override public int read(ByteBuffer dst, long position) throws IOException { return original.read(dst, position); }
		@Override public int write(ByteBuffer src, long position) throws IOException { return original.write(src, position); }
		@Override public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException { return original.map(mode, position, size); }
		@Override public FileLock lock(long position, long size, boolean shared) throws IOException { return original.lock(position, size, shared); }
		@Override public @Nullable FileLock tryLock(long position, long size, boolean shared) throws IOException { return original.tryLock(position, size, shared); }
	}

	private final class WrappedPath implements Path {
		private final Path path;
		WrappedPath(Path path) { this.path = path; }
		@Override public FileSystem getFileSystem() { return FaultyFileSystem.this; }
		@Override public boolean isAbsolute() { return path.isAbsolute(); }
		@Override public @Nullable Path getRoot() { return path.getRoot() == null ? null : wrap(path.getRoot()); }
		@Override public @Nullable Path getFileName() { return path.getFileName() == null ? null : wrap(path.getFileName()); }
		@Override public @Nullable Path getParent() { return path.getParent() == null ? null : wrap(path.getParent()); }
		@Override public int getNameCount() { return path.getNameCount(); }
		@Override public Path getName(int index) { return wrap(path.getName(index)); }
		@Override public Path subpath(int begin, int end) { return wrap(path.subpath(begin, end)); }
		@Override public boolean startsWith(Path other) { return path.startsWith(unwrap(other)); }
		@Override public boolean endsWith(Path other) { return path.endsWith(unwrap(other)); }
		@Override public Path normalize() { return wrap(path.normalize()); }
		@Override public Path resolve(Path other) { return wrap(path.resolve(unwrap(other))); }
		@Override public Path relativize(Path other) { return wrap(path.relativize(unwrap(other))); }
		@Override public URI toUri() { return path.toUri(); }
		@Override public Path toAbsolutePath() { return wrap(path.toAbsolutePath()); }
		@Override public Path toRealPath(LinkOption... options) throws IOException { return wrap(path.toRealPath(options)); }
		@Override public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers) throws IOException {
			return path.register(watcher, events, modifiers);
		}
		@Override public Iterator<Path> iterator() { return wrapIterator(path.iterator()); }
		@Override public int compareTo(Path other) { return path.compareTo(unwrap(other)); }
		@Override public String toString() { return path.toString(); }
		@Override public boolean equals(@Nullable Object other) {
			return other instanceof WrappedPath wrapped && wrapped.getFileSystem() == getFileSystem() && path.equals(wrapped.path);
		}
		@Override public int hashCode() { return path.hashCode(); }
	}
}
