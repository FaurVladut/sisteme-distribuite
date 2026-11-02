package ro.ubm.sd.node;

import ro.ubm.sd.common.Checksums;
import ro.ubm.sd.common.ConfigException;
import ro.ubm.sd.common.Connection;
import ro.ubm.sd.common.Discovery;
import ro.ubm.sd.common.FileMeta;
import ro.ubm.sd.common.Log;
import ro.ubm.sd.common.Message;
import ro.ubm.sd.common.NodeAddress;
import ro.ubm.sd.common.NodeConfig;
import ro.ubm.sd.common.NodeInfo;
import ro.ubm.sd.common.Protocol;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Nodul de stocare. Toate nodurile rulează același cod; cel pornit cu --coordinator
 * răspunde în plus la cererile de metadate (ALLOCATE, COMMIT, LOCATE, LIST, REMOVE, NODES).
 */
public final class StorageNode {
    private final NodeConfig config;
    private final NodeAddress self;
    private final Log log;
    private final LocalStore store;
    private final MetadataStore metadata = new MetadataStore();
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "request");
        t.setDaemon(true);
        return t;
    });
    private volatile String coordinatorId;
    private volatile String lastDiscoveryError;

    private StorageNode(NodeConfig config) throws IOException {
        this.config = config;
        this.self = config.address();
        this.log = new Log(config.id());
        this.store = new LocalStore(config.dir());
        if (config.coordinator()) {
            coordinatorId = config.id();
        }
    }

    public static void main(String[] args) {
        NodeConfig config;
        try {
            config = NodeConfig.parse(args);
        } catch (ConfigException e) {
            System.err.println("eroare: " + e.getMessage());
            System.err.println(NodeConfig.USAGE);
            System.exit(2);
            return;
        }
        try {
            new StorageNode(config).run();
        } catch (IOException e) {
            System.err.println("[" + config.id() + "] nu poate porni: " + e.getMessage());
            System.exit(1);
        }
    }

    private void run() throws IOException {
        ServerSocket server = new ServerSocket();
        server.bind(new InetSocketAddress(config.port()));

        String role = config.coordinator() ? "coordonator si nod de stocare" : "nod de stocare";
        log.info("pornit: port %d, director %s, rol: %s", config.port(),
                config.dir().toString().replace('\\', '/'), role);

        metadata.nodeSeen(self, System.currentTimeMillis());
        Discovery discovery = new Discovery(config.mcastGroup(), config.mcastPort(), config.iface());
        log.info("descoperire: multicast %s, anunt la fiecare %d ms", discovery.describe(),
                Discovery.ANNOUNCE_INTERVAL_MS);
        discovery.start(this::hello, message -> onDiscovery(discovery, message), this::onDiscoveryError);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            discovery.close();
            log.info("oprit");
        }));

        while (true) {
            Socket socket = server.accept();
            workers.execute(() -> serve(socket));
        }
    }

    // ---------------------------------------------------------------- descoperire

    private Message hello() {
        return Message.of(Protocol.HELLO)
                .with("id", self.id())
                .with("host", self.host())
                .with("port", self.port())
                .with("coordinator", config.coordinator());
    }

    private void onDiscovery(Discovery discovery, Message message) {
        long now = System.currentTimeMillis();
        if (message.is(Protocol.DISCOVER)) {
            try {
                discovery.send(hello());
            } catch (IOException e) {
                onDiscoveryError(e);
            }
            return;
        }
        if (!message.is(Protocol.HELLO)) {
            return;
        }
        NodeAddress peer;
        try {
            peer = new NodeAddress(message.require("id"), message.require("host"),
                    Integer.parseInt(message.require("port")));
        } catch (ProtocolException | NumberFormatException e) {
            return;
        }
        if (peer.id().equals(self.id())) {
            metadata.nodeSeen(self, now);
            if (peer.port() != self.port()) {
                log.error("ATENTIE: alt proces anunta acelasi ID %s pe portul %d", peer.id(), peer.port());
            }
            return;
        }
        if (metadata.nodeSeen(peer, now)) {
            boolean announcer = Boolean.parseBoolean(message.get("coordinator"));
            log.info("nod descoperit: %s la %s%s; noduri cunoscute: %d", peer.id(), peer.hostPort(),
                    announcer ? " (coordonator)" : "", metadata.nodes().size());
            // îi răspundem imediat, ca nodul nou să ne afle fără să aștepte runda următoare
            try {
                discovery.send(hello());
            } catch (IOException e) {
                onDiscoveryError(e);
            }
        }
        if (Boolean.parseBoolean(message.get("coordinator")) && !peer.id().equals(coordinatorId)) {
            String previous = coordinatorId;
            coordinatorId = peer.id();
            if (previous != null) {
                log.error("ATENTIE: doi coordonatori anuntati: %s si %s", previous, peer.id());
            } else {
                log.info("coordonator: %s la %s", peer.id(), peer.hostPort());
            }
        }
    }

    private void onDiscoveryError(IOException e) {
        String text = e.getMessage();
        if (!text.equals(lastDiscoveryError)) { // nu umplem jurnalul cu aceeași eroare la fiecare secundă
            lastDiscoveryError = text;
            log.error("eroare de descoperire: %s", text);
        }
    }

    private boolean isCoordinator() {
        return self.id().equals(coordinatorId);
    }

    private Optional<NodeAddress> coordinator() {
        String id = coordinatorId;
        return id == null ? Optional.empty() : metadata.node(id).map(NodeInfo::address);
    }

    // ---------------------------------------------------------------- TCP

    private void serve(Socket socket) {
        try (Connection c = new Connection(socket)) {
            Message request = c.receive();
            try {
                handle(c, request);
            } catch (ProtocolException | IllegalArgumentException e) {
                c.send(Message.error("bad_request", e.getMessage()));
            }
        } catch (IOException e) {
            log.error("cerere esuata de la %s: %s", socket.getRemoteSocketAddress(), e.getMessage());
        }
    }

    private void handle(Connection c, Message request) throws IOException {
        switch (request.type()) {
            case Protocol.PUT -> receiveCopy(c, request, true);
            case Protocol.REPLICATE -> receiveCopy(c, request, false);
            case Protocol.GET -> sendCopy(c, request);
            case Protocol.DELETE -> deleteCopy(c, request);
            case Protocol.INVENTORY -> inventory(c);
            case Protocol.ALLOCATE, Protocol.COMMIT, Protocol.LOCATE, Protocol.LIST, Protocol.REMOVE, Protocol.NODES -> {
                if (!isCoordinator()) {
                    c.send(Message.error("not_coordinator", self.id() + " nu este coordonator")
                            .with("coordinator", coordinator().map(NodeAddress::toString).orElse(null)));
                    return;
                }
                handleCoordinator(c, request);
            }
            default -> c.send(Message.error("unknown", "mesaj necunoscut: " + request.type()));
        }
    }

    // ---------------------------------------------------------------- operațiile nodului de stocare

    /**
     * PUT (de la client) sau REPLICATE (de la alt nod): primește octeții, verifică checksum-ul,
     * păstrează copia. La PUT nodul copiază mai departe pe {@code next}, apoi raportează
     * coordonatorului cu COMMIT; clientul primește STORED abia după ambele confirmări.
     */
    private void receiveCopy(Connection c, Message request, boolean fromClient) throws IOException {
        UUID id = UUID.fromString(request.require("id"));
        String name = request.require("name");
        long size = request.requireLong("size");
        String checksum = request.require("checksum");
        if (size < 0 || size > Protocol.MAX_FILE_SIZE) {
            c.send(Message.error("too_large", "dimensiune invalida: " + size));
            return;
        }

        Path temp = store.tempFile(id);
        MessageDigest digest = Checksums.sha256();
        try (OutputStream out = Files.newOutputStream(temp)) {
            c.receiveBytes(out, size, digest);
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        String actual = Checksums.hex(digest);
        if (!actual.equalsIgnoreCase(checksum)) {
            Files.deleteIfExists(temp);
            log.error("checksum gresit pentru %s: asteptat %s, primit %s", name, checksum, actual);
            c.send(Message.error("checksum", "checksum diferit pe " + self.id()).with("node", self.id()));
            return;
        }
        FileMeta file = new FileMeta(id, name, size, actual, List.of());
        store.commit(file);
        log.info("copie stocata: %s (%s, %d octeti) %s", name, id, size,
                fromClient ? "de la client" : "replicata de la alt nod");

        if (!fromClient) {
            c.send(Message.of(Protocol.STORED).with("id", id).with("node", self.id()));
            return;
        }

        List<String> holders = new ArrayList<>(List.of(self.id()));
        NodeAddress next = request.get("next") == null ? null : NodeAddress.parse(request.get("next"));
        if (next != null) {
            try {
                replicate(file, next);
                holders.add(next.id());
            } catch (IOException e) {
                store.delete(id);
                log.error("replicarea %s pe %s a esuat: %s", name, next.id(), e.getMessage());
                c.send(Message.error("replica_failed", "copia pe " + next.id() + " a esuat: " + e.getMessage())
                        .with("node", next.id()));
                return;
            }
        }

        FileMeta committed = file.withNodes(holders);
        try {
            commitToCoordinator(committed);
        } catch (IOException e) {
            store.delete(id);
            if (next != null) {
                deleteRemote(next, id);
            }
            log.error("COMMIT pentru %s a esuat: %s", name, e.getMessage());
            c.send(Message.error("commit_failed", e.getMessage()));
            return;
        }
        c.send(Message.of(Protocol.STORED).with("id", id).with("nodes", String.join(",", holders)));
    }

    private void replicate(FileMeta file, NodeAddress target) throws IOException {
        try (Connection c = Connection.open(target)) {
            c.sendFile(Message.of(Protocol.REPLICATE)
                    .with("id", file.id())
                    .with("name", file.name())
                    .with("size", file.size())
                    .with("checksum", file.checksum()), store.dataFile(file.id()));
            Message response = c.receive();
            if (!response.is(Protocol.STORED)) {
                throw new IOException(response.errorText());
            }
        }
    }

    private void commitToCoordinator(FileMeta file) throws IOException {
        NodeAddress coordinator = coordinator()
                .orElseThrow(() -> new IOException("niciun coordonator descoperit inca"));
        Message response = Connection.request(coordinator, Message.of(Protocol.COMMIT)
                .with("id", file.id())
                .with("name", file.name())
                .with("size", file.size())
                .with("checksum", file.checksum())
                .with("nodes", String.join(",", file.nodes())));
        if (!response.is(Protocol.OK)) {
            throw new IOException("coordonatorul a refuzat: " + response.errorText());
        }
    }

    private void deleteRemote(NodeAddress node, UUID id) {
        try {
            Connection.request(node, Message.of(Protocol.DELETE).with("id", id));
        } catch (IOException e) {
            log.error("nu am putut sterge copia %s de pe %s: %s", id, node.id(), e.getMessage());
        }
    }

    private void sendCopy(Connection c, Message request) throws IOException {
        UUID id = UUID.fromString(request.require("id"));
        Optional<FileMeta> file = store.find(id);
        if (file.isEmpty()) {
            c.send(Message.of(Protocol.NOT_FOUND).with("id", id));
            return;
        }
        FileMeta meta = file.get();
        c.sendFile(Message.of(Protocol.DATA)
                .with("id", id)
                .with("name", meta.name())
                .with("size", meta.size())
                .with("checksum", meta.checksum()), store.dataFile(id));
        log.info("copie trimisa: %s catre %s", meta.name(), c.remote());
    }

    private void deleteCopy(Connection c, Message request) throws IOException {
        UUID id = UUID.fromString(request.require("id"));
        boolean deleted = store.delete(id);
        if (deleted) {
            log.info("copie stearsa: %s", id);
        }
        c.send(Message.of(deleted ? Protocol.DELETED : Protocol.NOT_FOUND).with("id", id));
    }

    /** Fișierele deținute local; folosit de coordonator la reconstruirea metadatelor (Etapa 6). */
    private void inventory(Connection c) throws IOException {
        List<FileMeta> files = store.list();
        c.send(Message.of(Protocol.FILES).with("count", files.size()).with("node", self.id()));
        for (FileMeta file : files) {
            c.send(Message.of(Protocol.FILE)
                    .with("id", file.id())
                    .with("name", file.name())
                    .with("size", file.size())
                    .with("checksum", file.checksum()));
        }
    }

    // ---------------------------------------------------------------- coordonator

    private void handleCoordinator(Connection c, Message request) throws IOException {
        switch (request.type()) {
            case Protocol.ALLOCATE -> allocate(c, request);
            case Protocol.COMMIT -> commit(c, request);
            case Protocol.LOCATE -> locate(c, request);
            case Protocol.LIST -> list(c);
            case Protocol.REMOVE -> remove(c, request);
            case Protocol.NODES -> nodes(c);
            default -> throw new IllegalStateException(request.type());
        }
    }

    private void allocate(Connection c, Message request) throws IOException {
        String name = request.require("name");
        long size = request.requireLong("size");
        if (size < 0 || size > Protocol.MAX_FILE_SIZE) {
            c.send(Message.error("too_large", "dimensiunea maxima este " + Protocol.MAX_FILE_SIZE + " octeti"));
            return;
        }
        UUID retryId = request.get("id") == null ? null : UUID.fromString(request.get("id"));
        Set<String> exclude = new HashSet<>();
        if (request.get("exclude") != null && !request.get("exclude").isEmpty()) {
            exclude.addAll(List.of(request.get("exclude").split(",")));
        }
        try {
            MetadataStore.Allocation allocation = metadata.allocate(name, retryId, exclude);
            log.info("ALLOCATE %s (%d octeti): id %s, noduri %s", name, size, allocation.id(),
                    allocation.nodes().stream().map(NodeAddress::id).toList());
            c.send(Message.of(Protocol.ALLOCATED)
                    .with("id", allocation.id())
                    .with("nodes", NodeAddress.join(allocation.nodes())));
        } catch (MetadataStore.MetadataException e) {
            c.send(Message.error(e.code(), e.getMessage()));
        }
    }

    private void commit(Connection c, Message request) throws IOException {
        FileMeta file = new FileMeta(UUID.fromString(request.require("id")), request.require("name"),
                request.requireLong("size"), request.require("checksum"),
                List.of(request.require("nodes").split(",")));
        try {
            metadata.commit(file);
            log.info("COMMIT %s (%s): copii pe %s", file.name(), file.id(), file.nodes());
            c.send(Message.of(Protocol.OK).with("id", file.id()));
        } catch (MetadataStore.MetadataException e) {
            c.send(Message.error(e.code(), e.getMessage()));
        }
    }

    private void locate(Connection c, Message request) throws IOException {
        Optional<FileMeta> found = metadata.byName(request.require("name"));
        if (found.isEmpty()) {
            c.send(Message.of(Protocol.NOT_FOUND).with("name", request.get("name")));
            return;
        }
        FileMeta file = found.get();
        List<NodeAddress> locations = new ArrayList<>();
        for (String id : file.nodes()) {
            metadata.node(id).ifPresent(node -> locations.add(node.address()));
        }
        c.send(Message.of(Protocol.LOCATIONS)
                .with("id", file.id())
                .with("name", file.name())
                .with("size", file.size())
                .with("checksum", file.checksum())
                .with("nodes", NodeAddress.join(locations)));
    }

    private void list(Connection c) throws IOException {
        List<FileMeta> files = metadata.files();
        c.send(Message.of(Protocol.FILES).with("count", files.size()));
        for (FileMeta file : files) {
            c.send(Message.of(Protocol.FILE)
                    .with("id", file.id())
                    .with("name", file.name())
                    .with("size", file.size())
                    .with("checksum", file.checksum())
                    .with("nodes", String.join(",", file.nodes())));
        }
    }

    /**
     * Șterge metadatele, apoi copiile. Un nod care nu răspunde păstrează o copie orfană,
     * raportată clientului; ea nu mai este vizibilă, pentru că nu mai există în metadate.
     */
    private void remove(Connection c, Message request) throws IOException {
        Optional<FileMeta> removed = metadata.remove(request.require("name"));
        if (removed.isEmpty()) {
            c.send(Message.of(Protocol.NOT_FOUND).with("name", request.get("name")));
            return;
        }
        FileMeta file = removed.get();
        List<String> deleted = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String nodeId : file.nodes()) {
            Optional<NodeInfo> node = metadata.node(nodeId);
            try {
                if (node.isEmpty()) {
                    throw new IOException("nod necunoscut");
                }
                Connection.request(node.get().address(), Message.of(Protocol.DELETE).with("id", file.id()));
                deleted.add(nodeId);
            } catch (IOException e) {
                failed.add(nodeId);
                log.error("REMOVE %s: copia de pe %s nu a putut fi stearsa: %s", file.name(), nodeId, e.getMessage());
            }
        }
        log.info("REMOVE %s (%s): sters de pe %s%s", file.name(), file.id(), deleted,
                failed.isEmpty() ? "" : ", nesters de pe " + failed);
        c.send(Message.of(Protocol.REMOVED)
                .with("id", file.id())
                .with("deleted", String.join(",", deleted))
                .with("failed", String.join(",", failed)));
    }

    private void nodes(Connection c) throws IOException {
        List<NodeInfo> nodes = metadata.nodes();
        long now = System.currentTimeMillis();
        c.send(Message.of(Protocol.NODELIST).with("count", nodes.size()).with("coordinator", coordinatorId));
        for (NodeInfo node : nodes) {
            c.send(Message.of(Protocol.NODE)
                    .with("id", node.id())
                    .with("host", node.address().host())
                    .with("port", node.address().port())
                    .with("state", node.state())
                    .with("lastSeenMs", now - node.lastSeen()));
        }
    }
}
