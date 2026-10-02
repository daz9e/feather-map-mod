package dev.daze.worldmap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Атомарная запись: сначала во временный файл, затем замена — сбой посреди записи не портит данные. */
public final class SafeFiles {
    private SafeFiles() {}

    @FunctionalInterface
    public interface Writer {
        void write(Path tmp) throws IOException;
    }

    public static void write(Path file, Writer writer) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            writer.write(tmp);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public static void writeString(Path file, String text) throws IOException {
        write(file, tmp -> Files.writeString(tmp, text, StandardCharsets.UTF_8));
    }
}
