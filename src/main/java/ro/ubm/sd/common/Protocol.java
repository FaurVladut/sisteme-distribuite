package ro.ubm.sd.common;

/**
 * Tipurile de mesaje. Formatul complet este în docs/cerinte.md, secțiunea „Protocol”.
 * Pe TCP: o cerere pe conexiune; antetul este o linie, iar octeții unui fișier urmează
 * imediat după antet, exact {@code size} octeți.
 */
public final class Protocol {
    private Protocol() {
    }

    /** Dimensiunea maximă a unui fișier: 1 GiB. */
    public static final long MAX_FILE_SIZE = 1L << 30;

    // client -> coordonator
    public static final String ALLOCATE = "ALLOCATE";
    public static final String LOCATE = "LOCATE";
    public static final String LIST = "LIST";
    public static final String REMOVE = "REMOVE";
    public static final String NODES = "NODES";

    // client -> nod, nod -> nod
    public static final String PUT = "PUT";
    public static final String REPLICATE = "REPLICATE";
    public static final String GET = "GET";

    // nod -> coordonator, coordonator -> nod
    public static final String COMMIT = "COMMIT";
    public static final String DELETE = "DELETE";
    public static final String INVENTORY = "INVENTORY";

    // răspunsuri
    public static final String ALLOCATED = "ALLOCATED";
    public static final String STORED = "STORED";
    public static final String OK = "OK";
    public static final String LOCATIONS = "LOCATIONS";
    public static final String DATA = "DATA";
    public static final String FILES = "FILES";
    public static final String FILE = "FILE";
    public static final String REMOVED = "REMOVED";
    public static final String DELETED = "DELETED";
    public static final String NODELIST = "NODELIST";
    public static final String NODE = "NODE";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String ERROR = "ERROR";

    // UDP multicast
    public static final String HELLO = "HELLO";
    public static final String DISCOVER = "DISCOVER";
}
