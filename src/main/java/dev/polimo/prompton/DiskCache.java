package dev.polimo.prompton;

import dev.polimo.prompton.internal.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The snapshot mirror on local disk: the raw response bytes at {@code <path>}, and the ETag,
 * {@code Last-Modified}, project and environment in a {@code <path>.meta.json} sidecar.
 *
 * <p>Writes go to a temporary file and are renamed into place, so a reader never sees a half-written
 * document and several processes on one host may share the file. A missing, corrupt or partial file
 * is not an error: it is ignored, and the SDK falls through to the next tier.
 */
final class DiskCache {

    /** What one file holds. */
    record Stored(String body, Map<String, Object> meta) {}

    private DiskCache() {}

    /** Reads a snapshot file and its sidecar, or {@code null} when it cannot be used. */
    static Stored read(Path path) {
        if (path == null) {
            return null;
        }
        try {
            if (!Files.isReadable(path)) {
                return null;
            }
            String body = Files.readString(path, StandardCharsets.UTF_8);
            if (body.isBlank()) {
                return null;
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            Path sidecar = metaPath(path);
            if (Files.isReadable(sidecar)) {
                try {
                    meta.putAll(Json.parseObject(Files.readString(sidecar, StandardCharsets.UTF_8)));
                } catch (RuntimeException ignored) {
                    // A corrupt sidecar costs us the ETag, not the snapshot.
                }
            }
            return new Stored(body, meta);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Writes the snapshot and its sidecar atomically. Returns whether it worked. */
    static boolean write(Path path, String body, Map<String, Object> meta) {
        if (path == null) {
            return false;
        }
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            writeAtomically(path, body);
            writeAtomically(metaPath(path), Json.write(meta));
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** The sidecar path for a snapshot file. */
    static Path metaPath(Path path) {
        return path.resolveSibling(path.getFileName() + ".meta.json");
    }

    private static void writeAtomically(Path path, String content) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        Path temp = Files.createTempFile(parent, path.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
