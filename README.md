# AtomicFile

[![CI](https://github.com/mezz/AtomicFile/actions/workflows/ci.yml/badge.svg)](https://github.com/mezz/AtomicFile/actions/workflows/ci.yml)

AtomicFile writes complete contents to a temporary file beside the destination,
then atomically replaces the destination. Requires Java 17 or newer, with no
runtime dependencies.

## Usage

Dependency: `net.mezzdev:atomicfile:0.2.0`.

```java
import net.mezzdev.atomicfile.AtomicFiles;
import java.nio.file.Files;
import java.nio.file.Path;

Path target = Path.of("config/settings.txt");

AtomicFiles.write(target, temporary -> Files.writeString(temporary, "enabled=true\n"));
AtomicFiles.copy(Path.of("defaults/settings.txt"), target);
```

Use `WriteOptions` to customize retries, permissions, or atomic-move fallback.
For deferred commits and rollback, use `stage`, `stageCopy`, or `snapshot`.
Commit their handles to publish or restore contents; close them to discard.

See [AtomicFiles](src/main/java/net/mezzdev/atomicfile/AtomicFiles.java) and
[WriteOptions](src/main/java/net/mezzdev/atomicfile/WriteOptions.java) for API details.

Default replacement is atomic: readers see the complete old or new file.
Applications must coordinate concurrent writers. Atomic replacement does not
guarantee durability through power loss.

## Development

Run `./mvnw verify` to build and test.

[MIT License](LICENSE)
