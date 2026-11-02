package ro.ubm.sd.common;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Parsează argumente de forma {@code --cheie valoare} și {@code --flag}. */
public final class Args {
    private final Map<String, String> values = new HashMap<>();
    private final Set<String> flags = new HashSet<>();

    private Args() {
    }

    /**
     * @param valueKeys opțiunile care primesc o valoare (fără "--")
     * @param flagKeys  opțiunile fără valoare
     */
    public static Args parse(String[] argv, Set<String> valueKeys, Set<String> flagKeys) throws ConfigException {
        Args args = new Args();
        for (int i = 0; i < argv.length; i++) {
            String arg = argv[i];
            if (!arg.startsWith("--")) {
                throw new ConfigException("argument neasteptat: " + arg);
            }
            String key = arg.substring(2);
            if (flagKeys.contains(key)) {
                args.flags.add(key);
            } else if (valueKeys.contains(key)) {
                if (i + 1 >= argv.length || argv[i + 1].startsWith("--")) {
                    throw new ConfigException("lipseste valoarea pentru " + arg);
                }
                if (args.values.put(key, argv[++i]) != null) {
                    throw new ConfigException(arg + " apare de doua ori");
                }
            } else {
                throw new ConfigException("optiune necunoscuta: " + arg);
            }
        }
        return args;
    }

    public String get(String key) {
        return values.get(key);
    }

    public String get(String key, String defaultValue) {
        return values.getOrDefault(key, defaultValue);
    }

    public String require(String key) throws ConfigException {
        String value = values.get(key);
        if (value == null) {
            throw new ConfigException("lipseste --" + key);
        }
        return value;
    }

    public boolean has(String key) {
        return flags.contains(key);
    }

    public static int port(String key, String value) throws ConfigException {
        try {
            int port = Integer.parseInt(value);
            if (port >= 1 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException ignored) {
            // raportat mai jos
        }
        throw new ConfigException("--" + key + " trebuie sa fie un port intre 1 si 65535, nu " + value);
    }
}
