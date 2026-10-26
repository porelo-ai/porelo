package org.fuin.porelo.app;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.fuin.porelo.core.process.Command;

/**
 * The environment the message filter and every transport start from: chosen, never the caller's
 * whole one.
 *
 * <p><strong>What the caller had does not decide what they do</strong> (decided on 2026-09-30).
 * Inherited, a {@code PORELO_MSGSLUICE_*} in the shell that started Porelo switched the filter's
 * checks off, and a {@code PORELO_MATRIX_TLS_VERIFY=off} switched a transport's certificate checks
 * off; an outer agent's session variables reached them too. They get what a process needs to find
 * its tools, its home and its account's user services - and exactly what Porelo hands them on top.
 */
public final class HelperEnvironment {

    /**
     * What passes from the caller: the search path, who and where the account is, its language, its
     * user bus.
     */
    static final List<String> PASSED =
            List.of(
                    "PATH",
                    "HOME",
                    "USER",
                    "LOGNAME",
                    "SHELL",
                    "LANG",
                    "LANGUAGE",
                    "LC_ALL",
                    "LC_CTYPE",
                    "LC_MESSAGES",
                    "TZ",
                    "TMPDIR",
                    "XDG_CONFIG_HOME",
                    "XDG_DATA_HOME",
                    "XDG_STATE_HOME",
                    "XDG_CACHE_HOME",
                    "XDG_RUNTIME_DIR",
                    "DBUS_SESSION_BUS_ADDRESS");

    private HelperEnvironment() {}

    /**
     * Returns a helper's command, starting from the chosen environment.
     *
     * @param command What runs the helper, with what Porelo hands it.
     * @return The same, with nothing else of the caller's.
     */
    public static Command chosen(Command command) {
        return command.withChosenEnvironment(base(System.getenv()));
    }

    /** What a helper gets as its locale when the caller has none. */
    static final String LOCALE = "C.UTF-8";

    /**
     * Returns what passes from an environment.
     *
     * <p><strong>A UTF-8 locale where the caller has none.</strong> Java reads a program's
     * arguments and file names by the locale, and in the C locale that is ASCII: a transport
     * enrolled with a name holding an umlaut read it back with a question mark. porelod gets its
     * locale from the user manager, which may have none. A locale the caller sets, even {@code C},
     * passes unchanged.
     *
     * @param caller The caller's environment.
     * @return Only the variables that pass, and {@code LANG} where no locale did.
     */
    static Map<String, String> base(Map<String, String> caller) {
        final Map<String, String> base = new LinkedHashMap<>();
        for (final String name : PASSED) {
            final String value = caller.get(name);
            if (value != null) {
                base.put(name, value);
            }
        }
        if (Stream.of("LANG", "LC_ALL", "LC_CTYPE")
                .map(base::get)
                .allMatch(value -> value == null || value.isEmpty())) {
            base.put("LANG", LOCALE);
        }
        return base;
    }
}
