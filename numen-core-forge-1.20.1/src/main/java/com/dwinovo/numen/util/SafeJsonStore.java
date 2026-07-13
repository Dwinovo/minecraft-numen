package com.dwinovo.numen.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;

/** Atomic UTF-8 JSON storage with parse verification, backup recovery and quarantine. */
public final class SafeJsonStore {
    private SafeJsonStore() { }

    public static void write(Path file, String json, Function<JsonElement, ?> validator) throws IOException {
        Path parent = file.toAbsolutePath().normalize().getParent();
        if (parent == null) throw new IOException("JSON path has no parent: " + file);
        Files.createDirectories(parent);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Path backup = backup(file);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer data = ByteBuffer.wrap(bytes);
                while (data.hasRemaining()) channel.write(data);
                channel.force(true);
            }
            validate(Files.readString(temp, StandardCharsets.UTF_8), validator);
            if (Files.isRegularFile(file)) move(file, backup);
            try {
                move(temp, file);
            } catch (IOException failure) {
                if (!Files.exists(file) && Files.isRegularFile(backup)) {
                    try { Files.copy(backup, file, StandardCopyOption.REPLACE_EXISTING); }
                    catch (IOException restoreFailure) { failure.addSuppressed(restoreFailure); }
                }
                throw failure;
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public static <T> ReadResult<T> read(Path file, Function<JsonElement, T> parser) throws IOException {
        Path backup = backup(file);
        Exception primaryFailure = null;
        if (Files.isRegularFile(file)) {
            try { return new ReadResult<>(Optional.of(parse(file, parser)), false, null); }
            catch (IOException | RuntimeException failure) { primaryFailure = failure; }
        }
        if (Files.isRegularFile(backup)) {
            try {
                T value = parse(backup, parser);
                if (Files.isRegularFile(file)) quarantine(file);
                Files.copy(backup, file, StandardCopyOption.REPLACE_EXISTING);
                return new ReadResult<>(Optional.ofNullable(value), true, primaryFailure);
            } catch (IOException | RuntimeException backupFailure) {
                if (primaryFailure != null) backupFailure.addSuppressed(primaryFailure);
                quarantineIfPresent(file);
                quarantineIfPresent(backup);
                throw asIo("primary and backup JSON are unreadable: " + file, backupFailure);
            }
        }
        if (primaryFailure != null) {
            quarantineIfPresent(file);
            throw asIo("JSON is unreadable and no backup exists: " + file, primaryFailure);
        }
        return new ReadResult<>(Optional.empty(), false, null);
    }

    public static Path backup(Path file) { return file.resolveSibling(file.getFileName() + ".bak"); }

    private static <T> T parse(Path file, Function<JsonElement, T> parser) throws IOException {
        try { return parser.apply(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))); }
        catch (RuntimeException failure) { throw asIo("invalid JSON in " + file, failure); }
    }

    private static void validate(String json, Function<JsonElement, ?> validator) throws IOException {
        try { validator.apply(JsonParser.parseString(json)); }
        catch (RuntimeException failure) { throw asIo("temporary JSON failed validation", failure); }
    }

    private static void move(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void quarantineIfPresent(Path file) {
        if (!Files.exists(file)) return;
        try { quarantine(file); } catch (IOException ignored) { }
    }

    private static Path quarantine(Path file) throws IOException {
        Path diagnostics = file.toAbsolutePath().normalize().getParent().resolve("diagnostics");
        Files.createDirectories(diagnostics);
        String stamp = Long.toString(Instant.now().toEpochMilli());
        Path target = diagnostics.resolve(file.getFileName() + ".corrupt-" + stamp);
        move(file, target);
        return target;
    }

    private static IOException asIo(String message, Throwable cause) {
        return cause instanceof IOException io ? io : new IOException(message, cause);
    }

    public record ReadResult<T>(Optional<T> value, boolean recoveredFromBackup, Exception primaryFailure) { }
}
