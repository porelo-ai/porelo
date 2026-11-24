package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import org.fuin.porelo.gate.review.Kept;
import org.fuin.porelo.gate.review.LineAnchor;
import org.fuin.porelo.gate.review.Push;
import org.fuin.porelo.gate.review.ReviewComment;
import org.fuin.porelo.gate.review.ReviewRefused;
import org.fuin.porelo.gate.review.ReviewReply;
import org.fuin.porelo.gate.review.ReviewThread;
import org.fuin.porelo.gate.review.ReviewWriter;
import org.fuin.porelo.gate.review.Suggestion;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** Shows the comment threads of a waiting push. */
@Command(
        name = "comments",
        mixinStandardHelpOptions = true,
        description = "Shows the comment threads of a waiting push, or one SARIF page of them.")
public class GateCommentsCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Option(
            names = {"-p", "--project"},
            paramLabel = "<name>",
            required = true,
            description = "Project name, as 'porelo project list' prints it.")
    private String projectName;

    @Option(
            names = {"-r", "--repository"},
            paramLabel = "<name>",
            description =
                    "Which of the project's repositories. Default: the one the task worked on.")
    private @Nullable String repository;

    @Parameters(index = "0", paramLabel = "<name>", description = "Name of the pending push.")
    private String name;

    @Option(
            names = "--json",
            description =
                    "Prints one SARIF page instead of text; the next page's cursor goes to"
                            + " standard error.")
    private boolean json;

    @Option(
            names = "--cursor",
            paramLabel = "<cursor>",
            description = "With --json, the page the last page's cursor names.")
    private @Nullable String cursor;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    private CommentIntake.@Nullable Parts parts;

    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    /**
     * Uses these parts instead of the machine's.
     *
     * @param parts The pushes, check, store and writer.
     */
    void use(final CommentIntake.Parts parts) {
        this.parts = parts;
    }

    @Override
    public Integer call() {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        if (cursor != null && !json) {
            err.println("porelo: --cursor pages only --json; the text shows every thread");
            err.flush();
            return 64;
        }
        final CommentIntake.Parts used = parts == null ? CommentIntake.Parts.of(context) : parts;
        try {
            final Push push = used.pushes().waiting(projectName, repository, name);
            final List<ReviewThread> threads = used.store().threads(push);
            if (json) {
                final ReviewWriter.Page page = used.writer().page(push, threads, cursor);
                out.println(page.sarif());
                out.flush();
                final String next = page.next();
                if (next != null) {
                    err.println("cursor    " + TerminalText.line(next));
                    err.flush();
                }
                return 0;
            }
            out.print(shown(threads));
            out.flush();
            return 0;
        } catch (ReviewRefused ex) {
            err.println("porelo: " + GateCommentCommand.refused(ex.refusal()));
            err.flush();
            return 70;
        } catch (RuntimeException ex) {
            err.println("porelo: " + TerminalText.line(String.valueOf(ex.getMessage())));
            err.flush();
            return 70;
        }
    }

    /**
     * Returns threads as text: each thread's id and state, then each item's producer, the opening
     * comment's file, side and lines, and every line of what was written, indented below.
     *
     * <p>What a producer wrote goes through the terminal's escaping line by line and is indented
     * deeper than every header, so no text can draw a header of its own.
     *
     * @param threads The threads, the oldest first.
     * @return The text; a line saying so when there are none.
     */
    static String shown(final List<ReviewThread> threads) {
        if (threads.isEmpty()) {
            return "no comments\n";
        }
        final StringBuilder text = new StringBuilder();
        for (final ReviewThread thread : threads) {
            text.append("thread ")
                    .append(thread.id())
                    .append("  ")
                    .append(thread.state().name().toLowerCase(Locale.ROOT))
                    .append('\n');
            for (final Kept kept : thread.items()) {
                text.append("  ").append(TerminalText.line(kept.origin().producer().words()));
                switch (kept.item()) {
                    case ReviewComment comment -> {
                        switch (comment.anchor()) {
                            case LineAnchor line ->
                                    text.append("  ")
                                            .append(TerminalText.line(line.path()))
                                            .append("  ")
                                            .append(line.side().word())
                                            .append(' ')
                                            .append(line.startLine())
                                            .append('-')
                                            .append(line.endLine());
                        }
                        text.append('\n');
                        indented(text, comment.body().text());
                        final Suggestion suggestion = comment.suggestion();
                        if (suggestion != null) {
                            text.append("  suggests\n");
                            indented(text, suggestion.replacement());
                        }
                    }
                    case ReviewReply reply -> {
                        text.append("  replies\n");
                        indented(text, reply.body().text());
                    }
                }
            }
        }
        return text.toString();
    }

    /** Appends each line of a producer's text, escaped and indented below its header. */
    private static void indented(final StringBuilder text, final String written) {
        written.lines()
                .forEach(line -> text.append("    ").append(TerminalText.line(line)).append('\n'));
    }
}
