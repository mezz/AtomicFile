# AtomicFile

[![CI](https://github.com/mezz/AtomicFile/actions/workflows/ci.yml/badge.svg)](https://github.com/mezz/AtomicFile/actions/workflows/ci.yml)

AtomicFile is a Java library for updating configuration files, saved state, and
other files without exposing partially written contents. It writes to a temporary
file beside the destination, then atomically replaces the destination when the
write succeeds.

Requires Java 17 or newer. No runtime dependencies.

## Installation

From a source checkout, run `./mvnw install` to install the library locally, then
add the Maven dependency:

```xml
<dependency>
    <groupId>net.mezzdev</groupId>
    <artifactId>atomicfile</artifactId>
    <version>0.1.0</version>
</dependency>
```

## Writing and copying files

```java
import net.mezzdev.atomicfile.AtomicFiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

Path target = Path.of("config/settings.txt");

// Write a byte array.
byte[] contents = "enabled=true\n".getBytes(StandardCharsets.UTF_8);
AtomicFiles.write(target, contents);

// Or serialize directly to a temporary file.
AtomicFiles.write(target, staged -> Files.writeString(staged, "enabled=false\n"));

// Copy another file's contents, keeping the source.
AtomicFiles.copy(Path.of("defaults/settings.txt"), target);
```

### Shared options

Each write or copy accepts either the destination and contents alone, or an
additional `WriteOptions` argument. Defaults require atomic replacement, create
missing parent directories, use the temporary file's permissions, and retry
Windows sharing conflicts for up to 2 seconds per filesystem operation.

Configure options once and reuse them across files. For example, an application
that supports filesystems without atomic replacement can explicitly allow a
non-atomic fallback:

```java
import net.mezzdev.atomicfile.AtomicMovePolicy;
import net.mezzdev.atomicfile.WriteOptions;

WriteOptions options = WriteOptions.DEFAULT
    .withAtomicMovePolicy(AtomicMovePolicy.ALLOW_NON_ATOMIC);

AtomicFiles.write(target, contents, options);
AtomicFiles.copy(Path.of("defaults/settings.txt"), target, options);
```

Options are immutable; each `with...` method returns a new value. The same options
also work with the writer callback. Non-atomic fallback can expose partial
updates and can lose the old destination if replacement fails.

Other settings are optional:

| Setting | Use |
| --- | --- |
| `withRetryTimeout(Duration)` | Set the timeout per filesystem operation; `Duration.ZERO` disables retries. |
| `withParentDirectoryPolicy(ParentDirectoryPolicy.REQUIRE_EXISTING)` | Require existing parent directories. |
| `withPermissionPolicy(PermissionPolicy.PRESERVE_POSIX_PERMISSIONS)` | Preserve the destination's read/write/execute mode bits. |
| `withPermissionPolicy(PermissionPolicy.PRESERVE_ACL)` | Preserve the destination's ACL entries. |

Permission preservation requires filesystem support and does not copy ownership
or other metadata.

## Guarantees and limits

- Replacement requires an atomic move. `AtomicMovePolicy.ALLOW_NON_ATOMIC`
  permits a fallback that can expose partial updates.
- Operations do not lock the destination. Applications must coordinate writers
  for the same file.
- An interrupted process can leave temporary files behind. Startup recovery and
  cleanup policies belong to the application.
- Atomic replacement does not guarantee durability through power loss.

## Development

Run `./mvnw verify` to build and run the integration tests. CI tests Java 17, 21,
and 25 on Linux, macOS, and Windows. The local coverage report is
`target/site/jacoco/index.html`.
Each CI job displays line and branch coverage in its GitHub Actions summary and
uploads the full coverage and test reports as downloadable artifacts.

Pushing a version tag such as `v0.1.0` publishes to Maven Central after CI passes.
The tag must match the version in `pom.xml`.

[MIT License](LICENSE)
