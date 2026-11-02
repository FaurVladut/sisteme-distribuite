package ro.ubm.sd.common;

import java.util.Set;

/**
 * Configurația clientului. Coordonatorul poate fi dat explicit cu --coordinator;
 * dacă lipsește, clientul îl găsește prin descoperire multicast.
 */
public record ClientConfig(String coordinatorHost, int coordinatorPort,
                           String mcastGroup, int mcastPort, String iface) {

    public static final String USAGE = String.join("\n",
            "utilizare: java -cp target/classes ro.ubm.sd.client.Client [--coordinator <host:port>] [optiuni]",
            "  --coordinator <host:port>  coordonatorul; fara el este cautat prin multicast",
            Multicast.USAGE);

    public static ClientConfig parse(String[] argv) throws ConfigException {
        Args args = Args.parse(argv, Set.of("coordinator", "mcast-group", "mcast-port", "iface"), Set.of());

        String host = null;
        int port = 0;
        String coordinator = args.get("coordinator");
        if (coordinator != null) {
            int colon = coordinator.lastIndexOf(':');
            if (colon <= 0 || colon == coordinator.length() - 1) {
                throw new ConfigException("--coordinator trebuie sa fie de forma host:port, nu " + coordinator);
            }
            host = coordinator.substring(0, colon);
            port = Args.port("coordinator", coordinator.substring(colon + 1));
        }
        int mcastPort = Args.port("mcast-port", args.get("mcast-port", String.valueOf(Multicast.DEFAULT_PORT)));
        return new ClientConfig(host, port, args.get("mcast-group", Multicast.DEFAULT_GROUP), mcastPort,
                args.get("iface"));
    }

    public boolean hasFixedCoordinator() {
        return coordinatorHost != null;
    }
}
