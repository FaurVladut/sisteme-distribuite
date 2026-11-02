package ro.ubm.sd.node;

import ro.ubm.sd.common.FileMeta;
import ro.ubm.sd.common.NodeAddress;
import ro.ubm.sd.common.NodeInfo;
import ro.ubm.sd.common.NodeState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cele două tabele ale coordonatorului: metadatele fișierelor și lista nodurilor.
 * Toate nodurile țin lista nodurilor (din descoperire), pentru că oricare poate deveni
 * coordonator în Etapa 5; tabela fișierelor este folosită doar de coordonatorul curent.
 */
public final class MetadataStore {
    /** O alocare neconfirmată încă prin COMMIT expiră după acest timp. */
    private static final long PENDING_TTL_MS = 10 * 60 * 1000;

    /** Rezultatul ALLOCATE: ID-ul fișierului și nodurile alese, în ordinea transferului. */
    public record Allocation(UUID id, List<NodeAddress> nodes) {
    }

    /** O eroare raportată clientului ca {@code ERROR code=... message=...}. */
    public static final class MetadataException extends Exception {
        private final String code;

        MetadataException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private record Pending(UUID id, long createdAt) {
    }

    private final Map<String, NodeInfo> nodes = new ConcurrentHashMap<>();

    // protejate de "this"
    private final Map<String, FileMeta> files = new TreeMap<>();
    private final Map<String, Pending> pending = new HashMap<>();

    // ---------------------------------------------------------------- noduri

    /** Înregistrează un anunț HELLO. @return true dacă nodul nu era cunoscut */
    public boolean nodeSeen(NodeAddress address, long now) {
        boolean[] added = {false};
        nodes.compute(address.id(), (id, existing) -> {
            if (existing == null) {
                added[0] = true;
                return new NodeInfo(address, now);
            }
            existing.seen(address, now);
            return existing;
        });
        return added[0];
    }

    public Optional<NodeInfo> node(String id) {
        return Optional.ofNullable(nodes.get(id));
    }

    public List<NodeInfo> nodes() {
        List<NodeInfo> list = new ArrayList<>(nodes.values());
        list.sort(Comparator.comparing(NodeInfo::id, MetadataStore::compareIds));
        return list;
    }

    /** n2 înaintea lui n10. */
    static int compareIds(String a, String b) {
        int byLength = Integer.compare(a.length(), b.length());
        return byLength != 0 ? byLength : a.compareTo(b);
    }

    // ---------------------------------------------------------------- fișiere

    /**
     * Alege două noduri active distincte pentru un fișier nou.
     * Regula (pregătită pentru Etapa 4): nodurile cu cele mai puține copii, la egalitate ID-ul mai mic.
     *
     * @param retryId ID-ul primit la o încercare anterioară, ca reîncercarea să nu fie refuzată ca duplicat
     * @param exclude noduri care au eșuat la încercarea anterioară
     */
    public synchronized Allocation allocate(String name, UUID retryId, Set<String> exclude) throws MetadataException {
        if (files.containsKey(name)) {
            throw new MetadataException("exists", "fisierul " + name + " exista deja; sterge-l intai cu rm");
        }
        Pending reserved = pending.get(name);
        long now = System.currentTimeMillis();
        if (reserved != null && now - reserved.createdAt() > PENDING_TTL_MS) {
            pending.remove(name);
            reserved = null;
        }
        if (reserved != null && !reserved.id().equals(retryId)) {
            throw new MetadataException("busy", "fisierul " + name + " este in curs de incarcare");
        }

        Map<String, Integer> copies = new HashMap<>();
        for (FileMeta file : files.values()) {
            for (String node : file.nodes()) {
                copies.merge(node, 1, Integer::sum);
            }
        }
        List<NodeInfo> candidates = new ArrayList<>();
        for (NodeInfo node : nodes.values()) {
            if (node.state() == NodeState.ACTIV && !exclude.contains(node.id())) {
                candidates.add(node);
            }
        }
        if (candidates.size() < 2) {
            throw new MetadataException("insufficient_nodes",
                    "sunt necesare 2 noduri active, disponibile: " + candidates.size());
        }
        candidates.sort(Comparator.<NodeInfo>comparingInt(n -> copies.getOrDefault(n.id(), 0))
                .thenComparing(NodeInfo::id, MetadataStore::compareIds));

        UUID id = reserved != null ? reserved.id() : UUID.randomUUID();
        pending.put(name, new Pending(id, now));
        return new Allocation(id, List.of(candidates.get(0).address(), candidates.get(1).address()));
    }

    /** Înregistrează un fișier după ce ambele copii au fost confirmate. */
    public synchronized void commit(FileMeta file) throws MetadataException {
        FileMeta existing = files.get(file.name());
        if (existing != null && !existing.id().equals(file.id())) {
            throw new MetadataException("exists", "fisierul " + file.name() + " exista deja");
        }
        Pending reserved = pending.get(file.name());
        if (reserved != null && !reserved.id().equals(file.id())) {
            throw new MetadataException("busy", "fisierul " + file.name() + " este rezervat de alta incarcare");
        }
        pending.remove(file.name());
        files.put(file.name(), file);
    }

    public synchronized Optional<FileMeta> byName(String name) {
        return Optional.ofNullable(files.get(name));
    }

    public synchronized List<FileMeta> files() {
        return new ArrayList<>(files.values());
    }

    public synchronized Optional<FileMeta> remove(String name) {
        return Optional.ofNullable(files.remove(name));
    }
}
