package ro.ubm.sd.common;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Identitatea unui nod: ID, adresă și port. Pe fir: {@code n2@localhost:7002}. */
public record NodeAddress(String id, String host, int port) {

    public String hostPort() {
        return host + ":" + port;
    }

    @Override
    public String toString() {
        return id + "@" + host + ":" + port;
    }

    public static NodeAddress parse(String text) {
        int at = text.indexOf('@');
        int colon = text.lastIndexOf(':');
        if (at <= 0 || colon <= at + 1) {
            throw new IllegalArgumentException("adresa de nod invalida: " + text);
        }
        return new NodeAddress(text.substring(0, at), text.substring(at + 1, colon),
                Integer.parseInt(text.substring(colon + 1)));
    }

    public static String join(List<NodeAddress> nodes) {
        return nodes.stream().map(NodeAddress::toString).collect(Collectors.joining(","));
    }

    public static List<NodeAddress> parseList(String text) {
        List<NodeAddress> nodes = new ArrayList<>();
        if (text != null && !text.isEmpty()) {
            for (String part : text.split(",")) {
                nodes.add(parse(part));
            }
        }
        return nodes;
    }
}
