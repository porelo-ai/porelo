package org.fuin.porelo.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.vault.VaultException;
import org.fuin.porelo.vault.VaultFile;
import org.fuin.porelo.wire.RootOwnedFile;
import org.fuin.porelo.wire.SystemProgram;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Mints a fresh forge token for this person on a host through {@code sudo -n /usr/sbin/porelo-host
 * forge issue-token} and stores it in this account's vault under {@code <forge>.person}.
 *
 * <p><strong>The token never touches a command line, log or exception message.</strong> It is read
 * from the child process's standard output as one bounded line and written straight into the vault,
 * opening the vault with a passphrase prompt only when it is shut and a terminal is present.
 */
@Command(
        name = "forge-token",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description =
                "Mints this person's forge token on the host and stores it in the vault under"
                        + " <forge>.person.")
public class HostForgeTokenCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    /** The root-owned host program, invoked by this absolute path only. */
    public static final Path HOST_PROGRAM = Path.of("/usr/sbin/porelo-host");

    /** Where the host's forge settings are published by root. */
    public static final Path FORGE_CONF = HostForgeSettings.FILE;

    private static final Pattern TOKEN_LINE = Pattern.compile("^[0-9a-f]{40}$");

    @Option(names = "--if-forge", description = "Does nothing when this host runs no local forge.")
    private boolean ifForge;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    /** Where the host's forge settings are; another path only in a test. */
    Path forgeConf = FORGE_CONF;

    /** Reads file ownership and mode for {@link #forgeConf}; another reader only in a test. */
    RootOwnedFile.Attributes attributes = HostCentral.ATTRIBUTES;

    /** The host program path; another path only in a test. */
    Path hostProgram = HOST_PROGRAM;

    /** Checks the host program before it runs; another check only in a test. */
    UnaryOperator<Path> taken = SystemProgram::at;

    /** Finds a checked system program; another finder only in a test. */
    Function<String, Path> found = SystemProgram::find;

    /** Whether a terminal is present to ask a passphrase on; another check only in a test. */
    BooleanSupplier terminal = Offer::atTerminal;

    /** A gathered transfer keeps the issued token even when its vault update fails. */
    java.util.function.Consumer<String> received = secret -> {};

    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final PrintWriter err = spec.commandLine().getErr();
        if (ifForge && missingPlain(forgeConf)) {
            return 0;
        }
        final HostForgeSettings settings = HostForgeSettings.read(forgeConf, attributes);
        if (settings == null) {
            err.println("porelo: the forge settings are not a plain file root alone can change");
            err.flush();
            return 69;
        }
        if (ifForge && settings.accounts() <= 1 && settings.account().isEmpty()) {
            return 0;
        }
        if (!settings.validAccount()) {
            err.println("porelo: the forge settings name no single valid forge account");
            err.flush();
            return 69;
        }
        if (!settings.validName()) {
            err.println("porelo: the forge settings name no valid forge");
            err.flush();
            return 69;
        }

        try {
            taken.apply(hostProgram);
        } catch (final UncheckedIOException | IllegalArgumentException ex) {
            err.println("porelo: " + hostProgram + " is not a program root alone can change");
            err.flush();
            return 69;
        }

        final Path sudo;
        try {
            sudo = found.apply("sudo");
        } catch (final UncheckedIOException | IllegalArgumentException ex) {
            err.println(
                    "porelo: sudo is not a program root alone can change; install or repair the"
                            + " system sudo package");
            err.flush();
            return 69;
        }

        Optional<VaultFile.Opener> opener;
        try {
            if (!context.vault().exists()) {
                err.println("porelo: there is no vault; run 'porelo vault init' first");
                err.flush();
                return 69;
            }
            opener = context.opener();
            if (opener.isEmpty()) {
                if (context.vault().isHostVault()) {
                    err.println(
                            "porelo: the host vault is shut; run 'porelo host unlock <host>' from"
                                    + " your own computer first");
                    err.flush();
                    return 69;
                }
                if (!terminal.getAsBoolean()) {
                    err.println("porelo: the vault is shut; run 'porelo vault unlock' first");
                    err.flush();
                    return 69;
                }
                context.openIfShut(true, true, err);
                opener = context.opener();
                if (opener.isEmpty()) {
                    err.println("porelo: the vault is shut; run 'porelo vault unlock' first");
                    err.flush();
                    return 69;
                }
            }
            // Issuing a token revokes the previous one even if storing its replacement fails.
            context.vault().read(opener.get());
        } catch (final VaultException ex) {
            err.println("porelo: the vault refused the person's forge token; unlock it and retry");
            err.flush();
            return 65;
        }

        final Optional<VaultFile.Opener> openerForIssue = opener;
        try {
            return FileLocks.holding(
                    HostGatherPush.lock(context), () -> issue(settings, sudo, openerForIssue, err));
        } catch (IOException ex) {
            err.println("porelo: the forge token issuance lock could not be held");
            err.flush();
            return 69;
        }
    }

    private int issue(
            HostForgeSettings settings,
            Path sudo,
            Optional<VaultFile.Opener> opener,
            PrintWriter err) {
        final CommandResult issued;
        try {
            issued =
                    HostGatherRunner.system(
                            context.runner(),
                            org.fuin.porelo.core.process.Command.of(
                                            sudo.toString(),
                                            "-n",
                                            hostProgram.toString(),
                                            "forge",
                                            "issue-token")
                                    .in(Path.of("/"))
                                    .withChosenEnvironment(
                                            java.util.Map.of(
                                                    "PATH", "/usr/sbin:/usr/bin", "LC_ALL", "C")));
        } catch (final CommandException | HostGatherRefusal ex) {
            err.println(
                    "porelo: sudo -n "
                            + HOST_PROGRAM
                            + " forge issue-token could not run; check the sudo rule for this"
                            + " account");
            err.flush();
            return 69;
        }

        if (!issued.successful()) {
            final String stderr = issued.standardError().strip();
            if (sudoRefused(stderr)) {
                err.println(
                        "porelo: sudo -n "
                                + HOST_PROGRAM
                                + " forge issue-token was refused; check the sudo rule for this"
                                + " account");
                err.flush();
                return 69;
            }
            if (!stderr.isEmpty()) {
                err.println(safeWords(stderr, issued.standardOutput()));
                err.flush();
            }
            return issued.exitCode();
        }

        final String secret = readTokenLine(issued.standardOutput());
        if (secret == null) {
            err.println("porelo: the host answered no valid forge token");
            err.flush();
            return 69;
        }
        final String entryName = settings.entryName();
        received.accept(secret);
        try {
            context.vault()
                    .update(
                            opener.get(),
                            entries ->
                                    VaultPutCommand.replace(
                                            entries, entryName, VaultEntry.of(secret)));
        } catch (final VaultException ex) {
            err.println("porelo: the vault refused the person's forge token; unlock it and retry");
            err.flush();
            return 65;
        }
        return 0;
    }

    private boolean missingPlain(final Path file) {
        try {
            attributes.read(file);
            return false;
        } catch (final NoSuchFileException ex) {
            return true;
        } catch (final IOException ex) {
            return false;
        }
    }

    private static boolean sudoRefused(final String stderr) {
        final String lower = stderr.toLowerCase(Locale.ROOT);
        return lower.startsWith("sudo:")
                || lower.contains("a password is required")
                || lower.contains("a terminal is required")
                || lower.contains("no tty present")
                || lower.contains("sudoers")
                || lower.contains("not allowed to execute");
    }

    private static String safeWords(final String stderr, final String stdout) {
        String words = stderr;
        // Stdout belongs to the secret channel even when minting fails.
        for (final String line : stdout.lines().toList()) {
            if (!line.isEmpty()) {
                words = words.replace(line, "[redacted]");
                final byte[] bytes = ("x-access-token:" + line).getBytes(StandardCharsets.UTF_8);
                try {
                    words =
                            words.replace(
                                    java.util.Base64.getEncoder().encodeToString(bytes),
                                    "[redacted]");
                } finally {
                    java.util.Arrays.fill(bytes, (byte) 0);
                }
            }
        }
        return words.replaceAll("[0-9a-f]{40}", "[redacted]")
                .replaceAll("[\\p{Cntrl}&&[^\n]]", "?");
    }

    private static @Nullable String readTokenLine(final String stdout) {
        String text = stdout;
        if (text.endsWith("\n")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.endsWith("\r")) {
            text = text.substring(0, text.length() - 1);
        }
        if (!TOKEN_LINE.matcher(text).matches()) {
            return null;
        }
        return text;
    }
}
