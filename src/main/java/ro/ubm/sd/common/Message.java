package ro.ubm.sd.common;

import java.net.ProtocolException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Antetul unui mesaj: o linie de text {@code TIP cheie=valoare cheie=valoare}.
 * Valorile sunt codificate URL, deci pot conține spații și diacritice.
 * Același format circulă pe TCP (urmat eventual de octeții fișierului) și în pachetele UDP.
 */
public final class Message {
    private final String type;
    private final Map<String, String> fields = new LinkedHashMap<>();

    private Message(String type) {
        this.type = type;
    }

    public static Message of(String type) {
        return new Message(type);
    }

    public static Message error(String code, String text) {
        return of(Protocol.ERROR).with("code", code).with("message", text);
    }

    /** Adaugă un câmp; valorile null sunt omise. */
    public Message with(String key, Object value) {
        if (value != null) {
            fields.put(key, String.valueOf(value));
        }
        return this;
    }

    public String type() {
        return type;
    }

    public boolean is(String expected) {
        return type.equals(expected);
    }

    public String get(String key) {
        return fields.get(key);
    }

    public String get(String key, String defaultValue) {
        return fields.getOrDefault(key, defaultValue);
    }

    public String require(String key) throws ProtocolException {
        String value = fields.get(key);
        if (value == null) {
            throw new ProtocolException("lipseste campul '" + key + "' din " + type);
        }
        return value;
    }

    public long requireLong(String key) throws ProtocolException {
        String value = require(key);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new ProtocolException("campul '" + key + "' din " + type + " nu este numar: " + value);
        }
    }

    /** Textul unui ERROR, pentru afișare. */
    public String errorText() {
        return get("message", get("code", type));
    }

    public String encode() {
        StringBuilder sb = new StringBuilder(type);
        fields.forEach((key, value) ->
                sb.append(' ').append(key).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8)));
        return sb.toString();
    }

    public static Message decode(String line) throws ProtocolException {
        String[] parts = line.trim().split(" +");
        if (parts.length == 0 || parts[0].isEmpty()) {
            throw new ProtocolException("mesaj gol");
        }
        Message message = new Message(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq <= 0) {
                throw new ProtocolException("camp invalid in " + parts[0] + ": " + parts[i]);
            }
            try {
                message.fields.put(parts[i].substring(0, eq),
                        URLDecoder.decode(parts[i].substring(eq + 1), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw new ProtocolException("valoare codificata gresit in " + parts[0] + ": " + parts[i]);
            }
        }
        return message;
    }

    @Override
    public String toString() {
        return encode();
    }
}
