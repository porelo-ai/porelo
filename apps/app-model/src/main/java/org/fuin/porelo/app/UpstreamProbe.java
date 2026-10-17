package org.fuin.porelo.app;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.Repository;
import org.fuin.porelo.gate.GitCredentials;
import org.jspecify.annotations.Nullable;

/**
 * Reads an upstream the way the gate's clone will read it, and says why it cannot.
 *
 * <p><strong>Asked before anything is created.</strong> A key the forge refused surfaced from the
 * gate's {@code git clone --bare} as "no git gate for this task" with git's own words, after the
 * task had been half made, and the start then offered a shell that had an empty workspace and fixed
 * nothing about the key (found on 2026-10-13). So the start asks first - through the very lease the
 * clone takes, in the process that will clone, so the answer is the clone's - and names the cause
 * with what fixes it.
 */
public final class UpstreamProbe {

    /**
     * How long a successful read counts. A start dialog asks again every few seconds while a person
     * chooses, and each ask reached the network. A refusal is never kept: the next ask after a fix
     * must see it.
     */
    static final java.time.Duration REMEMBERED = java.time.Duration.ofSeconds(30);

    /** When each upstream was last read, by mirror and address. */
    private static final java.util.concurrent.ConcurrentHashMap<String, java.time.Instant> READ =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * How long ssh may take to connect, so an unreachable host costs a start seconds, not minutes.
     */
    static final int CONNECT_SECONDS = 10;

    private UpstreamProbe() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** Why an upstream could not be read. */
    public enum Cause {

        /** ssh has never met the host on that port. {@code trust-host} answers it. */
        UNKNOWN_HOST_KEY,

        /** The host refused what was offered: no key it accepts, or a token it does not. */
        KEY_REFUSED,

        /** The host answered and has no such repository, or none this credential may read. */
        NOT_FOUND,

        /** Nothing answered: no route, no such name, a timeout. */
        UNREACHABLE,

        /** Anything else; the detail carries git's own last line. */
        UNREADABLE
    }

    /**
     * Why an upstream could not be read, and what fixes it.
     *
     * @param cause The cause.
     * @param detail Prose for a person: what was tried and what happened.
     * @param fix What to type, or "" when nothing on this machine helps.
     */
    public record Answer(Cause cause, String detail, String fix) {

        /**
         * Returns this as plain values, for a wire.
         *
         * @return cause, detail and fix.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("cause", cause.name());
            map.put("detail", detail);
            map.put("fix", fix);
            return map;
        }
    }

    /**
     * What a start would find when it reads a repository's upstream.
     *
     * @param answer Why it cannot be read.
     * @param refuses Whether the start must stop: there is no mirror yet, and the clone needs the
     *     upstream. With a mirror the start goes on from what it holds, as its refresh already
     *     allows, and this is a warning.
     */
    public record Finding(Answer answer, boolean refuses) {}

    /**
     * Reads the upstream a gated start of one repository would clone or refresh from.
     *
     * @param context The machine.
     * @param project The project.
     * @param repository The repository.
     * @param upstream The address the gate will use: {@code --upstream} when given, else the
     *     repository's own.
     * @return What is in the way, or {@code null} when it was read or there is nothing to ask - no
     *     upstream, as in an {@code offline} project, or a local path.
     */
    public static @Nullable Finding forStart(
            final PoreloContext context,
            final Project project,
            final Repository repository,
            final @Nullable String upstream) {
        final String address =
                upstream != null && !upstream.isBlank() ? upstream : repository.upstream();
        if (address == null || address.isBlank() || !remote(address)) {
            return null;
        }
        // An offline project names no upstream, and takes no --upstream: it never gets here.
        final boolean mirrored = mirrored(context, project, repository);
        // By the mirror's path, which names the account, the project and the repository, and by the
        // address.
        final String key = GateSupport.mirror(context, project, repository) + " " + address;
        final java.time.Instant read = READ.get(key);
        if (read != null && read.plus(REMEMBERED).isAfter(java.time.Instant.now())) {
            return null;
        }
        final Answer answer =
                probe(
                        context,
                        GateSupport.credentials(context, project.name(), repository.name()),
                        address);
        if (answer == null) {
            READ.put(key, java.time.Instant.now());
        } else {
            READ.remove(key);
        }
        return answer == null ? null : new Finding(answer, !mirrored);
    }

    /**
     * Tells whether a repository's mirror is there, so a start can go on from it whatever the
     * upstream says.
     *
     * @param context The machine.
     * @param project The project.
     * @param repository The repository.
     * @return {@code true} when the mirror holds objects.
     */
    public static boolean mirrored(
            final PoreloContext context, final Project project, final Repository repository) {
        return Files.isDirectory(
                GateSupport.mirror(context, project, repository).resolve("objects"));
    }

    /**
     * Reads an upstream through a lending, as the gate does.
     *
     * @param context The machine.
     * @param lending What lends the credential - the gate's own for a start.
     * @param url The upstream.
     * @return Why it cannot be read, or {@code null} when it was read.
     */
    public static @Nullable Answer probe(
            final PoreloContext context, final GitCredentials lending, final String url) {
        final CommandResult asked;
        try (GitCredentials.Lease lease = lending.forUrl(url)) {
            final List<String> command = new ArrayList<>(List.of("git"));
            command.addAll(lease.arguments());
            command.addAll(List.of("ls-remote", "--heads", "--end-of-options", url));
            asked =
                    context.runner()
                            .run(Command.of(command).withEnvironment(bounded(lease.environment())));
        } catch (final RuntimeException ex) {
            return new Answer(
                    Cause.UNREADABLE,
                    "the credential for " + url + " could not be lent: " + ex.getMessage(),
                    "");
        }
        if (asked.successful()) {
            return null;
        }
        return answer(context, url, asked.standardError() + "\n" + asked.standardOutput());
    }

    /**
     * Never a prompt, and never a wait without end: a start has no terminal for ssh to ask on, and
     * a host that does not answer must cost seconds.
     */
    static Map<String, String> bounded(final Map<String, String> lent) {
        final Map<String, String> environment = new LinkedHashMap<>(lent);
        final String ssh = environment.getOrDefault("GIT_SSH_COMMAND", "ssh");
        environment.put(
                "GIT_SSH_COMMAND",
                ssh.replaceFirst(
                        "^ssh(\\s|$)",
                        "ssh -o BatchMode=yes -o ConnectTimeout=" + CONNECT_SECONDS + "$1"));
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GIT_HTTP_LOW_SPEED_LIMIT", "1");
        environment.put("GIT_HTTP_LOW_SPEED_TIME", String.valueOf(CONNECT_SECONDS));
        return environment;
    }

    /**
     * Sorts what git and ssh said into a cause, and says what fixes it.
     *
     * @param context The machine, for the declaration that was used.
     * @param url The upstream.
     * @param said What git and ssh wrote.
     * @return The answer.
     */
    static Answer answer(final PoreloContext context, final String url, final String said) {
        final String host = HostKeys.nameOf(url);
        if (HostKeys.refusedTheHost(said)) {
            return HostKeys.changed(said)
                    ? new Answer(
                            Cause.UNKNOWN_HOST_KEY,
                            host
                                    + " answered with a different key from the one this"
                                    + " machine remembers - nothing was read; find out why before trusting it again",
                            "")
                    : new Answer(
                            Cause.UNKNOWN_HOST_KEY,
                            "this machine has never met " + host,
                            "porelo credentials trust-host " + host);
        }
        final String lower = said.toLowerCase(Locale.ROOT);
        if (lower.contains("repository not found")
                || lower.contains("does not appear to be a git repository")
                || lower.contains("could not be found")
                || lower.contains("does not exist")
                || lower.contains("error: 404")
                || lower.contains(" 404 ")) {
            return new Answer(
                    Cause.NOT_FOUND,
                    host
                            + " has no repository at "
                            + url
                            + ", or none the credential that was offered may read",
                    "");
        }
        if (lower.contains("permission denied")
                || lower.contains("authentication failed")
                || lower.contains("could not read username")
                || lower.contains("could not read password")
                || lower.contains("error: 401")
                || lower.contains("error: 403")
                || lower.contains(" 401 ")
                || lower.contains(" 403 ")) {
            return refused(context, url, host);
        }
        if (lower.contains("could not resolve")
                || lower.contains("timed out")
                || lower.contains("no route to host")
                || lower.contains("connection refused")
                || lower.contains("network is unreachable")
                || lower.contains("failed to connect")
                || lower.contains("operation too slow")) {
            return new Answer(
                    Cause.UNREACHABLE,
                    host + " could not be reached from here: " + lastLine(said),
                    "");
        }
        return new Answer(Cause.UNREADABLE, url + " could not be read: " + lastLine(said), "");
    }

    private static Answer refused(
            final PoreloContext context, final String url, final String host) {
        final CredentialDeclarations.Check check =
                new CredentialDeclarations(context).check(url, "git");
        final org.fuin.porelo.core.credential.Credential used = check.credential();
        final boolean overSsh = GitCredentialNames.kindOf(url) == GitCredentialNames.Kind.KEY;
        // Said for every case: the daemon has no ssh agent, so what works at a terminal may not
        // work through it.
        final String daemon =
                overSsh
                        ? " Through the daemon only a key in the vault, or one without a passphrase,"
                                + " works."
                        : "";
        if (check.outcome() == CredentialDeclarations.Outcome.VAULT_LOCKED) {
            return new Answer(
                    Cause.KEY_REFUSED,
                    host
                            + " refused this machine: the vault is shut, so "
                            + (used == null ? "what it holds" : "'" + used.id() + "'")
                            + " was not offered.",
                    "porelo vault unlock");
        }
        if (used == null || check.outcome() != CredentialDeclarations.Outcome.READY) {
            final String offered =
                    overSsh
                            ? "nothing is stored for it, so ssh offered only its default key files and, at a terminal, your"
                                    + " ssh agent"
                            : "nothing is stored for it, so no token was offered";
            return new Answer(
                    Cause.KEY_REFUSED,
                    host + " refused this machine: " + offered + "." + daemon,
                    check.storeCommand().isEmpty()
                            ? GitCredentialNames.storeCommandFor(url)
                            : check.storeCommand());
        }
        final String which =
                used.source() == org.fuin.porelo.core.credential.Credential.Source.VAULT
                        ? "the vault's '" + used.id() + "'"
                        : used.id();
        return new Answer(
                Cause.KEY_REFUSED,
                host
                        + " refused "
                        + which
                        + ", which is what is declared for "
                        + url
                        + ". Add its public half to your account there, or store the key it does accept."
                        + daemon,
                CredentialDeclarations.storeCommandFor(used));
    }

    /**
     * Tells whether git reaches an address over the network, rather than on this machine's disk.
     *
     * @param address The upstream.
     * @return {@code true} for a URL with a scheme other than {@code file} and for {@code
     *     [user@]host:path}.
     */
    public static boolean remote(final String address) {
        final String said = address.strip();
        if (said.contains("://")) {
            return !said.startsWith("file://");
        }
        return HostKeys.Address.ofUpstream(said) != null;
    }

    private static String lastLine(final String said) {
        return said.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .reduce((first, second) -> second)
                .orElse("git said nothing");
    }
}
