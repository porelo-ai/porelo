package org.fuin.porelo.app;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Pattern;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.gittree.FetchAccess;
import org.jspecify.annotations.Nullable;

/**
 * What a fetch through a host's own gate uses: a forge token the host's helper makes for this
 * person, for that fetch alone. The gate answers no one without one, and no vault holds it.
 *
 * <p><strong>Only the root-owned host file's {@code fetch_from}, on this machine's
 * loopback.</strong> Any other address, a project's own or a redirect's, gets what the caller lends
 * otherwise. The token goes to git in its environment, with redirects off; what the helper or git
 * says never goes into a word of a refusal, because either can hold it.
 */
final class HostForgeAccess implements FetchAccess {

    private static final Pattern GATE =
            Pattern.compile("http://127\\.0\\.0\\.1:[1-9][0-9]{0,4}/[^\\s@?#]*");

    private static final Pattern ONE_TOKEN = Pattern.compile("\\S+");

    private final PoreloContext context;

    private final @Nullable String fetchFrom;

    private final FetchAccess otherwise;
    private final java.util.function.BooleanSupplier installed;

    /**
     * Constructor.
     *
     * @param context The machine.
     * @param fetchFrom The host file's {@code fetch_from}, or {@code null} when it names none.
     * @param otherwise What any other address is lent.
     */
    HostForgeAccess(
            final PoreloContext context,
            final @Nullable String fetchFrom,
            final FetchAccess otherwise) {
        this(
                context,
                fetchFrom,
                otherwise,
                () -> java.nio.file.Files.isRegularFile(HostPerson.PROGRAM));
    }

    HostForgeAccess(
            final PoreloContext context,
            final @Nullable String fetchFrom,
            final FetchAccess otherwise,
            final java.util.function.BooleanSupplier installed) {
        this.context = context;
        this.fetchFrom = fetchFrom;
        this.otherwise = otherwise;
        this.installed = installed;
    }

    /**
     * Tells whether a fetch of this address asks the host's helper for a token.
     *
     * @param address The address.
     * @return Whether it does.
     */
    boolean asksFor(final String address) {
        return address.equals(fetchFrom) && GATE.matcher(address).matches();
    }

    /**
     * The forge's own root, {@code http://127.0.0.1:<port>/}, of an address {@link #GATE} takes.
     */
    private static String forge(final String address) {
        return address.substring(0, address.indexOf('/', "http://".length()) + 1);
    }

    @Override
    public Lease lend(final String address) {
        if (!asksFor(address)) {
            return otherwise.lend(address);
        }
        final String header =
                "Authorization: Basic "
                        + Base64.getEncoder()
                                .encodeToString(
                                        ("x-access-token:" + token())
                                                .getBytes(StandardCharsets.UTF_8));
        return new Lease() {
            @Override
            public Map<String, String> environment() {
                return Map.of(
                        "GIT_CONFIG_COUNT", "2",
                        // Named for this forge alone: git's settings can rewrite an address
                        // after it was checked here, and the header must not follow it.
                        "GIT_CONFIG_KEY_0", "http." + forge(address) + ".extraHeader",
                        "GIT_CONFIG_VALUE_0", header,
                        "GIT_CONFIG_KEY_1", "http.followRedirects",
                        "GIT_CONFIG_VALUE_1", "false",
                        "GIT_ALLOW_PROTOCOL", "http");
            }
        };
    }

    private String token() {
        final CommandResult asked;
        try {
            asked =
                    context.runner()
                            .run(
                                    // Lent for this command, beside the tokens a running task
                                    // holds.
                                    HostForgeLending.onHost(
                                                    HostPerson.PROGRAM, "forge", "lend-token")
                                            .withInput(""));
        } catch (final CommandException ex) {
            requireInstalled();
            throw cannotSay();
        }
        if (asked.exitCode() == 65) {
            throw new Denied(
                    65,
                    OrganizationQuestions.Refused.REFUSED_STATE,
                    "the host refuses this person: locked or revoked");
        }
        final String out = asked.standardOutput();
        // One line end a helper may print after the token is no part of it.
        final String token = out.endsWith("\n") ? out.substring(0, out.length() - 1) : out;
        if (!asked.successful() || !ONE_TOKEN.matcher(token).matches()) {
            requireInstalled();
            throw cannotSay();
        }
        return token;
    }

    private void requireInstalled() {
        if (!installed.getAsBoolean()) {
            throw new Denied(
                    69,
                    OrganizationQuestions.Refused.CANNOT_SAY,
                    "the host's /usr/sbin/porelo-host is missing; install the porelo-host package");
        }
    }

    private static Denied cannotSay() {
        return new Denied(
                69,
                OrganizationQuestions.Refused.CANNOT_SAY,
                "the host's helper gave no token for its forge");
    }

    /** The host's helper gave no token: its words are not here, its output may hold one. */
    static final class Denied extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final int code;

        private final String reason;

        Denied(final int code, final String reason, final String words) {
            super(words);
            this.code = code;
            this.reason = reason;
        }

        /**
         * Returns the exit code.
         *
         * @return It.
         */
        int code() {
            return code;
        }

        /**
         * Returns the contract's reason.
         *
         * @return It.
         */
        String reason() {
            return reason;
        }
    }
}
