/**
 * Writes and copies complete file contents before replacing the destination.
 * Start with {@link net.mezzdev.atomicfile.AtomicFiles#write(java.nio.file.Path, byte[])} or
 * {@link net.mezzdev.atomicfile.AtomicFiles#copy(java.nio.file.Path, java.nio.file.Path)}.
 * Pass {@link net.mezzdev.atomicfile.WriteOptions} to customize writes and copies.
 * Arguments must not be {@code null}. Return values are never {@code null}.
 */
@NullMarked
package net.mezzdev.atomicfile;

import org.jspecify.annotations.NullMarked;
