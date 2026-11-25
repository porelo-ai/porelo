package org.fuin.porelo.app;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * How git on this host reaches a forge with a token that was lent for one command: the token in
 * git's chosen environment under a variable, read back by {@code porelo vault credential --env}
 * through git's credential protocol, so the command line names only the variable.
 *
 * <p>Nothing else steers git: no system or account config, no hooks, no proxy, no prompt, and no
 * transport but the one named.
 */
public final class HostGitTransport {

    private HostGitTransport() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns git's arguments before its command, for a forge reached with a lent token.
     *
     * @param transport The one transport allowed, {@code http} or {@code https}.
     * @param scope Where the helper answers: the scheme, host and port git connects to.
     * @param variable The variable of git's environment that holds the token.
     * @param gitDir The repository git works in.
     * @return The arguments, starting with {@code git}.
     */
    public static List<String> arguments(
            final String transport, final String scope, final String variable, final Path gitDir) {
        Objects.requireNonNull(scope, "scope");
        if (!"http".equals(transport) && !"https".equals(transport)) {
            throw new IllegalArgumentException("a forge is reached over http or https");
        }
        return List.of(
                "git",
                "-c",
                "core.hooksPath=/dev/null",
                "-c",
                "protocol.allow=never",
                "-c",
                "protocol." + transport + ".allow=always",
                "-c",
                "http.proxy=",
                "-c",
                "credential.helper=",
                "-c",
                "credential."
                        + scope
                        + ".helper=!"
                        + word(PoreloBinary.path().toString())
                        + " vault credential --env "
                        + variable,
                "--git-dir",
                gitDir.toString());
    }

    /**
     * Returns git's environment, chosen rather than inherited: none of the account's or the
     * system's git config, no proxy, no trace, no prompt; HOME and PATH alone, for the helper.
     *
     * @return The variables.
     */
    public static Map<String, String> environment() {
        final Map<String, String> chosen = new LinkedHashMap<>();
        chosen.put("PATH", Objects.requireNonNullElse(System.getenv("PATH"), "/usr/bin:/bin"));
        final String home = System.getenv("HOME");
        if (home != null) {
            chosen.put("HOME", home);
        }
        chosen.put("LC_ALL", "C");
        chosen.put("GIT_CONFIG_NOSYSTEM", "1");
        chosen.put("GIT_CONFIG_GLOBAL", "/dev/null");
        chosen.put("GIT_ATTR_NOSYSTEM", "1");
        chosen.put("GIT_NO_REPLACE_OBJECTS", "1");
        chosen.put("GIT_PROTOCOL_FROM_USER", "0");
        chosen.put("GIT_TERMINAL_PROMPT", "0");
        return chosen;
    }

    /**
     * Returns a text with the token, and the Basic credential git makes of it, replaced.
     *
     * @param text What git or the forge said.
     * @param secret The lent token.
     * @return The text with {@code <token>} in their place.
     */
    public static String masked(final String text, final String secret) {
        final String basic =
                Base64.getEncoder()
                        .encodeToString(
                                ("x-access-token:" + secret).getBytes(StandardCharsets.UTF_8));
        return text.replace(basic, "<token>").replace(secret, "<token>");
    }

    private static String word(final String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
