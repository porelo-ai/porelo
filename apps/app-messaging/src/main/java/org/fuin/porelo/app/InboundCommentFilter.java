package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.LimitFactors;
import org.jspecify.annotations.Nullable;

/**
 * Puts a forge's comment through the message filter as an external peer's message: its profile 2
 * page becomes the one data part of a host-built inbound {@code comment} message from the forge's
 * host, written into a check area only the owner reaches, and the filter is run on it as {@link
 * InboundCheck} runs it, with {@code --inbound}.
 *
 * <p>Fail closed: no filter, one that cannot be run, stops with anything but its two ordinary
 * outcomes, times out, or cannot read the message gives no verdict, and that throws. One message is
 * checked at a time, in this process and across processes, since the area is emptied before and
 * after each.
 */
public final class InboundCommentFilter implements InboundFilter {

    /** The kind of message a comment goes as. */
    static final String KIND = "comment";

    /** A rule id the filter names, as it may be shown and kept. */
    private static final Pattern SHOWN_RULE = Pattern.compile("[A-Za-z0-9_./:-]{1,128}");

    private final CommandRunner runner;
    private final @Nullable Path filter;
    private final LimitFactors limits;
    private final Path area;
    private final Object lock = new Object();

    /**
     * Constructor.
     *
     * @param runner How commands are run.
     * @param filter The filter's executable, or {@code null} when this machine has none.
     * @param limits The factors of the account that runs the check, which the filter is handed.
     * @param area A directory only the owner reaches, where each message is checked; made when
     *     missing.
     */
    public InboundCommentFilter(
            final CommandRunner runner,
            final @Nullable Path filter,
            final LimitFactors limits,
            final Path area) {
        this.runner = java.util.Objects.requireNonNull(runner, "runner");
        this.filter = filter;
        this.limits = java.util.Objects.requireNonNull(limits, "limits");
        this.area = java.util.Objects.requireNonNull(area, "area");
    }

    @Override
    public Optional<String> refuse(final String peer, final byte[] data) throws IOException {
        final Path program = filter;
        if (program == null) {
            throw new IOException("no message filter is installed to check it");
        }
        final Map<?, ?> page = page(data);
        final Mailbox mailbox = new Mailbox(area);
        synchronized (lock) {
            prepare(mailbox);
            try (FileChannel channel =
                            FileChannel.open(
                                    area.resolve("lock"),
                                    Set.of(
                                            StandardOpenOption.CREATE,
                                            StandardOpenOption.WRITE,
                                            LinkOption.NOFOLLOW_LINKS),
                                    PosixFilePermissions.asFileAttribute(
                                            PosixFilePermissions.fromString("rw-------")));
                    FileLock held = channel.lock()) {
                return check(program, mailbox, peer, page);
            }
        }
    }

    private Optional<String> check(
            final Path program, final Mailbox mailbox, final String peer, final Map<?, ?> page)
            throws IOException {
        InboundFilterRun.clear(mailbox);
        try {
            final String id = "porelo-" + UUID.randomUUID();
            final String name = id + ".json";
            put(mailbox, name, message(id, peer, page));
            final CommandResult result;
            try {
                result =
                        runner.run(
                                InboundFilterRun.command(
                                        program, limits, mailbox.check(), "--inbound"));
            } catch (final RuntimeException e) {
                throw new IOException("the message filter could not be run: " + e.getMessage(), e);
            }
            if (!InboundFilterRun.ran(result.exitCode())) {
                throw new IOException("the message filter stopped with " + result.exitCode());
            }
            if (Files.isRegularFile(mailbox.checkAccepted().resolve(name))) {
                return Optional.empty();
            }
            if (Files.isRegularFile(mailbox.checkRejected().resolve(name))) {
                return Optional.of(rule(mailbox, id));
            }
            throw new IOException("the message filter could not read the comment");
        } finally {
            InboundFilterRun.clear(mailbox);
        }
    }

    /** Reads the page as the JSON object the data part carries, its numbers as written. */
    private static Map<?, ?> page(final byte[] data) throws IOException {
        final Object parsed;
        try {
            parsed = Json.parseNumberTypes(new String(data, StandardCharsets.UTF_8));
        } catch (final RuntimeException e) {
            throw new IOException("the comment's page is not JSON", e);
        }
        if (!(parsed instanceof Map<?, ?> page)) {
            throw new IOException("the comment's page is not a JSON object");
        }
        return page;
    }

    private static Map<String, Object> message(
            final String id, final String peer, final Map<?, ?> page) {
        final Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("from", peer);
        metadata.put("kind", KIND);
        final Map<String, Object> message = new LinkedHashMap<>();
        message.put("messageId", id);
        message.put("role", MessageIntake.AGENT);
        message.put("parts", List.of(Map.of("data", page)));
        message.put("metadata", metadata);
        return message;
    }

    /** Writes the message owner-only beside the queue and renames it in, never half written. */
    private static void put(
            final Mailbox mailbox, final String name, final Map<String, Object> message)
            throws IOException {
        final Path staged = mailbox.check().resolve("." + name);
        Files.deleteIfExists(staged);
        Files.createFile(
                staged,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(staged, Json.write(message), StandardCharsets.UTF_8);
        Files.move(staged, mailbox.checkIncoming().resolve(name), StandardCopyOption.ATOMIC_MOVE);
    }

    /** Makes the check area owner-only, refusing one reached through a symbolic link. */
    private void prepare(final Mailbox mailbox) throws IOException {
        final Path parent = area.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        for (final Path directory :
                List.of(
                        area,
                        mailbox.check(),
                        mailbox.checkIncoming(),
                        mailbox.checkAccepted().getParent(),
                        mailbox.checkAccepted(),
                        mailbox.checkRejected(),
                        mailbox.checkFeedback(),
                        mailbox.checkError())) {
            if (Files.isSymbolicLink(directory)) {
                throw new IOException("the comment check area is a symbolic link: " + directory);
            }
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(directory);
            }
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        }
    }

    /**
     * Returns the rule the filter's answer about the message names: its deciding rule, else its
     * first finding's, else {@link InboundFilter#UNNAMED_RULE}.
     */
    private static String rule(final Mailbox mailbox, final String id) throws IOException {
        final List<Path> answers;
        try (Stream<Path> listed = Files.list(mailbox.checkFeedback())) {
            answers = listed.filter(Files::isRegularFile).sorted().toList();
        }
        for (final Path answer : answers) {
            final String named = named(answer, id);
            if (named != null) {
                return named;
            }
        }
        return UNNAMED_RULE;
    }

    private static @Nullable String named(final Path answer, final String id) {
        final Object parsed;
        try {
            parsed = Json.parse(Files.readString(answer, StandardCharsets.UTF_8));
        } catch (final IOException | RuntimeException unreadable) {
            // Another answer may still name it; none at all leaves the unnamed rule.
            return null;
        }
        if (!(parsed instanceof Map<?, ?> document)
                || !(document.get("metadata") instanceof Map<?, ?> metadata)
                || !id.equals(metadata.get("inReplyToMessageId"))
                || !(document.get("parts") instanceof List<?> parts)) {
            return null;
        }
        final List<Object> rules = new ArrayList<>();
        for (final Object part : parts) {
            if (part instanceof Map<?, ?> each && each.get("data") instanceof Map<?, ?> data) {
                rules.add(data.get("decidingRule"));
                if (data.get("findings") instanceof List<?> findings) {
                    for (final Object finding : findings) {
                        if (finding instanceof Map<?, ?> found) {
                            rules.add(found.get("ruleId"));
                        }
                    }
                }
            }
        }
        for (final Object rule : rules) {
            if (rule instanceof String text && SHOWN_RULE.matcher(text).matches()) {
                return text;
            }
        }
        return null;
    }
}
