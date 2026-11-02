package ro.ubm.sd.common;

/** Argument de linie de comandă lipsă sau invalid. Procesul iese cu codul 2. */
public class ConfigException extends Exception {
    public ConfigException(String message) {
        super(message);
    }
}
