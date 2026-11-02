package ro.ubm.sd.common;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/**
 * O conexiune TCP care transportă mesaje-linie urmate opțional de octeți.
 * TCP este un flux: antetul se citește octet cu octet până la '\n', ca octeții
 * fișierului care urmează să nu fie consumați de un buffer de text.
 */
public final class Connection implements Closeable {
    public static final int CONNECT_TIMEOUT_MS = 3_000;
    public static final int READ_TIMEOUT_MS = 120_000;
    private static final int MAX_HEADER = 64 * 1024;
    private static final int BUFFER = 64 * 1024;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    public Connection(Socket socket) throws IOException {
        this.socket = socket;
        socket.setSoTimeout(READ_TIMEOUT_MS);
        socket.setTcpNoDelay(true);
        this.in = new BufferedInputStream(socket.getInputStream(), BUFFER);
        this.out = new BufferedOutputStream(socket.getOutputStream(), BUFFER);
    }

    public static Connection open(String host, int port) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            return new Connection(socket);
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    public static Connection open(NodeAddress node) throws IOException {
        return open(node.host(), node.port());
    }

    /** Trimite o cerere și citește un singur răspuns. */
    public static Message request(NodeAddress node, Message request) throws IOException {
        try (Connection c = open(node)) {
            c.send(request);
            return c.receive();
        }
    }

    public String remote() {
        return String.valueOf(socket.getRemoteSocketAddress());
    }

    public void send(Message message) throws IOException {
        writeHeader(message);
        out.flush();
    }

    /** Trimite antetul urmat de conținutul fișierului ({@code size} din antet trebuie să fie dimensiunea lui). */
    public void sendFile(Message header, Path file) throws IOException {
        writeHeader(header);
        Files.copy(file, out);
        out.flush();
    }

    private void writeHeader(Message message) throws IOException {
        out.write(message.encode().getBytes(StandardCharsets.UTF_8));
        out.write('\n');
    }

    public Message receive() throws IOException {
        String line = readLine();
        if (line == null) {
            throw new EOFException("conexiunea a fost inchisa de " + remote());
        }
        return Message.decode(line);
    }

    /** Copiază exact {@code size} octeți din conexiune în {@code target}, actualizând digest-ul. */
    public void receiveBytes(OutputStream target, long size, MessageDigest digest) throws IOException {
        byte[] buffer = new byte[BUFFER];
        long remaining = size;
        while (remaining > 0) {
            int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (n < 0) {
                throw new EOFException("conexiune inchisa dupa " + (size - remaining) + " din " + size + " octeti");
            }
            target.write(buffer, 0, n);
            digest.update(buffer, 0, n);
            remaining -= n;
        }
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        while (true) {
            int b = in.read();
            if (b < 0) {
                if (line.size() == 0) {
                    return null;
                }
                throw new EOFException("antet neterminat");
            }
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                line.write(b);
            }
            if (line.size() > MAX_HEADER) {
                throw new ProtocolException("antet mai lung de " + MAX_HEADER + " octeti");
            }
        }
        return line.toString(StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
