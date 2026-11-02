package ro.ubm.sd.node;

import ro.ubm.sd.common.FileMeta;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/**
 * Copiile locale ale unui nod, în directorul dat cu --dir:
 * {@code <id>.bin} conținutul, {@code <id>.meta} numele, dimensiunea și checksum-ul.
 * Fișierul .meta permite unui coordonator nou să reconstruiască tabela (Etapa 6).
 * O copie în curs de primire este {@code <id>.part} și devine .bin abia după verificarea checksum-ului.
 */
public final class LocalStore {
    private final Path dir;

    public LocalStore(Path dir) throws IOException {
        this.dir = dir;
        Files.createDirectories(dir);
        try (DirectoryStream<Path> leftovers = Files.newDirectoryStream(dir, "*.part")) {
            for (Path part : leftovers) {
                Files.deleteIfExists(part); // transfer întrerupt de o oprire anterioară
            }
        }
    }

    public Path tempFile(UUID id) {
        return dir.resolve(id + ".part");
    }

    public Path dataFile(UUID id) {
        return dir.resolve(id + ".bin");
    }

    private Path metaFile(UUID id) {
        return dir.resolve(id + ".meta");
    }

    /** Mută copia primită din .part în .bin și scrie .meta lângă ea. */
    public void commit(FileMeta meta) throws IOException {
        Properties props = new Properties();
        props.setProperty("id", meta.id().toString());
        props.setProperty("name", meta.name());
        props.setProperty("size", String.valueOf(meta.size()));
        props.setProperty("checksum", meta.checksum());
        try (OutputStream out = Files.newOutputStream(metaFile(meta.id()))) {
            props.store(out, "sd-storage");
        }
        try {
            Files.move(tempFile(meta.id()), dataFile(meta.id()),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tempFile(meta.id()), dataFile(meta.id()), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public Optional<FileMeta> find(UUID id) throws IOException {
        Path meta = metaFile(id);
        if (!Files.exists(meta) || !Files.exists(dataFile(id))) {
            return Optional.empty();
        }
        return Optional.of(readMeta(meta));
    }

    public boolean delete(UUID id) throws IOException {
        boolean data = Files.deleteIfExists(dataFile(id));
        boolean meta = Files.deleteIfExists(metaFile(id));
        Files.deleteIfExists(tempFile(id));
        return data || meta;
    }

    public List<FileMeta> list() throws IOException {
        List<FileMeta> files = new ArrayList<>();
        try (DirectoryStream<Path> metas = Files.newDirectoryStream(dir, "*.meta")) {
            for (Path meta : metas) {
                FileMeta file = readMeta(meta);
                if (Files.exists(dataFile(file.id()))) {
                    files.add(file);
                }
            }
        }
        return files;
    }

    private static FileMeta readMeta(Path path) throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        }
        try {
            return new FileMeta(UUID.fromString(props.getProperty("id")), props.getProperty("name"),
                    Long.parseLong(props.getProperty("size")), props.getProperty("checksum"), List.of());
        } catch (RuntimeException e) {
            throw new IOException("fisier .meta corupt: " + path, e);
        }
    }
}
