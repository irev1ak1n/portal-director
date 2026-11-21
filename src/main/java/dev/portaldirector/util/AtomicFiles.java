package dev.portaldirector.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Writes a file so that a crash or a full disk never leaves half of it: write to a temporary file, flush, then swap it in. */
public final class AtomicFiles {

    public interface Body {

        void write(OutputStream out) throws IOException;
    }

    private AtomicFiles() {
    }

    public static void write(Path target, Body body) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path temp = dir.resolve(target.getFileName() + ".tmp");

        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            OutputStream out = new java.io.BufferedOutputStream(Channels.newOutputStream(channel), 64 * 1024);

            body.write(out);
            out.flush();
            channel.force(true);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }

        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void writeString(Path target, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        write(target, out -> out.write(bytes));
    }
}
