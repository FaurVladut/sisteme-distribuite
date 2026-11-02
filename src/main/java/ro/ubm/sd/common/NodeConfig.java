package ro.ubm.sd.common;

import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Configurația unui nod de stocare, citită din linia de comandă. */
public record NodeConfig(String id, int priority, int port, Path dir, String host, boolean coordinator,
                         String mcastGroup, int mcastPort, String iface) {

    private static final Pattern ID = Pattern.compile("n([0-9]{1,9})");

    public static final String USAGE = String.join("\n",
            "utilizare: java -cp target/classes ro.ubm.sd.node.StorageNode --id n<numar> --port <port> --dir <director> [optiuni]",
            "  --id <nX>            identificatorul nodului; numarul X este prioritatea pentru Bully",
            "  --port <port>        portul TCP al nodului",
            "  --dir <director>     directorul local pentru fisiere (se creeaza daca lipseste)",
            "  --host <adresa>      adresa anuntata celorlalte noduri (implicit localhost)",
            "  --coordinator        nodul este coordonator (temporar, pana la Bully din Etapa 5)",
            Multicast.USAGE);

    public static NodeConfig parse(String[] argv) throws ConfigException {
        Args args = Args.parse(argv,
                Set.of("id", "port", "dir", "host", "mcast-group", "mcast-port", "iface"),
                Set.of("coordinator"));

        String id = args.require("id");
        Matcher m = ID.matcher(id);
        if (!m.matches()) {
            throw new ConfigException("--id trebuie sa fie de forma n<numar> (n1, n2, n10), nu " + id);
        }
        int priority = Integer.parseInt(m.group(1));
        int port = Args.port("port", args.require("port"));
        Path dir = Path.of(args.require("dir"));
        String host = args.get("host", "localhost");
        int mcastPort = Args.port("mcast-port", args.get("mcast-port", String.valueOf(Multicast.DEFAULT_PORT)));

        return new NodeConfig(id, priority, port, dir, host, args.has("coordinator"),
                args.get("mcast-group", Multicast.DEFAULT_GROUP), mcastPort, args.get("iface"));
    }

    public NodeAddress address() {
        return new NodeAddress(id, host, port);
    }
}
