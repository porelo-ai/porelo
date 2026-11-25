package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import org.fuin.porelo.gate.review.PullRequestRef;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** Shows what is open at a place for one role, and the place's decision. */
@Command(
        name = "open",
        mixinStandardHelpOptions = true,
        description =
                "Shows what is open at a place for one role and the place's decision, read anew"
                        + " from the gate's records. Changes and starts nothing.")
public class ReviewOpenCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Option(
            names = {"-p", "--project"},
            paramLabel = "<name>",
            required = true,
            description = "Project name, as 'porelo project list' prints it.")
    private String projectName;

    @Option(
            names = "--gate-push",
            paramLabel = "<name>",
            description = "A push at this machine's gate, by its name.")
    private @Nullable String gatePush;

    @Option(
            names = {"-r", "--repository"},
            paramLabel = "<repo>",
            description =
                    "With --gate-push, which of the project's repositories. Default: the one the"
                            + " task worked on.")
    private @Nullable String repository;

    @Option(
            names = "--pull-request",
            paramLabel = "<url>",
            description =
                    "A pull request at a forge this machine knows, by its https address; needs"
                            + " --task.")
    private @Nullable String pullRequest;

    @Option(
            names = "--own-pull-request",
            description = "The pull request the task's own pushes opened; needs --task.")
    private boolean ownPullRequest;

    @Option(
            names = "--role",
            paramLabel = "AUTHOR|REVIEWER",
            description = "The role asking. Default: AUTHOR.")
    private @Nullable String role;

    @Option(names = "--task", paramLabel = "<task>", description = "The task whose run asks.")
    private @Nullable String task;

    @Option(
            names = "--json",
            description = "Prints the answer as one JSON object, by the daemon's field names.")
    private boolean json;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    private @Nullable ReviewOpen open;

    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    /**
     * Answers from this reader instead of the machine's.
     *
     * @param open The reader.
     */
    void use(final ReviewOpen open) {
        this.open = open;
    }

    @Override
    public Integer call() {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        final String refused = refusedArguments();
        if (refused != null) {
            err.println("porelo: " + refused);
            err.flush();
            return 64;
        }
        final ReviewOpen.Place place = place();
        final ReviewOpen.Role asking =
                role == null ? null : ReviewOpen.Role.valueOf(role.toUpperCase(Locale.ROOT));
        final ReviewOpen.Answer answer;
        try {
            answer =
                    (open == null ? ReviewOpen.of(context) : open)
                            .answer(projectName, place, asking, task);
        } catch (ReviewOpen.Refused ex) {
            final Map<String, Object> shown = new LinkedHashMap<>();
            shown.put("refused", ex.error());
            shown.putAll(ex.fields());
            out.println(Json.write(shown));
            out.flush();
            err.println("porelo: " + sentence(ex));
            err.flush();
            return 69;
        } catch (RuntimeException ex) {
            err.println(
                    "porelo: what is open there could not be read: "
                            + TerminalText.line(String.valueOf(ex.getMessage())));
            err.flush();
            return 70;
        }
        out.print(json ? Json.write(answer.fields()) + "\n" : shown(answer));
        out.flush();
        return 0;
    }

    /** Why the arguments are refused, or null when they name one place and a known role. */
    private @Nullable String refusedArguments() {
        final int places =
                (gatePush == null ? 0 : 1)
                        + (pullRequest == null ? 0 : 1)
                        + (ownPullRequest ? 1 : 0);
        if (places == 0) {
            return "name a place: --gate-push, --pull-request or --own-pull-request";
        }
        if (places > 1) {
            return "name one place: --gate-push, --pull-request and --own-pull-request exclude each"
                    + " other";
        }
        if (repository != null && gatePush == null) {
            return "--repository names the repository of a --gate-push";
        }
        if (ownPullRequest && (task == null || task.isBlank())) {
            return "--own-pull-request needs --task, the task whose pull request it is";
        }
        if (pullRequest != null && (task == null || task.isBlank())) {
            return "--pull-request needs --task, the task whose run asks";
        }
        if (pullRequest != null && ref(pullRequest) == null) {
            return "--pull-request is a pull request's https address, not "
                    + TerminalText.line(pullRequest);
        }
        if (gatePush != null && gatePush.isBlank()) {
            return "--gate-push needs the push's name";
        }
        if (role != null
                && !"AUTHOR".equalsIgnoreCase(role)
                && !"REVIEWER".equalsIgnoreCase(role)) {
            return "--role is AUTHOR or REVIEWER, not " + TerminalText.line(role);
        }
        return null;
    }

    private ReviewOpen.Place place() {
        if (gatePush != null) {
            return new ReviewOpen.GatePush(repository, gatePush);
        }
        if (pullRequest != null) {
            return new ReviewOpen.PullRequest(Objects.requireNonNull(ref(pullRequest), "ref"));
        }
        return new ReviewOpen.OwnPullRequest();
    }

    /** The pull request an address names, or null when it is none. */
    private static @Nullable PullRequestRef ref(final String address) {
        try {
            return PullRequestRef.of(new URI(address));
        } catch (URISyntaxException | IllegalArgumentException ex) {
            return null;
        }
    }

    private static String sentence(final ReviewOpen.Refused refused) {
        final Object reason = refused.fields().get("reason");
        final Object detail = refused.fields().get("detail");
        return "the place cannot be read ("
                + TerminalText.line(refused.error())
                + (reason == null ? "" : ", " + TerminalText.line(String.valueOf(reason)))
                + ")"
                + (detail == null ? "" : ": " + TerminalText.line(String.valueOf(detail)));
    }

    /**
     * Returns an answer as text: the decision and its time, the head, what went, the counts, why it
     * is incomplete, each point, and the version. Every string from outside goes through the
     * terminal's escaping.
     *
     * @param answer The answer.
     * @return The text.
     */
    static String shown(final ReviewOpen.Answer answer) {
        final StringBuilder text = new StringBuilder();
        text.append("decision  ").append(answer.decision().name());
        if (answer.decisionSince() != null) {
            text.append("  since ").append(answer.decisionSince());
        }
        text.append('\n');
        if (answer.head() != null) {
            text.append("head      ").append(TerminalText.line(answer.head())).append('\n');
        }
        if (answer.mergedCommit() != null) {
            text.append("merged    ").append(TerminalText.line(answer.mergedCommit()));
            if (answer.mergedHead() != null) {
                text.append(" on ").append(TerminalText.line(answer.mergedHead()));
            }
            text.append('\n');
        }
        text.append("findings  ").append(answer.findings()).append('\n');
        text.append("held      ").append(answer.held()).append('\n');
        if (!answer.complete()) {
            text.append("incomplete  ")
                    .append(TerminalText.line(String.valueOf(answer.reason())))
                    .append('\n');
        }
        if (answer.points().isEmpty()) {
            text.append("no open point\n");
        }
        for (final ReviewOpen.Point point : answer.points()) {
            text.append("point     ").append(TerminalText.line(point.thread()));
            if (point.path() != null) {
                text.append("  ").append(TerminalText.line(point.path()));
                if (point.line() != null) {
                    text.append(':').append(point.line());
                }
            }
            text.append("  ")
                    .append(TerminalText.line(point.lastBy()))
                    .append("  ")
                    .append(point.lastAt())
                    .append('\n');
        }
        text.append("version   ").append(answer.version()).append('\n');
        return text.toString();
    }
}
