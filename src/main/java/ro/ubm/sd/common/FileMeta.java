package ro.ubm.sd.common;

import java.util.List;
import java.util.UUID;

/**
 * Metadatele unui fișier.
 *
 * @param id       stabilit de coordonator la ALLOCATE
 * @param name     dat de utilizator; unic în sistem
 * @param size     în octeți
 * @param checksum SHA-256, hex
 * @param nodes    ID-urile nodurilor care au o copie (goală pentru o copie locală)
 */
public record FileMeta(UUID id, String name, long size, String checksum, List<String> nodes) {

    public FileMeta {
        nodes = List.copyOf(nodes);
    }

    public FileMeta withNodes(List<String> newNodes) {
        return new FileMeta(id, name, size, checksum, newNodes);
    }
}
