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

    @Option(
            names = "--json-lines",
            description =
                    "Answers each verdict as a JSON line as it is reached, then a summary line;"
                            + " standard error names only the first 100 refused commits.")
    private boolean jsonLines;

    /**
     * The most refused commits standard error names under {@code --json-lines}: porelo-host shows
     * the pusher no more, and keeps only a bounded part of standard error.
     */
    static final int NAMED_REFUSALS = 100;

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
        final HostArrivals.Summary answer;
        for (final Path each : java.util.Arrays.asList(repository, signers, certifiers)) {
            if (each.toString().isEmpty()) {
                return fail(64, "--repository, --signers and --certifiers each name a path");
            }
        }
        if ((krl != null && krl.toString().isEmpty())
                || (certificates != null && certificates.toString().isEmpty())) {
            return fail(64, "--krl and --certificates each name a path");
        }
        if (json && jsonLines) {
            return fail(64, "--json and --json-lines exclude each other");
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
            if (json) out.print("{\"commits\":[");
            final boolean[] first = {true};
            final long[] refused = {0};
            answer =
                    arrivals.judge(
                            input,
                            verdict -> {
                                if (jsonLines)
                                    out.println(org.fuin.porelo.wire.Json.write(verdict.answer()));
                                else if (json) {
                                    if (!first[0]) out.print(',');
                                    first[0] = false;
                                    out.print(org.fuin.porelo.wire.Json.write(verdict.answer()));
                                }
                                if (jsonLines || json) out.flush();
                                if (!verdict.accepted()
                                        && (!jsonLines || refused[0]++ < NAMED_REFUSALS))
                                    err.println(verdict.commit() + " " + verdict.reason());
                            });
            if (refused[0] > NAMED_REFUSALS) {
                err.println(
                        "porelo: " + (refused[0] - NAMED_REFUSALS) + " more commits are refused");
            }
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
        err.flush();
        if (jsonLines) {
            out.println(
                    org.fuin.porelo.wire.Json.write(
                            java.util.Map.of(
                                    "checked_at",
                                    answer.checkedAt().toString(),
                                    "accepted",
                                    answer.successful(),
                                    "count",
                                    answer.commits(),
                                    "reason",
                                    HostArrivals.text(answer.reason()))));
        } else if (json) {
            out.println(
                    "],\"checked_at\":"
                            + org.fuin.porelo.wire.Json.write(answer.checkedAt().toString())
                            + ",\"accepted\":"
                            + answer.successful()
                            + ",\"reason\":"
                            + org.fuin.porelo.wire.Json.write(HostArrivals.text(answer.reason()))
                            + "}");
        } else {
            out.println(
                    (answer.successful() ? "accepted" : "refused")
                            + ": "
                            + answer.accepted()
                            + " of "
                            + answer.commits()
                            + " new commits hold at "
                            + answer.checkedAt());
        }
        out.flush();
        return answer.successful() ? 0 : 65;
    }

    private int fail(int code, String message) {
        final var err = spec.commandLine().getErr();
        err.println("porelo: " + HostArrivals.text(message));
        err.flush();
        return code;
    }
}
