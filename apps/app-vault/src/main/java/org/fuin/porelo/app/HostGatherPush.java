package org.fuin.porelo.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.vault.VaultException;
import org.fuin.porelo.vault.VaultFile;
import org.fuin.porelo.wire.RootOwnedFile;
import picocli.CommandLine;

/** The person's noninteractive gathered release, shared with the host command. */
public final class HostGatherPush {
    /** Completed remote writes and confirmed unchanged repositories, in selection order. */
    public record Result(List<String> pushed, List<String> upToDate) {
        public Result {
            pushed = List.copyOf(pushed);
            upToDate = List.copyOf(upToDate);
        }
    }

    /** A fixed stage word and a validated repository name; never process output. */
    @FunctionalInterface
    public interface Progress {
        void stage(String repository, String stage) throws IOException;
    }

    final PoreloContext context;
    java.nio.file.Path forgeConf = HostForgeTokenCommand.FORGE_CONF;
    org.fuin.porelo.wire.RootOwnedFile.Attributes attributes = HostCentral.ATTRIBUTES;
    java.util.function.UnaryOperator<java.nio.file.Path> taken =
            org.fuin.porelo.wire.SystemProgram::at;
    java.util.function.Function<String, java.nio.file.Path> found =
            org.fuin.porelo.wire.SystemProgram::find;
    java.util.function.BooleanSupplier terminal = () -> false;
    java.util.function.LongSupplier uid = org.fuin.porelo.core.process.SocketPeer::ownUid;
    HostGatherFiles files = new HostGatherFiles();
    java.util.function.Supplier<java.util.Optional<VaultFile.Opener>> opener;

    public HostGatherPush(PoreloContext context) {
        this.context = context;
        opener = context::opener;
    }

    public Result push(
            String project, List<String> repositories, Progress progress, BooleanSupplier open)
            throws IOException {
        // A daemon never consults terminal availability, including inside the token issuer.
        final Result result =
                run(
                        false,
                        project,
                        repositories,
                        new PrintWriter(Writer.nullWriter()),
                        new PrintWriter(Writer.nullWriter()),
                        progress,
                        open,
                        false);
        if (result.pushed().isEmpty())
            throw new HostGatherRefusal(
                    HostGatherRefusal.Kind.NOTHING_TO_PUSH,
                    0,
                    "all selected repositories are up to date",
                    Map.of("pushed", result.pushed(), "upToDate", result.upToDate()));
        return result;
    }

    Result run(
            boolean pull,
            String project,
            List<String> repositories,
            PrintWriter out,
            PrintWriter err,
            Progress progress,
            BooleanSupplier open,
            boolean asking)
            throws IOException {
        char[] central = new char[0];
        boolean prompted = false;
        String credential = "";
        try {
            if (!project.matches("[a-z0-9]([a-z0-9-]{0,38}[a-z0-9])?"))
                throw HostGatherRefusal.host(
                        "selection", 64, "'" + project + "' is not a project name");
            final var seen = new java.util.HashSet<String>();
            for (String name : repositories) {
                if (!HostGatherConfig.repositoryName(name))
                    throw HostGatherRefusal.host("selection", 64, "invalid repository name");
                if (!seen.add(name))
                    throw HostGatherRefusal.host("selection", 64, "a repository is named twice");
            }
            final long caller = uid.getAsLong();
            if (caller == 0)
                throw HostGatherRefusal.host(
                        "account", 77, "push and pull run in the person's own account");
            final Settings settings = settings();
            final Path git = program("git");
            final Path curl = pull ? git : program("curl");
            program("getent");
            program("sudo");
            try {
                taken.apply(HostForgeTokenCommand.HOST_PROGRAM);
            } catch (UncheckedIOException | IllegalArgumentException ex) {
                throw HostGatherRefusal.host(
                        "program",
                        69,
                        "the host token issuer is not a program root alone can change");
            }
            final String key = settings.name() + ".central";
            credential = key;
            final VaultState state = vaultBeforePrograms(key, asking);
            if (state.opener().isPresent()) central = central(state.entries(), key);
            final Path home = home(caller);
            final Path source = home.resolve(".config/porelo-host/push/" + project + ".conf");
            HostGatherConfig config =
                    HostGatherConfig.parse(
                            source,
                            files.read(home, caller, project),
                            project,
                            "http://127.0.0.1:" + settings.port());
            config.select(repositories);
            try {
                final Path ca =
                        org.fuin.porelo.wire.RootOwnedAuthority.resolve(
                                config.ca(), Path.of("/"), 0, attributes);
                config =
                        new HostGatherConfig(
                                config.local(), config.owner(), ca, config.repositories());
            } catch (IOException | IllegalArgumentException ex) {
                throw HostGatherRefusal.host(
                        "authority",
                        66,
                        "the central forge's CA is not a file only root can change");
            }
            if (state.opener().isEmpty()) {
                prompted = true;
                final var opened = context.openerAsking(false, err);
                if (opened.isEmpty())
                    throw new HostGatherRefusal(
                            69, "the vault is shut; run 'porelo vault unlock' first");
                central = central(context.vault().read(opened.get()), key);
            }
            config.refuseCredentials(central, new char[0]);
            final StringWriter issuerErrors = new StringWriter();
            final HostForgeTokenCommand issuer = new HostForgeTokenCommand();
            issuer.setContext(context);
            issuer.forgeConf = forgeConf;
            issuer.attributes = attributes;
            issuer.taken = taken;
            issuer.found = found;
            issuer.terminal = () -> asking && terminal.getAsBoolean();
            final HostGatherConfig selected = config;
            final char[] centralToken = central;
            return FileLocks.holdingIfFree(
                            lock(context),
                            () -> {
                                if (!open.getAsBoolean()) throw cancelled();
                                try (HostGatherToken received = new HostGatherToken()) {
                                    issuer.received = received;
                                    final int issued =
                                            new CommandLine(issuer)
                                                    .setErr(new PrintWriter(issuerErrors))
                                                    .setExecutionExceptionHandler(
                                                            (failure, line, result) -> {
                                                                line.getErr()
                                                                        .println(
                                                                                "porelo: the person's forge token could not be stored; inspect the vault and retry");
                                                                return 70;
                                                            })
                                                    .execute();
                                    final char[] token = received.value();
                                    if (issued != 0 && (issued != 65 || token.length == 0)) {
                                        final String words =
                                                HostGatherFlow.redact(
                                                        issuerErrors.toString(),
                                                        centralToken,
                                                        token);
                                        err.print(
                                                words.isBlank()
                                                        ? "porelo: the person's forge token could not be made\n"
                                                        : words);
                                        throw new HostGatherRefusal(
                                                HostGatherRefusal.Kind.HOST_TOKEN,
                                                issued,
                                                "",
                                                Map.of());
                                    }
                                    if (issued == 65)
                                        err.println(
                                                "porelo: your vault took no new forge token; "
                                                        + settings.name()
                                                        + ".person works again after your next push or pull with your vault open");
                                    selected.refuseCredentials(centralToken, token);
                                    return new HostGatherFlow(
                                                    context.runner()
                                                                    instanceof
                                                                    org.fuin.porelo.core.process
                                                                            .ProcessCommandRunner
                                                            ? (asking
                                                                    ? new HostGatherRunner()
                                                                    : new HostGatherRunner(
                                                                            java.time.Duration
                                                                                    .ofMinutes(10),
                                                                            open))
                                                            : context.runner(),
                                                    git,
                                                    curl,
                                                    Path.of(System.getProperty("java.io.tmpdir")))
                                            .transfer(
                                                    pull,
                                                    selected,
                                                    repositories,
                                                    token,
                                                    centralToken,
                                                    out,
                                                    err,
                                                    (repository, stage) -> {
                                                        if (!open.getAsBoolean()) throw cancelled();
                                                        progress.stage(repository, stage);
                                                    });
                                }
                            })
                    .orElseThrow(
                            () ->
                                    new HostGatherRefusal(
                                            HostGatherRefusal.Kind.HOST_TOKEN,
                                            69,
                                            "another forge token issuance or gathered transfer is running",
                                            Map.of("reason", "busy")));
        } catch (HostGatherRefusal ex) {
            final HostGatherRefusal safe = ex.redacted(central, new char[0]);
            if (safe.kind() == HostGatherRefusal.Kind.CENTRAL_TOKEN_REFUSED)
                throw safe.withDetails(Map.of("credential", credential, "storeCommand", List.of()));
            throw safe;
        } catch (VaultException ex) {
            throw vaultShut(65, "the vault refused the central forge token; unlock it and retry");
        } catch (IOException ex) {
            throw HostGatherRefusal.host(
                    "configuration", 66, "the person's push configuration could not be read");
        } finally {
            Arrays.fill(central, '\0');
            if (prompted) context.forgetTyped();
            err.flush();
            out.flush();
        }
    }

    private record VaultState(
            java.util.Optional<VaultFile.Opener> opener,
            java.util.Map<String, VaultEntry> entries) {}

    private VaultState vaultBeforePrograms(String key, boolean asking) {
        if (!context.vault().exists())
            throw new HostGatherRefusal(
                    HostGatherRefusal.Kind.VAULT_SHUT,
                    69,
                    "there is no vault; run 'porelo vault init' first",
                    Map.of("unlockCommand", List.of("porelo", "vault", "init")));
        final var opened = opener.get();
        if (opened.isPresent()) {
            try {
                final VaultEntry entry = context.vault().read(opened.get()).get(key);
                return new VaultState(
                        opened, entry == null ? java.util.Map.of() : java.util.Map.of(key, entry));
            } catch (VaultException ex) {
                if ((!asking || !terminal.getAsBoolean()) || !context.vault().hasPassphrase())
                    throw ex;
                context.forgetTyped();
                final var forgotten =
                        new org.fuin.porelo.vault.KernelKeyring(
                                        context.paths().vault().vaultKeyringKey())
                                .forget();
                if (forgotten == org.fuin.porelo.vault.KernelKeyring.Forgotten.UNKNOWN)
                    throw new HostGatherRefusal(
                            69,
                            "the stale passphrase could not be forgotten; run 'porelo vault unlock --forget' and retry");
            }
        }
        if (context.vault().isHostVault())
            throw new HostGatherRefusal(
                    HostGatherRefusal.Kind.VAULT_SHUT,
                    69,
                    "the host vault is shut; run 'porelo host unlock <host>' from your own computer first",
                    Map.of("unlockCommand", List.of("porelo", "host", "unlock", "<host>")));
        if (!context.vault().hasPassphrase())
            throw new HostGatherRefusal(
                    HostGatherRefusal.Kind.VAULT_SHUT,
                    69,
                    "the vault is shut; unlock it with an enrolled device",
                    Map.of("unlockCommand", List.of()));
        if ((!asking || !terminal.getAsBoolean()))
            throw vaultShut("the vault is shut; run 'porelo vault unlock' first");
        return new VaultState(java.util.Optional.empty(), java.util.Map.of());
    }

    private static char[] central(java.util.Map<String, VaultEntry> entries, String key) {
        final VaultEntry entry = entries.get(key);
        if (entry == null
                || entry.value().isEmpty()
                || entry.value().chars().anyMatch(value -> value < 32 || value == 127))
            throw new HostGatherRefusal(
                    HostGatherRefusal.Kind.MISSING_CENTRAL_TOKEN,
                    69,
                    "the vault holds no valid central token at '"
                            + key
                            + "'; store it with 'porelo vault put "
                            + key
                            + "'",
                    Map.of(
                            "credential",
                            key,
                            "storeCommand",
                            List.of("porelo", "vault", "put", key)));
        return entry.value().toCharArray();
    }

    private Path program(String name) {
        try {
            return found.apply(name);
        } catch (UncheckedIOException | IllegalArgumentException ex) {
            throw HostGatherRefusal.host(
                    "program",
                    69,
                    name
                            + " is not a program root alone can change; install or repair its system package");
        }
    }

    private Path home(long caller) {
        final org.fuin.porelo.core.process.CommandResult result;
        try {
            result =
                    HostGatherRunner.system(
                            context.runner(),
                            Command.of(
                                            program("getent").toString(),
                                            "passwd",
                                            Long.toString(caller))
                                    .in(Path.of("/"))
                                    .withChosenEnvironment(
                                            java.util.Map.of(
                                                    "PATH", "/usr/sbin:/usr/bin", "LC_ALL", "C")));
        } catch (CommandException | HostGatherRefusal ex) {
            throw HostGatherRefusal.host(
                    "account", 69, "account " + caller + " is not in getent passwd");
        }
        final String[] fields = result.standardOutput().strip().split(":", -1);
        if (!result.successful()
                || fields.length != 7
                || !fields[2].equals(Long.toString(caller))
                || fields[0].isEmpty()
                || !fields[5].startsWith("/")
                || fields[5].equals("/")
                || result.standardOutput().lines().count() != 1)
            throw HostGatherRefusal.host(
                    "account", 69, "getent passwd gave no home directory for account " + caller);
        return Path.of(fields[5]);
    }

    private record Settings(String name, int port) {}

    private Settings settings() {
        final String text;
        try {
            text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .decode(
                                    ByteBuffer.wrap(
                                            RootOwnedFile.read(forgeConf, 65536, attributes, 0)))
                            .toString();
        } catch (IOException | IllegalArgumentException ex) {
            throw HostGatherRefusal.host(
                    "settings",
                    69,
                    "the forge settings are not a plain file root alone can change");
        }
        String name = "", account = "", port = "";
        int accounts = 0;
        for (String raw : text.split("\n", -1)) {
            final String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.startsWith("name=")) name = line.substring(5);
            if (line.startsWith("account=")) {
                account = line.substring(8);
                accounts++;
            }
            if (line.startsWith("git_port=")) port = line.substring(9);
        }
        if (accounts != 1 || !account.matches("[a-z_][a-z0-9_-]{0,31}"))
            throw HostGatherRefusal.host(
                    "settings", 69, "the forge settings name no single valid forge account");
        if (!name.matches("[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?"))
            throw HostGatherRefusal.host("settings", 69, "the forge settings name no valid forge");
        if (!port.matches("[1-9][0-9]{0,4}") || Integer.parseInt(port) > 65535)
            throw HostGatherRefusal.host("settings", 69, forgeConf + " names no valid git_port");
        return new Settings(name, Integer.parseInt(port));
    }

    static Path lock(PoreloContext context) {
        return context.paths().xdg().state().resolve("host-forge-token.lock");
    }

    private HostGatherRefusal vaultShut(String words) {
        return vaultShut(69, words);
    }

    private HostGatherRefusal vaultShut(int code, String words) {
        try {
            final List<String> command =
                    context.vault().isHostVault()
                            ? List.of("porelo", "host", "unlock", "<host>")
                            : context.vault().hasPassphrase()
                                    ? List.of("porelo", "vault", "unlock")
                                    : List.of();
            return new HostGatherRefusal(
                    HostGatherRefusal.Kind.VAULT_SHUT,
                    code,
                    words,
                    Map.of("unlockCommand", command));
        } catch (VaultException ex) {
            return HostGatherRefusal.host("vault", code, words);
        }
    }

    private static HostGatherRefusal cancelled() {
        return new HostGatherRefusal(
                HostGatherRefusal.Kind.CANCELLED,
                1,
                "gathered transfer stopped; a started push may have reached central",
                Map.of());
    }
}
