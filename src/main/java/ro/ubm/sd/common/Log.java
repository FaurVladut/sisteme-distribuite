package ro.ubm.sd.common;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Jurnal pe consolă, cu timestamp la milisecundă: obiectivele din cerinte.md
 * (descoperire în cel mult 3 s etc.) se măsoară din aceste linii.
 */
public final class Log {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final String tag;

    public Log(String tag) {
        this.tag = tag;
    }

    public void info(String format, Object... args) {
        System.out.println(line(format, args));
    }

    public void error(String format, Object... args) {
        System.err.println(line(format, args));
    }

    private String line(String format, Object... args) {
        return LocalTime.now().format(TIME) + " [" + tag + "] " + String.format(Locale.ROOT, format, args);
    }
}
