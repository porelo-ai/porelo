package org.fuin.porelo.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.vault.VaultException;
import org.fuin.porelo.vault.VaultFile;
import org.fuin.porelo.wire.RootOwnedFile;
import org.fuin.porelo.wire.SystemProgram;
import picocli.CommandLine;

/** Vault and system boundaries shared by the person's push and pull. */
abstract class HostGatherCommand implements Callable<Integer>, PoreloFactory.ContextAware {
    @CommandLine.Parameters(index = "0", paramLabel = "<project>")
    String project;

    @CommandLine.Parameters(index = "1..*", paramLabel = "<repository>")
    List<String> repositories = List.of();

    @CommandLine.Spec CommandLine.Model.CommandSpec spec;
    PoreloContext context = PoreloContext.real();
    Path forgeConf = HostForgeTokenCommand.FORGE_CONF;
    RootOwnedFile.Attributes attributes = HostCentral.ATTRIBUTES;
    UnaryOperator<Path> taken = SystemProgram::at;
    Function<String, Path> found = SystemProgram::find;
    BooleanSupplier terminal = Offer::atTerminal;
    LongSupplier uid = org.fuin.porelo.core.process.SocketPeer::ownUid;
    HostGatherFiles files = new HostGatherFiles();

    @Override
    public void setContext(PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final PrintWriter err = spec.commandLine().getErr();
        char[] central = new char[0];
        boolean prompted = false;
        try {
            if (!project.matches("[a-z0-9]([a-z0-9-]{0,38}[a-z0-9])?"))
                throw new HostGatherRefusal(64, "'" + project + "' is not a project name");
            final var seen = new java.util.HashSet<String>();
            for (String name : repositories) {
                if (!HostGatherConfig.repositoryName(name))
                    throw new HostGatherRefusal(64, "invalid repository name");
                if (!seen.add(name)) throw new HostGatherRefusal(64, "a repository is named twice");
            }
            final long caller = uid.getAsLong();
            if (caller == 0)
                throw new HostGatherRefusal(77, "push and pull run in the person's own account");
            final Settings settings = settings();
            final Path git = program("git");
            program("getent");
            program("sudo");
            try {
                taken.apply(HostForgeTokenCommand.HOST_PROGRAM);
            } catch (UncheckedIOException | IllegalArgumentException ex) {
                throw new HostGatherRefusal(
                        69, "the host token issuer is not a program root alone can change");
            }
            final String key = settings.name() + ".central";
            final VaultState state = vaultBeforePrograms(key);
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
                throw new HostGatherRefusal(
                        66, "the central forge's CA is not a file only root can change");
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
            issuer.terminal = terminal;
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
                            HostGatherFlow.redact(issuerErrors.toString(), central, token);
                    err.print(
                            words.isBlank()
                                    ? "porelo: the person's forge token could not be made\n"
                                    : words);
                    return issued;
                }
                if (issued == 65)
                    err.println(
                            "porelo: your vault took no new forge token; "
                                    + settings.name()
                                    + ".person works again after your next push or pull with your vault open");
                config.refuseCredentials(central, token);
                return new HostGatherFlow(
                                context.runner()
                                                instanceof
                                                org.fuin.porelo.core.process.ProcessCommandRunner
                                        ? new HostGatherRunner()
                                        : context.runner(),
                                git,
                                Path.of(System.getProperty("java.io.tmpdir")))
                        .run(
                                pulling(),
                                config,
                                repositories,
                                token,
                                central,
                                spec.commandLine().getOut(),
                                err);
            }
        } catch (HostGatherRefusal ex) {
            if (!java.util.Objects.requireNonNull(
                            ex.getMessage(), "Gathered refusals have safe words")
                    .isBlank())
                err.println(
                        "porelo: " + HostGatherFlow.redact(ex.getMessage(), central, new char[0]));
            return ex.code();
        } catch (VaultException ex) {
            err.println("porelo: the vault refused the central forge token; unlock it and retry");
            return 65;
        } catch (IOException ex) {
            err.println("porelo: the person's push configuration could not be read");
            return 66;
        } finally {
            Arrays.fill(central, '\0');
            if (prompted) context.forgetTyped();
            err.flush();
            spec.commandLine().getOut().flush();
        }
    }

    private record VaultState(
            java.util.Optional<VaultFile.Opener> opener,
            java.util.Map<String, VaultEntry> entries) {}

    private VaultState vaultBeforePrograms(String key) {
        if (!context.vault().exists())
            throw new HostGatherRefusal(69, "there is no vault; run 'porelo vault init' first");
        final var opened = context.opener();
        if (opened.isPresent()) {
            try {
                final VaultEntry entry = context.vault().read(opened.get()).get(key);
                return new VaultState(
                        opened, entry == null ? java.util.Map.of() : java.util.Map.of(key, entry));
            } catch (VaultException ex) {
                if (!terminal.getAsBoolean() || !context.vault().hasPassphrase()) throw ex;
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
                    69,
                    "the host vault is shut; run 'porelo host unlock <host>' from your own computer first");
        if (!context.vault().hasPassphrase())
            throw new HostGatherRefusal(69, "the vault is shut; unlock it with an enrolled device");
        if (!terminal.getAsBoolean())
            throw new HostGatherRefusal(69, "the vault is shut; run 'porelo vault unlock' first");
        return new VaultState(java.util.Optional.empty(), java.util.Map.of());
    }

    private static char[] central(java.util.Map<String, VaultEntry> entries, String key) {
        final VaultEntry entry = entries.get(key);
        if (entry == null
                || entry.value().isEmpty()
                || entry.value().chars().anyMatch(value -> value < 32 || value == 127))
            throw new HostGatherRefusal(
                    69,
                    "the vault holds no valid central token at '"
                            + key
                            + "'; store it with 'porelo vault put "
                            + key
                            + "'");
        return entry.value().toCharArray();
    }

    private Path program(String name) {
        try {
            return found.apply(name);
        } catch (UncheckedIOException | IllegalArgumentException ex) {
            throw new HostGatherRefusal(
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
            throw new HostGatherRefusal(69, "account " + caller + " is not in getent passwd");
        }
        final String[] fields = result.standardOutput().strip().split(":", -1);
        if (!result.successful()
                || fields.length != 7
                || !fields[2].equals(Long.toString(caller))
                || fields[0].isEmpty()
                || !fields[5].startsWith("/")
                || fields[5].equals("/")
                || result.standardOutput().lines().count() != 1)
            throw new HostGatherRefusal(
                    69, "getent passwd gave no home directory for account " + caller);
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
            throw new HostGatherRefusal(
                    69, "the forge settings are not a plain file root alone can change");
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
            throw new HostGatherRefusal(
                    69, "the forge settings name no single valid forge account");
        if (!name.matches("[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?"))
            throw new HostGatherRefusal(69, "the forge settings name no valid forge");
        if (!port.matches("[1-9][0-9]{0,4}") || Integer.parseInt(port) > 65535)
            throw new HostGatherRefusal(69, forgeConf + " names no valid git_port");
        return new Settings(name, Integer.parseInt(port));
    }

    abstract boolean pulling();
}
