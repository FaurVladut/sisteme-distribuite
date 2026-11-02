package ro.ubm.sd.common;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.StandardSocketOptions;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Descoperire prin UDP multicast. Fiecare nod anunță periodic HELLO cu ID-ul, adresa,
 * portul TCP și dacă este coordonator; nu există o listă fixă de vecini. Un proces nou
 * trimite DISCOVER, iar nodurile răspund imediat cu HELLO, fără să aștepte runda următoare.
 */
public final class Discovery implements Closeable {
    public static final int ANNOUNCE_INTERVAL_MS = 1_000;
    private static final int MAX_DATAGRAM = 1_500;

    private final InetAddress group;
    private final int port;
    private final MulticastSocket socket;
    private final NetworkInterface iface;
    private volatile boolean closed;

    public Discovery(String groupAddress, int port, String ifaceName) throws IOException {
        this.group = InetAddress.getByName(groupAddress);
        if (!group.isMulticastAddress()) {
            throw new IOException(groupAddress + " nu este o adresa multicast");
        }
        this.port = port;
        this.iface = chooseInterface(ifaceName);
        this.socket = new MulticastSocket(port); // SO_REUSEADDR: mai multe procese pe aceeași mașină
        try {
            socket.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, true);
            socket.setTimeToLive(1);
            socket.setNetworkInterface(iface);
            socket.joinGroup(new InetSocketAddress(group, port), iface);
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    public String describe() {
        return group.getHostAddress() + ":" + port + " pe " + iface.getName() + " (" + iface.getDisplayName() + ")";
    }

    public void send(Message message) throws IOException {
        byte[] data = message.encode().getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(data, data.length, group, port));
    }

    /** Așteaptă un mesaj; întoarce null la expirarea timpului. Pachetele malformate sunt ignorate. */
    public Message receive(int timeoutMs) throws IOException {
        byte[] buffer = new byte[MAX_DATAGRAM];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            long left = timeoutMs == 0 ? 0 : deadline - System.currentTimeMillis();
            if (timeoutMs != 0 && left <= 0) {
                return null;
            }
            socket.setSoTimeout((int) left);
            try {
                socket.receive(packet);
            } catch (SocketTimeoutException e) {
                return null;
            }
            try {
                return Message.decode(new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // pachet străin pe grupul nostru
            }
        }
    }

    /**
     * Pornește anunțarea periodică și ascultarea, pe două fire daemon.
     *
     * @param hello    construiește anunțul curent (rolul se poate schimba la rulare)
     * @param received primește fiecare mesaj, inclusiv propriile anunțuri
     * @param failure  primește erorile de rețea, pentru jurnal
     */
    public void start(Supplier<Message> hello, Consumer<Message> received, Consumer<IOException> failure) {
        Thread announcer = new Thread(() -> {
            while (!closed) {
                try {
                    send(hello.get());
                } catch (IOException e) {
                    if (!closed) {
                        failure.accept(e);
                    }
                }
                try {
                    Thread.sleep(ANNOUNCE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "discovery-announce");
        Thread listener = new Thread(() -> {
            while (!closed) {
                try {
                    Message message = receive(0);
                    if (message != null) {
                        received.accept(message);
                    }
                } catch (IOException e) {
                    if (!closed) {
                        failure.accept(e);
                    }
                }
            }
        }, "discovery-listen");
        announcer.setDaemon(true);
        listener.setDaemon(true);
        listener.start();
        announcer.start();
    }

    /** Pentru client: trimite DISCOVER și așteaptă un HELLO de la coordonator. */
    public Optional<NodeAddress> findCoordinator(int timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        long nextDiscover = 0;
        while (true) {
            long now = System.currentTimeMillis();
            if (now >= deadline) {
                return Optional.empty();
            }
            if (now >= nextDiscover) {
                send(Message.of(Protocol.DISCOVER));
                nextDiscover = now + ANNOUNCE_INTERVAL_MS;
            }
            Message message = receive((int) Math.max(1, Math.min(deadline, nextDiscover) - now));
            if (message != null && message.is(Protocol.HELLO) && Boolean.parseBoolean(message.get("coordinator"))) {
                try {
                    return Optional.of(new NodeAddress(message.require("id"), message.require("host"),
                            Integer.parseInt(message.require("port"))));
                } catch (NumberFormatException ignored) {
                    // anunț malformat, așteptăm altul
                }
            }
        }
    }

    /**
     * Interfața pe care circulă multicast-ul: cea dată cu --iface (nume sau adresă IP),
     * altfel interfața rutei implicite, altfel loopback (toate procesele pe aceeași mașină).
     */
    static NetworkInterface chooseInterface(String name) throws IOException {
        if (name != null) {
            NetworkInterface byName = NetworkInterface.getByName(name);
            if (byName != null) {
                return byName;
            }
            NetworkInterface byAddress = NetworkInterface.getByInetAddress(InetAddress.getByName(name));
            if (byAddress != null) {
                return byAddress;
            }
            throw new IOException("interfata necunoscuta: " + name);
        }
        try (DatagramSocket probe = new DatagramSocket()) {
            // connect() pe UDP nu trimite nimic; doar alege adresa locală a rutei implicite
            probe.connect(InetAddress.getByName("192.0.2.1"), 9);
            NetworkInterface routed = NetworkInterface.getByInetAddress(probe.getLocalAddress());
            if (routed != null && routed.isUp() && routed.supportsMulticast()) {
                return routed;
            }
        } catch (SocketException ignored) {
            // fără rută implicită: rămâne loopback
        }
        return NetworkInterface.getByInetAddress(InetAddress.getLoopbackAddress());
    }

    @Override
    public void close() {
        closed = true;
        socket.close();
    }
}
