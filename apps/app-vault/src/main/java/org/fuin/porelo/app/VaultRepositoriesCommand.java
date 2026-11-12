package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.fuin.porelo.supervisor.RepositoryProxy;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.vault.VaultException;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Serves one task's repository broker: the package repositories its project declares with a
 * credential, read with GET and HEAD under each one's prefix, the vault's credential added on the
 * host.
 *
 * <p>The credentials are read once, as the broker starts, and held by this process alone; none is
 * on its command line, in its output or in its log, which names each request by its method, its
 * path and the status.
 */
@Command(
        name = "repositories",
        mixinStandardHelpOptions = true,
        description = "Serves the package repository broker for one task on a unix socket.")
public class VaultRepositoriesCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    /** The kinds of vault entry a repository's credential may be. */
    public static final List<String> KINDS = List.of("basic", "bearer", "header");

    @Option(
            names = "--socket",
            paramLabel = "<file>",
            required = true,
            description = "Unix socket to bind, in an owner-only directory.")
    private Path socket;

    @Option(
            names = "--repository",
            paramLabel = "<name>=<credential>|<prefix>",
            required = true,
            description =
                    "A repository the task reads: its name, the vault entry of its credential, and"
                            + " the https prefix every request goes under. Repeatable.")
    private Map<String, String> repositories = new java.util.LinkedHashMap<>();

    @Option(
            names = "--pid-file",
            paramLabel = "<file>",
            required = true,
            description = "Where to write this process's id, so it is reaped with the task.")
    private Path pidFile;

    @Option(
            names = "--seconds",
            paramLabel = "<n>",
            hidden = true,
            description = "Serve this long and return; for tests.")
    private long seconds;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(PoreloContext context) {
        this.context = context;
    }

    /**
     * How a vault entry's credential goes on a request, or why it cannot.
     *
     * @param header The header it goes in, or {@code null} when refused.
     * @param value The header's value.
     * @param secrets What an answer must never carry.
     * @param refusal Why it cannot, or {@code null}.
     */
    record Credential(
            @Nullable String header, String value, List<String> secrets, @Nullable String refusal) {

        static Credential refused(String why) {
            return new Credential(null, "", List.of(), why);
        }

        @Override
        public String toString() {
            // Never the value.
            return refusal != null ? "Credential[refused]" : "Credential[" + header + "]";
        }
    }

    /**
     * Reads how a vault entry's credential goes on a request to a repository.
     *
     * <p>{@code basic}: the value is {@code user:password}, sent Base64-encoded behind {@code
     * Basic}. {@code bearer}: sent behind {@code Bearer}. {@code header}: sent as it is, in the
     * header its setting {@code header} names, such as {@code X-JFrog-Art-Api}. Each in the forms
     * an answer must not carry: as stored, Base64-encoded, and a basic's password alone.
     *
     * @param entry The vault entry.
     * @return How, or why not.
     */
    static Credential credential(VaultEntry entry) {
        final String value = entry.value();
        final String encoded =
                Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
        if (value.isEmpty() || value.chars().anyMatch(each -> each < 0x20 || each == 0x7f)) {
            return Credential.refused("holds no value a header can carry");
        }
        final String type = entry.type() == null ? "" : entry.type();
        return switch (type) {
            case "basic" -> {
                final int colon = value.indexOf(':');
                if (colon <= 0) {
                    yield Credential.refused("is of kind basic and holds no 'user:password'");
                }
                final List<String> secrets = new ArrayList<>(List.of(value, encoded));
                final String password = value.substring(colon + 1);
                // A short password alone would cut answers at every word it spells.
                if (password.length() >= 8) {
                    secrets.add(password);
                }
                yield new Credential("Authorization", "Basic " + encoded, secrets, null);
            }
            case "bearer" ->
                    new Credential(
                            "Authorization", "Bearer " + value, List.of(value, encoded), null);
            case "header" -> {
                final String header = entry.settings().get("header");
                if (header == null || !header.matches("[A-Za-z0-9-]{1,64}")) {
                    yield Credential.refused(
                            "is of kind header and names no header: store it with --setting"
                                    + " header=<name>");
                }
                yield new Credential(header, value, List.of(value, encoded), null);
            }
            default ->
                    Credential.refused(
                            "is no repository credential: store it with --type "
                                    + String.join(", ", KINDS.subList(0, 2))
                                    + " or "
                                    + KINDS.get(2));
        };
    }

    @Override
    public Integer call() throws Exception {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        // Before the first credential is read: a core dump of this process is a copy of each.
        org.fuin.porelo.core.hardening.ProcessHardening.disableDumping();
        final List<RepositoryProxy.Repository> served = new ArrayList<>();
        try {
            final Map<String, VaultEntry> entries = context.credentials();
            for (final Map.Entry<String, String> each : repositories.entrySet()) {
                final String name = each.getKey();
                final int bar = each.getValue().indexOf('|');
                if (bar <= 0) {
                    err.println("porelo: --repository " + name + " names no credential and prefix");
                    err.flush();
                    return 2;
                }
                final String credential = each.getValue().substring(0, bar);
                final String prefix = each.getValue().substring(bar + 1);
                if (!name.matches(org.fuin.porelo.core.project.ToolRepository.NAME_PATTERN)
                        || !org.fuin.porelo.core.project.ToolRepository.isPrefix(prefix)) {
                    err.println(
                            "porelo: --repository "
                                    + name
                                    + " is no repository name with an https prefix");
                    err.flush();
                    return 2;
                }
                final VaultEntry entry = entries.get(credential);
                if (entry == null) {
                    err.println(
                            "porelo: the vault holds no credential '"
                                    + credential
                                    + "' for the repository "
                                    + name);
                    err.flush();
                    return 2;
                }
                final Credential how = credential(entry);
                if (how.refusal() != null || how.header() == null) {
                    err.println(
                            "porelo: the credential '"
                                    + credential
                                    + "' for the repository "
                                    + name
                                    + " "
                                    + how.refusal());
                    err.flush();
                    return 2;
                }
                served.add(
                        new RepositoryProxy.Repository(
                                name, prefix, how.header(), how.value(), how.secrets()));
            }
        } catch (VaultException ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return 70;
        }
        // What opening the vault used is garbage now; collected once, before the broker serves.
        System.gc();
        try (RepositoryProxy proxy =
                        new RepositoryProxy(
                                socket,
                                served,
                                line -> {
                                    out.println("request   " + line);
                                    out.flush();
                                },
                                org.fuin.porelo.core.net.TrustedCertificates.file(
                                        context.paths().xdg()));
                // Closed by a signal too: a socket left behind reads as a live broker.
                Teardown closing = Teardown.arm(proxy::close)) {
            org.fuin.porelo.wire.HelperPid.record(pidFile);
            out.println("socket    " + socket);
            served.forEach(
                    repository ->
                            out.println(
                                    "repository "
                                            + repository.name()
                                            + " -> "
                                            + repository.prefix()));
            out.flush();
            final Thread serving = Thread.ofPlatform().start(proxy);
            if (seconds > 0) {
                serving.join(java.time.Duration.ofSeconds(seconds));
            } else {
                serving.join();
            }
        }
        return 0;
    }
}
