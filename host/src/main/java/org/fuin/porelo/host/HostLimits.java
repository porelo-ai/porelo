package org.fuin.porelo.host;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.fuin.porelo.wire.LimitFactors;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * The host configuration root's role writes, never a person's file: the four factors porelo-host's
 * time limits follow, and how many days a task certificate and a lent token hold.
 *
 * @param factors The factors every time limit of porelo-host follows.
 * @param certificateDays How many days a task certificate holds.
 * @param lentTokenDays How many days a lent token is kept.
 */
record HostLimits(LimitFactors factors, int certificateDays, int lentTokenDays) {
    /** The host configuration the role writes. */
    static final String FILE = "/etc/porelo-host/host.yml";

    /** The key holding the limits line. */
    static final String LIMITS = "limits";

    /** The key holding how many days a task certificate holds. */
    static final String CERTIFICATE_DAYS = "certificate_days";

    /** The key holding how many days a lent token is kept. */
    static final String LENT_TOKEN_DAYS = "lent_token_days";

    /** A task certificate's days when the file names none. */
    static final int DEFAULT_CERTIFICATE_DAYS = 7;

    /** A lent token's days when the file names none. */
    static final int DEFAULT_LENT_TOKEN_DAYS = 1;

    private static final int MOST_DAYS = 3650;

    /** What a host the role has not yet written the file on keeps. */
    static final HostLimits DEFAULT =
            new HostLimits(LimitFactors.DEFAULT, DEFAULT_CERTIFICATE_DAYS, DEFAULT_LENT_TOKEN_DAYS);

    /**
     * Reads the host configuration once for a run of porelo-host; a missing file, or a key left
     * out, keeps its default.
     *
     * @param files The host's file tree.
     * @return What the file names.
     * @throws HostRefusal With exit code 69, naming the file and what is wrong, when it is there
     *     but not a plain file of root's alone, unreadable, no YAML mapping, holds another key, its
     *     limits line is refused, or a day count is no whole number from 1 to 3650.
     */
    static HostLimits read(HostFiles files) {
        final Path path = files.path(FILE);
        final String text;
        try {
            if (!files.check(path, 69, false, false, -1)) {
                return DEFAULT;
            }
            text = files.read(path);
        } catch (HostRefusal ex) {
            throw refused(
                    path,
                    java.util.Objects.requireNonNull(
                            ex.getMessage(), "HostRefusal always has a safe diagnostic"));
        }
        final Object document;
        try {
            document = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
        } catch (YAMLException ex) {
            throw refused(path, path + " is not YAML");
        }
        if (!(document instanceof Map<?, ?> map)) {
            throw refused(path, path + " holds no mapping of " + KEYS);
        }
        for (Object key : map.keySet()) {
            if (!KEYS.contains(key)) {
                throw refused(path, path + " holds the key " + key + "; it holds only " + KEYS);
            }
        }
        LimitFactors factors = LimitFactors.DEFAULT;
        if (map.containsKey(LIMITS)) {
            if (!(map.get(LIMITS) instanceof String line)) {
                throw refused(path, path + ": " + LIMITS + " is no limits line");
            }
            try {
                factors = LimitFactors.parse(line);
            } catch (IllegalArgumentException ex) {
                throw refused(path, path + ": " + LIMITS + ": " + ex.getMessage());
            }
        }
        return new HostLimits(
                factors,
                days(path, map, CERTIFICATE_DAYS, DEFAULT_CERTIFICATE_DAYS),
                days(path, map, LENT_TOKEN_DAYS, DEFAULT_LENT_TOKEN_DAYS));
    }

    private static final java.util.List<String> KEYS =
            java.util.List.of(LIMITS, CERTIFICATE_DAYS, LENT_TOKEN_DAYS);

    private static int days(Path path, Map<?, ?> map, String key, int otherwise) {
        if (!map.containsKey(key)) {
            return otherwise;
        }
        // A fraction, a quoted number, a boolean and a number past int all arrive as another type.
        if (!(map.get(key) instanceof Integer days) || days < 1 || days > MOST_DAYS) {
            throw refused(path, path + ": " + key + " is no whole number from 1 to " + MOST_DAYS);
        }
        return days;
    }

    /**
     * Returns a limit in whole seconds, rounded up, for a program that takes no finer unit.
     *
     * @param limit The limit.
     * @return Its seconds, at least 1.
     */
    static long seconds(Duration limit) {
        final long whole = limit.getSeconds() + (limit.getNano() > 0 ? 1 : 0);
        return Math.max(1, whole);
    }

    /**
     * Returns a limit as systemd writes a time span: whole seconds when it is, otherwise
     * milliseconds.
     *
     * @param limit The limit.
     * @return Its span, such as {@code 2} or {@code 2500ms}.
     */
    static String span(Duration limit) {
        final long millis = limit.toMillis();
        return millis % 1000 == 0 ? Long.toString(millis / 1000) : millis + "ms";
    }

    private static HostRefusal refused(Path path, String what) {
        final String message = what.contains(path.toString()) ? what : path + ": " + what;
        return new HostRefusal(69, "porelo-host: cannot take the host's time factors: " + message);
    }
}
