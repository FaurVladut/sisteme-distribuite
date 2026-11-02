package ro.ubm.sd.client;

import ro.ubm.sd.common.Checksums;
import ro.ubm.sd.common.ClientConfig;
import ro.ubm.sd.common.ConfigException;
import ro.ubm.sd.common.Connection;
import ro.ubm.sd.common.Discovery;
import ro.ubm.sd.common.Message;
import ro.ubm.sd.common.NodeAddress;
import ro.ubm.sd.common.Protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interfața în terminal. Metadatele se cer coordonatorului; conținutul fișierelor
 * circulă direct între client și nodurile de stocare.
 */
public final class Client {
    private static final String COMMANDS = "comenzi: put, get, ls, rm, nodes, exit";
    private static final int DISCOVERY_TIMEOUT_MS = 5_000;
    private static final int PUT_ATTEMPTS = 3;
    private static final Pattern TOKEN = Pattern.compile("\"([^\"]*)\"|(\\S+)");

    private final ClientConfig config;
    private NodeAddress coordinator;

    private Client(ClientConfig config) {
        this.config = config;
        if (config.hasFixedCoordinator()) {
            coordinator = new NodeAddress("coordonator", config.coordinatorHost(), config.coordinatorPort());
        }
    }

    public static void main(String[] args) {
        ClientConfig config;
        try {
            config = ClientConfig.parse(args);
        } catch (ConfigException e) {
            System.err.println("eroare: " + e.getMessage());
            System.err.println(ClientConfig.USAGE);
            System.exit(2);
            return;
        }
        new Client(config).run();
    }

    private void run() {
        if (config.hasFixedCoordinator()) {
            System.out.println("[client] coordonator configurat: " + coordinator.hostPort() + "; " + COMMANDS);
        } else {
            try {
                discover();
                System.out.println("[client] coordonator descoperit: " + coordinator + "; " + COMMANDS);
            } catch (IOException e) {
                System.out.println("[client] " + e.getMessage() + "; comenzile vor reincerca descoperirea; " + COMMANDS);
            }
        }

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            System.out.print("sd> ");
            System.out.flush();
            String line;
            try {
                line = in.readLine();
            } catch (IOException e) {
                return;
            }
            if (line == null) {
                return;
            }
            List<String> words = tokenize(line);
            if (words.isEmpty()) {
                continue;
            }
            String command = words.get(0);
            List<String> rest = words.subList(1, words.size());
            if (command.equals("exit") || command.equals("quit")) {
                return;
            }
            try {
                execute(command, rest);
            } catch (UsageException e) {
                System.out.println("utilizare: " + e.getMessage());
            } catch (IOException e) {
                System.out.println("eroare: " + e.getMessage());
            }
        }
    }

    private static List<String> tokenize(String line) {
        List<String> words = new ArrayList<>();
        Matcher m = TOKEN.matcher(line);
        while (m.find()) {
            words.add(m.group(1) != null ? m.group(1) : m.group(2));
        }
        return words;
    }

    private static final class UsageException extends Exception {
        UsageException(String usage) {
            super(usage);
        }
    }

    private void execute(String command, List<String> args) throws IOException, UsageException {
        switch (command) {
            case "put" -> {
                requireArgs(args, 1, 1, "put <fisier-local>");
                put(Path.of(args.get(0)));
            }
            case "get" -> {
                requireArgs(args, 1, 2, "get <nume> [director]");
                get(args.get(0), Path.of(args.size() > 1 ? args.get(1) : "."));
            }
            case "ls" -> {
                requireArgs(args, 0, 0, "ls");
                ls();
            }
            case "rm" -> {
                requireArgs(args, 1, 1, "rm <nume>");
                rm(args.get(0));
            }
            case "nodes" -> {
                requireArgs(args, 0, 0, "nodes");
                nodes();
            }
            case "help" -> help();
            default -> System.out.println("comanda necunoscuta: " + command + " (help pentru lista)");
        }
    }

    private static void requireArgs(List<String> args, int min, int max, String usage) throws UsageException {
        if (args.size() < min || args.size() > max) {
            throw new UsageException(usage);
        }
    }

    private static void help() {
        System.out.println("  put <fisier-local>      incarca un fisier (2 copii pe noduri distincte)");
        System.out.println("  get <nume> [director]   descarca un fisier si ii verifica checksum-ul");
        System.out.println("  ls                      fisierele, cu dimensiunea si nodurile fiecaruia");
        System.out.println("  rm <nume>               sterge un fisier");
        System.out.println("  nodes                   nodurile cunoscute, starea lor si coordonatorul");
        System.out.println("  help, exit");
    }

    // ---------------------------------------------------------------- coordonator

    private void discover() throws IOException {
        try (Discovery discovery = new Discovery(config.mcastGroup(), config.mcastPort(), config.iface())) {
            Optional<NodeAddress> found = discovery.findCoordinator(DISCOVERY_TIMEOUT_MS);
            if (found.isEmpty()) {
                throw new IOException("niciun coordonator gasit prin multicast " + discovery.describe()
                        + " in " + DISCOVERY_TIMEOUT_MS / 1000 + " s");
            }
            coordinator = found.get();
        }
    }

    /** Deschide o conexiune la coordonator; dacă a fost descoperit și nu răspunde, îl caută din nou. */
    private Connection openCoordinator() throws IOException {
        if (coordinator == null) {
            discover();
            System.out.println("coordonator descoperit: " + coordinator);
        }
        try {
            return Connection.open(coordinator);
        } catch (IOException e) {
            if (config.hasFixedCoordinator()) {
                throw new IOException("coordonatorul " + coordinator.hostPort() + " nu raspunde: " + e.getMessage());
            }
            NodeAddress previous = coordinator;
            discover();
            System.out.println("coordonatorul " + previous + " nu raspunde; descoperit: " + coordinator);
            return Connection.open(coordinator);
        }
    }

    private Message askCoordinator(Message request) throws IOException {
        try (Connection c = openCoordinator()) {
            c.send(request);
            return c.receive();
        }
    }

    // ---------------------------------------------------------------- comenzi

    /**
     * ALLOCATE la coordonator, apoi PUT direct pe primul nod, care replică pe al doilea.
     * Dacă un nod eșuează, se cere o nouă alocare fără el, cu același ID de fișier.
     */
    private void put(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("fisier local inexistent: " + file);
        }
        String name = file.getFileName().toString();
        long size = Files.size(file);
        if (size > Protocol.MAX_FILE_SIZE) {
            throw new IOException("fisierul depaseste dimensiunea maxima de " + Protocol.MAX_FILE_SIZE + " octeti");
        }
        long start = System.nanoTime();
        String checksum = Checksums.of(file);

        UUID id = null;
        Set<String> exclude = new LinkedHashSet<>();
        String lastError = null;
        for (int attempt = 1; attempt <= PUT_ATTEMPTS; attempt++) {
            Message allocated = askCoordinator(Message.of(Protocol.ALLOCATE)
                    .with("name", name)
                    .with("size", size)
                    .with("id", id)
                    .with("exclude", exclude.isEmpty() ? null : String.join(",", exclude)));
            if (!allocated.is(Protocol.ALLOCATED)) {
                throw new IOException(allocated.errorText());
            }
            id = UUID.fromString(allocated.require("id"));
            List<NodeAddress> nodes = NodeAddress.parseList(allocated.require("nodes"));
            NodeAddress first = nodes.get(0);
            NodeAddress second = nodes.get(1);

            Message stored;
            try (Connection c = Connection.open(first)) {
                c.sendFile(Message.of(Protocol.PUT)
                        .with("id", id)
                        .with("name", name)
                        .with("size", size)
                        .with("checksum", checksum)
                        .with("next", second), file);
                stored = c.receive();
            } catch (IOException e) {
                lastError = first.id() + " nu raspunde: " + e.getMessage();
                exclude.add(first.id());
                System.out.println("incercarea " + attempt + ": " + lastError + "; aleg alt nod");
                continue;
            }
            if (stored.is(Protocol.STORED)) {
                System.out.printf(Locale.ROOT, "incarcat %s: id %s, %d octeti, copii pe %s (%.2f s)%n",
                        name, id, size, stored.get("nodes"), (System.nanoTime() - start) / 1e9);
                return;
            }
            lastError = stored.errorText();
            String failedNode = stored.get("node");
            if (failedNode == null) {
                throw new IOException(lastError);
            }
            exclude.add(failedNode);
            System.out.println("incercarea " + attempt + ": " + lastError + "; aleg alt nod");
        }
        throw new IOException("incarcarea a esuat dupa " + PUT_ATTEMPTS + " incercari: " + lastError);
    }

    /** LOCATE la coordonator, apoi GET direct de la noduri, în ordine, până la o copie cu checksum corect. */
    private void get(String name, Path targetDir) throws IOException {
        Message location = askCoordinator(Message.of(Protocol.LOCATE).with("name", name));
        if (location.is(Protocol.NOT_FOUND)) {
            throw new IOException("fisier necunoscut: " + name);
        }
        if (!location.is(Protocol.LOCATIONS)) {
            throw new IOException(location.errorText());
        }
        String id = location.require("id");
        String expected = location.require("checksum");
        List<NodeAddress> nodes = NodeAddress.parseList(location.get("nodes"));
        if (nodes.isEmpty()) {
            throw new IOException("nicio copie cunoscuta pentru " + name);
        }

        Files.createDirectories(targetDir);
        Path target = targetDir.resolve(name);
        Path temp = targetDir.resolve(name + ".part");
        List<String> errors = new ArrayList<>();
        for (NodeAddress node : nodes) {
            long start = System.nanoTime();
            try (Connection c = Connection.open(node)) {
                c.send(Message.of(Protocol.GET).with("id", id));
                Message data = c.receive();
                if (!data.is(Protocol.DATA)) {
                    errors.add(node.id() + ": " + (data.is(Protocol.NOT_FOUND) ? "nu are copia" : data.errorText()));
                    continue;
                }
                long size = data.requireLong("size");
                MessageDigest digest = Checksums.sha256();
                try (OutputStream out = Files.newOutputStream(temp)) {
                    c.receiveBytes(out, size, digest);
                }
                String actual = Checksums.hex(digest);
                if (!actual.equalsIgnoreCase(expected)) {
                    Files.deleteIfExists(temp);
                    errors.add(node.id() + ": checksum gresit");
                    System.out.println("copia de pe " + node.id() + " are checksum gresit; incerc urmatoarea copie");
                    continue;
                }
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                System.out.printf(Locale.ROOT, "descarcat %s de la %s in %s, %d octeti, checksum verificat %s (%.2f s)%n",
                        name, node.id(), target, size, Checksums.shortForm(actual), (System.nanoTime() - start) / 1e9);
                return;
            } catch (IOException e) {
                Files.deleteIfExists(temp);
                errors.add(node.id() + ": " + e.getMessage());
                System.out.println(node.id() + " nu raspunde (" + e.getMessage() + "); incerc urmatoarea copie");
            }
        }
        throw new IOException("nicio copie accesibila pentru " + name + ": " + String.join("; ", errors));
    }

    private void ls() throws IOException {
        try (Connection c = openCoordinator()) {
            c.send(Message.of(Protocol.LIST));
            Message header = c.receive();
            if (!header.is(Protocol.FILES)) {
                throw new IOException(header.errorText());
            }
            long count = header.requireLong("count");
            if (count == 0) {
                System.out.println("niciun fisier");
                return;
            }
            System.out.printf("%-30s %12s  %-12s %s%n", "NUME", "OCTETI", "NODURI", "ID");
            for (long i = 0; i < count; i++) {
                Message file = c.receive();
                System.out.printf("%-30s %12s  %-12s %s%n", file.get("name"), file.get("size"), file.get("nodes"),
                        file.get("id"));
            }
        }
    }

    private void rm(String name) throws IOException {
        Message response = askCoordinator(Message.of(Protocol.REMOVE).with("name", name));
        if (response.is(Protocol.NOT_FOUND)) {
            throw new IOException("fisier necunoscut: " + name);
        }
        if (!response.is(Protocol.REMOVED)) {
            throw new IOException(response.errorText());
        }
        String failed = response.get("failed", "");
        System.out.println("sters " + name + " de pe " + response.get("deleted", "")
                + (failed.isEmpty() ? "" : "; copii ramase pe nodurile care nu au raspuns: " + failed));
    }

    private void nodes() throws IOException {
        try (Connection c = openCoordinator()) {
            c.send(Message.of(Protocol.NODES));
            Message header = c.receive();
            if (!header.is(Protocol.NODELIST)) {
                throw new IOException(header.errorText());
            }
            String coordinatorId = header.get("coordinator");
            long count = header.requireLong("count");
            System.out.printf("%-6s %-22s %-8s %-14s %s%n", "ID", "ADRESA", "STARE", "ULTIMUL SEMNAL", "ROL");
            for (long i = 0; i < count; i++) {
                Message node = c.receive();
                String id = node.get("id");
                System.out.printf(Locale.ROOT, "%-6s %-22s %-8s %-14s %s%n", id,
                        node.get("host") + ":" + node.get("port"), node.get("state"),
                        "acum " + node.get("lastSeenMs") + " ms",
                        id.equals(coordinatorId) ? "coordonator" : "stocare");
            }
        }
    }
}
