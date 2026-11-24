package org.fuin.porelo.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.gate.review.Body;
import org.fuin.porelo.gate.review.Kept;
import org.fuin.porelo.gate.review.LineAnchor;
import org.fuin.porelo.gate.review.OwnerAccount;
import org.fuin.porelo.gate.review.Push;
import org.fuin.porelo.gate.review.ReviewComment;
import org.fuin.porelo.gate.review.ReviewError;
import org.fuin.porelo.gate.review.ReviewItem;
import org.fuin.porelo.gate.review.ReviewRefusal;
import org.fuin.porelo.gate.review.ReviewRefused;
import org.fuin.porelo.gate.review.ReviewReply;
import org.fuin.porelo.gate.review.Side;
import org.fuin.porelo.gate.review.Suggestion;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** Writes a line comment on a waiting push, or a reply in one of its threads. */
@Command(
        name = "comment",
        mixinStandardHelpOptions = true,
        description = "Writes a line comment on a waiting push, or a reply in one of its threads.")
public class GateCommentCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    /** The largest replacement file read: no more fits one message to the daemon either. */
    static final int REPLACEMENT_LIMIT = 4 * 1024 * 1024;

    private static final Pattern LINES = Pattern.compile("([0-9]{1,9})(?:-([0-9]{1,9}))?");

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
            names = "--commit",
            paramLabel = "<id>",
            required = true,
            description = "The full commit id you reviewed; refused if the push moved past it.")
    private String commit;

    @Option(
            names = "--path",
            paramLabel = "<path>",
            description = "The file the comment is on, as the review names it.")
    private @Nullable String path;

    @Option(
            names = "--side",
            paramLabel = "old|new",
            description = "Which side of the change the lines are on: old or new.")
    private @Nullable String side;

    @Option(
            names = "--lines",
            paramLabel = "<start>[-<end>]",
            description = "The lines, counted from 1, both ends included.")
    private @Nullable String lines;

    @Option(
            names = "--text",
            paramLabel = "<text>",
            required = true,
            description = "What the comment or reply says.")
    private String text;

    @Option(
            names = "--replacement-file",
            paramLabel = "<file>",
            description = "A file holding the lines proposed in place of the comment's lines.")
    private @Nullable Path replacementFile;

    @Option(
            names = "--reply-to",
            paramLabel = "<thread>",
            description = "Replies in this thread instead of opening one; takes no lines.")
    private @Nullable String replyTo;

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
        final ReviewItem item;
        try {
            item = item();
        } catch (IllegalArgumentException | IOException ex) {
            err.println("porelo: " + TerminalText.line(String.valueOf(ex.getMessage())));
            err.flush();
            return 64;
        }
        final CommentIntake.Parts used =
                parts == null ? CommentIntake.Parts.unwired(context) : parts;
        try {
            final Push push = used.pushes().waiting(projectName, repository, name);
            if (!push.head().equals(commit.strip())) {
                err.println("porelo: " + moved(push, commit.strip()));
                err.flush();
                return 70;
            }
            final Kept kept = used.intake().take(new OwnerAccount(), push, item, null);
            out.println("thread    " + kept.thread());
            out.println("comment   " + kept.guid());
            out.flush();
            return 0;
        } catch (ReviewRefused ex) {
            err.println("porelo: " + refused(ex.refusal()));
            err.flush();
            return 70;
        } catch (RuntimeException ex) {
            err.println("porelo: " + TerminalText.line(String.valueOf(ex.getMessage())));
            err.flush();
            return 70;
        }
    }

    /** Builds the comment or the reply the command line describes. */
    private ReviewItem item() throws IOException {
        final String thread = replyTo;
        if (thread != null) {
            if (path != null || side != null || lines != null || replacementFile != null) {
                throw new IllegalArgumentException(
                        "--reply-to takes no --path, --side, --lines or --replacement-file:"
                                + " a reply stays where its thread is");
            }
            return new ReviewReply(thread, new Body(text));
        }
        if (path == null || side == null || lines == null) {
            throw new IllegalArgumentException(
                    "a comment needs --path, --side and --lines; a reply needs --reply-to");
        }
        final Matcher range = LINES.matcher(lines);
        if (!range.matches()) {
            throw new IllegalArgumentException("--lines is <start> or <start>-<end>, from 1");
        }
        final int start = Integer.parseInt(range.group(1));
        final int end = range.group(2) == null ? start : Integer.parseInt(range.group(2));
        final Path file = replacementFile;
        return new ReviewComment(
                new LineAnchor(path, Side.named(side), start, end),
                new Body(text),
                file == null ? null : new Suggestion(replacement(file)));
    }

    /** Reads a replacement file whole, as UTF-8, refusing one larger than a message can carry. */
    private static String replacement(final Path file) throws IOException {
        final BasicFileAttributes seen;
        try {
            seen = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException ex) {
            throw new IllegalArgumentException("--replacement-file is no regular file");
        }
        if (!seen.isRegularFile()) {
            throw new IllegalArgumentException("--replacement-file is no regular file");
        }
        // Opened once, never through a link, and read only up to one byte past the limit: a file
        // swapped or grown after the look above is still refused, never read whole.
        final byte[] bytes;
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = in.readNBytes(REPLACEMENT_LIMIT + 1);
        }
        if (bytes.length > REPLACEMENT_LIMIT) {
            throw new IllegalArgumentException(
                    "--replacement-file is larger than " + REPLACEMENT_LIMIT + " bytes");
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException ex) {
            throw new IllegalArgumentException("--replacement-file is not UTF-8");
        }
    }

    /**
     * Says that the push moved: its name, the head it waits at now and the commit reviewed.
     *
     * @param push The push as it waits now.
     * @param reviewed The commit the person named.
     * @return One line, safe to print.
     */
    static String moved(final Push push, final String reviewed) {
        return ReviewError.MOVED_SINCE_REVIEW.wireName()
                + ": "
                + TerminalText.line(push.name())
                + " now waits at "
                + TerminalText.line(push.head())
                + ", not at the reviewed "
                + TerminalText.line(reviewed)
                + "; review it again";
    }

    /**
     * Says a refusal: the contract's name, and the rule or limit it names.
     *
     * @param refusal The refusal.
     * @return One line, safe to print.
     */
    static String refused(final ReviewRefusal refusal) {
        final String detail = refusal.detail();
        return refusal.error().wireName()
                + (detail == null ? "" : ": " + TerminalText.line(detail));
    }
}
