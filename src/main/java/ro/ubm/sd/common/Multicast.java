package ro.ubm.sd.common;

/** Valorile implicite pentru descoperirea prin UDP multicast, comune nodurilor și clientului. */
public final class Multicast {
    /** Adresă din blocul administrat local (239.0.0.0/8), nu iese din rețeaua locală. */
    public static final String DEFAULT_GROUP = "239.255.10.10";
    public static final int DEFAULT_PORT = 4446;

    private Multicast() {
    }

    public static final String USAGE =
            "  --mcast-group <ip>   grupul multicast pentru descoperire (implicit " + DEFAULT_GROUP + ")\n"
          + "  --mcast-port <port>  portul UDP pentru descoperire (implicit " + DEFAULT_PORT + ")\n"
          + "  --iface <nume|ip>    interfata de retea pentru multicast (implicit: cea a rutei implicite)";
}
