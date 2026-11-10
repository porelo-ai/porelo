package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.Callable;
import org.fuin.porelo.core.process.CommandException;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Judges every commit of a push as a host's forge receives it, from its pre-receive hook, without a
 * home, a vault or the network: every file it reads is named, and it writes nothing but the scratch
 * files ssh-keygen reads, removed before it answers.
 */
@Command(
        name = "verify",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description =
                "Judges each new commit of a push, the pre-receive hook's lines on standard input,"
                        + " by its SSH signature at its arrival.")
public class CommitVerifyCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Option(
            names = "--repository",
            required = true,
            paramLabel = "GIT-DIR",
            description = "The repository the push goes to, read through the hook's quarantine.")
    private Path repository = Path.of("");

    @Option(
            names = "--signers",
            required = true,
            paramLabel = "FILE",
            description = "The persons' keys in force, in ssh-keygen's allowed signers format.")
    private Path signers = Path.of("");

    @Option(
            names = "--certifiers",
            required = true,
            paramLabel = "FILE",
            description = "The certifiers' cert-authority lines, in allowed signers format.")
    private Path certifiers = Path.of("");

    @Option(names = "--krl", paramLabel = "FILE", description = "The SSH revocation list in force.")
    private @Nullable Path krl;

    @Option(
            names = "--certificates",
            paramLabel = "FILE",
            description =
                    "The host's recorded task certificates, one line each, as"
                            + " porelo-host task show answers them.")
    private @Nullable Path certificates;

    @Option(names = "--json", description = "Answers every verdict as JSON on standard output.")
    private boolean json;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final var out = spec.commandLine().getOut();
        final var err = spec.commandLine().getErr();
        final HostArrivals.Answer answer;
        for (final Path each : java.util.Arrays.asList(repository, signers, certifiers)) {
            if (each.toString().isEmpty()) {
                return fail(64, "--repository, --signers and --certifiers each name a path");
            }
        }
        if ((krl != null && krl.toString().isEmpty())
                || (certificates != null && certificates.toString().isEmpty())) {
            return fail(64, "--krl and --certificates each name a path");
        }
        try {
            final HostArrivals arrivals =
                    HostArrivals.reading(
                            context.runner(),
                            repository,
                            signers,
                            certifiers,
                            krl,
                            certificates,
                            context.environment(),
                            Clock.systemUTC());
            final byte[] input;
            try {
                input = context.input().stream().readNBytes(HostArrivals.MAX_INPUT + 1);
            } catch (IOException ex) {
                return fail(70, "the hook's input cannot be read: " + ex.getMessage());
            }
            answer = arrivals.judge(input);
        } catch (IOException ex) {
            return fail(66, String.valueOf(ex.getMessage()));
        } catch (CommandException ex) {
            return fail(70, "git or ssh-keygen could not be run: " + ex.getMessage());
        } catch (java.io.UncheckedIOException ex) {
            return fail(
                    70,
                    "ssh-keygen could not be run: "
                            + ex.getMessage()
                            + ": "
                            + java.util.Objects.requireNonNull(
                                            ex.getCause(), "an unchecked I/O failure has a cause")
                                    .getMessage());
        }
        if (!answer.reason().isEmpty()) {
            err.println("porelo: " + answer.reason());
        }
        for (final HostArrivals.Verdict each : answer.commits()) {
            if (!each.accepted()) {
                err.println(each.commit() + " " + each.reason());
            }
        }
        err.flush();
        if (json) {
            out.println(answer.json());
        } else {
            out.println(
                    (answer.accepted() ? "accepted" : "refused")
                            + ": "
                            + answer.commits().stream()
                                    .filter(HostArrivals.Verdict::accepted)
                                    .count()
                            + " of "
                            + answer.commits().size()
                            + " new commits hold at "
                            + answer.checkedAt());
        }
        out.flush();
        return answer.accepted() ? 0 : 65;
    }

    private int fail(int code, String message) {
        final var err = spec.commandLine().getErr();
        err.println("porelo: " + HostArrivals.text(message));
        err.flush();
        return code;
    }
}
