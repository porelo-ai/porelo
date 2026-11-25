package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.LimitFactors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A forge's comment goes through the message filter as an external peer's message does. */
class InboundCommentFilterTest {

    private static final Path FILTER = Path.of("/usr/libexec/porelo/porelo-message-sluice-filter");

    private static final byte[] PAGE =
            "{\"version\":\"2.1.0\",\"runs\":[{\"results\":[{\"ruleId\":\"comment\"}]}]}"
                    .getBytes(StandardCharsets.UTF_8);

    @TempDir Path dir;

    /** What the fake filter was given: each command and each message it found waiting. */
    private final List<Command> commands = new ArrayList<>();

    private final List<Map<?, ?>> messages = new ArrayList<>();

    private Path area() {
        return dir.resolve("comments");
    }

    private Path check() {
        return new Mailbox(area()).check();
    }

    /**
     * A filter that moves each waiting message to the directory named and, for a refusal, writes an
     * answer about it, the way the real one does.
     */
    private CommandRunner filterThat(
            final String outcome, final @Nullable Map<String, Object> verdict) {
        final Mailbox mailbox = new Mailbox(area());
        return command -> {
            commands.add(command);
            try (Stream<Path> waiting = Files.list(mailbox.checkIncoming())) {
                for (final Path message : waiting.toList()) {
                    final Map<?, ?> parsed =
                            (Map<?, ?>)
                                    Json.parse(Files.readString(message, StandardCharsets.UTF_8));
                    messages.add(parsed);
                    final Path target =
                            switch (outcome) {
                                case "accepted" -> mailbox.checkAccepted();
                                case "rejected" -> mailbox.checkRejected();
                                default -> mailbox.checkError();
                            };
                    Files.move(message, target.resolve(message.getFileName().toString()));
                    if (verdict != null) {
                        Files.writeString(
                                mailbox.checkFeedback().resolve("answer.json"),
                                Json.write(
                                        Map.of(
                                                "messageId",
                                                "sluice-1",
                                                "metadata",
                                                Map.of(
                                                        "inReplyToMessageId",
                                                        parsed.get("messageId")),
                                                "parts",
                                                List.of(
                                                        Map.of(
                                                                "text",
                                                                "Your message was rejected."),
                                                        Map.of("data", verdict)))));
                    }
                }
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
            return new CommandResult(
                    command,
                    "accepted".equals(outcome)
                            ? MessageFiltering.ALL_ACCEPTED
                            : MessageFiltering.SOMETHING_REFUSED,
                    "",
                    "");
        };
    }

    private InboundCommentFilter filter(final CommandRunner runner) {
        return new InboundCommentFilter(runner, FILTER, LimitFactors.DEFAULT, area());
    }

    @Test
    void aRefusalGivesTheFiltersDecidingRule() throws IOException {
        final Optional<String> rule =
                filter(
                                filterThat(
                                        "rejected",
                                        Map.of(
                                                "decision",
                                                "rejected",
                                                "decidingRule",
                                                "PROFILE/UNKNOWN_FIELD")))
                        .refuse("github.com", PAGE);

        assertThat(rule).as("the filter's deciding rule").contains("PROFILE/UNKNOWN_FIELD");
    }

    @Test
    void aRefusalWithoutADecidingRuleGivesItsFirstFindingsRule() throws IOException {
        final Optional<String> rule =
                filter(
                                filterThat(
                                        "rejected",
                                        Map.of(
                                                "decision",
                                                "rejected",
                                                "findings",
                                                List.of(Map.of("ruleId", "ENCODING/BASE64")))))
                        .refuse("github.com", PAGE);

        assertThat(rule).as("the first finding's rule").contains("ENCODING/BASE64");
    }

    @Test
    void aRefusalNamingNoRuleThatCanBeShownGivesTheUnnamedRule() throws IOException {
        // A refusal is still a refusal, and a rule id with a line feed is never shown.
        final Optional<String> unnamed =
                filter(filterThat("rejected", null)).refuse("github.com", PAGE);
        final Optional<String> hostile =
                new InboundCommentFilter(
                                filterThat(
                                        "rejected",
                                        Map.of("decidingRule", "RULE\n\u001b[2Jforged")),
                                FILTER,
                                LimitFactors.DEFAULT,
                                area())
                        .refuse("github.com", PAGE);

        assertThat(unnamed).as("no answer").contains(InboundFilter.UNNAMED_RULE);
        assertThat(hostile).as("a rule that cannot be shown").contains(InboundFilter.UNNAMED_RULE);
    }

    @Test
    void aPassGivesNoRule() throws IOException {
        final Optional<String> rule =
                filter(filterThat("accepted", null)).refuse("github.com", PAGE);

        assertThat(rule).as("it may be delivered").isEmpty();
        assertThat(new Mailbox(area()).checkAccepted())
                .as("nothing left behind")
                .isEmptyDirectory();
    }

    @Test
    void thePageGoesAsTheOneDataPartOfACommentFromTheForgesHost() throws IOException {
        // The filter judges it as it judges a message from a peer this machine does not vouch for.
        filter(filterThat("accepted", null)).refuse("github.com", PAGE);

        assertThat(messages).as("one message checked").hasSize(1);
        final Map<?, ?> message = messages.get(0);
        assertThat(message.get("metadata"))
                .as("a comment from the forge's host")
                .isEqualTo(Map.of("from", "github.com", "kind", "comment"));
        assertThat(message.get("parts"))
                .as("the page as its one data part")
                .isEqualTo(
                        List.of(
                                Map.of(
                                        "data",
                                        Json.parse(new String(PAGE, StandardCharsets.UTF_8)))));
        assertThat(message.get("messageId")).as("it has an id").isNotNull();
    }

    @Test
    void theFilterRunsInboundAndBlockingOnTheCheckArea() throws IOException {
        filter(filterThat("accepted", null)).refuse("github.com", PAGE);

        assertThat(commands)
                .as("one run of the filter")
                .singleElement()
                .satisfies(
                        command -> {
                            assertThat(command.arguments().subList(0, 5))
                                    .as("the factors first, then the check area")
                                    .containsExactly(
                                            FILTER.toString(),
                                            "--limits",
                                            LimitFactors.DEFAULT.line(),
                                            "--mail",
                                            check().toString());
                            assertThat(command.arguments())
                                    .as("blocking, without settling, on the way in")
                                    .contains("--blocking", "--inbound")
                                    .containsSubsequence("--stabilityDelayMillis", "0");
                        });
    }

    @Test
    void theCheckAreaIsTheOwnersAlone() throws IOException {
        filter(filterThat("accepted", null)).refuse("github.com", PAGE);

        for (final Path each : List.of(area(), check(), new Mailbox(area()).checkIncoming())) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(each)))
                    .as("%s owner-only", each.getFileName())
                    .isEqualTo("rwx------");
        }
    }

    @Test
    void aFilterThatCrashesThrows() {
        final CommandRunner crashing = command -> new CommandResult(command, 134, "", "aborted");

        assertThatThrownBy(() -> filter(crashing).refuse("github.com", PAGE))
                .as("a crash is no verdict")
                .isInstanceOf(IOException.class);
    }

    @Test
    void aFilterThatCannotBeRunOrTimesOutThrows() {
        final CommandRunner failing =
                command -> {
                    throw new IllegalStateException("timed out after 600 s");
                };

        assertThatThrownBy(() -> filter(failing).refuse("github.com", PAGE))
                .as("a run that did not end is no verdict")
                .isInstanceOf(IOException.class);
    }

    @Test
    void aMissingFilterBinaryThrows() {
        final InboundCommentFilter missing =
                new InboundCommentFilter(
                        new ProcessCommandRunner(Duration.ofSeconds(10)),
                        dir.resolve("no-such-filter"),
                        LimitFactors.DEFAULT,
                        area());
        final InboundCommentFilter none =
                new InboundCommentFilter(
                        filterThat("accepted", null), null, LimitFactors.DEFAULT, area());

        assertThatThrownBy(() -> missing.refuse("github.com", PAGE))
                .as("a binary that is not there")
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> none.refuse("github.com", PAGE))
                .as("no filter installed")
                .isInstanceOf(IOException.class);
    }

    @Test
    void twoFiltersOnOneAreaCheckAtOnceAndBothAnswer() throws Exception {
        // Each instance alone in its process is not enough: two in one JVM share the area's lock.
        final CommandRunner slow =
                command -> {
                    try {
                        Thread.sleep(200);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return filterThat("accepted", null).run(command);
                };
        final InboundCommentFilter one = filter(slow);
        final InboundCommentFilter two = filter(slow);
        final java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        final List<java.util.concurrent.Future<Optional<String>>> answers = new ArrayList<>();

        try (java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(2)) {
            for (final InboundCommentFilter each : List.of(one, two)) {
                answers.add(
                        pool.submit(
                                () -> {
                                    go.await();
                                    return each.refuse("github.com", PAGE);
                                }));
            }
            go.countDown();
            for (final java.util.concurrent.Future<Optional<String>> answer : answers) {
                assertThat(answer.get()).as("each check answers, one after the other").isEmpty();
            }
        }
    }

    @Test
    void everyDirectoryOfTheCheckLayoutIsMadeOwnerOnly() throws IOException {
        filter(filterThat("accepted", null)).refuse("github.com", PAGE);

        for (final Path each : new Mailbox(area()).checkLayout()) {
            assertThat(Files.isDirectory(each, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .as("%s made", area().relativize(each))
                    .isTrue();
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(each)))
                    .as("%s owner-only", area().relativize(each))
                    .isEqualTo("rwx------");
        }
    }

    @Test
    void aSymlinkedAreaIsRefused() throws IOException {
        final Path elsewhere = Files.createDirectory(dir.resolve("elsewhere"));
        Files.createSymbolicLink(area(), elsewhere);

        assertThatThrownBy(() -> filter(filterThat("accepted", null)).refuse("github.com", PAGE))
                .as("an area reached through a link")
                .isInstanceOf(IOException.class);
        assertThat(Files.list(elsewhere).toList()).as("nothing written through it").isEmpty();
    }

    @Test
    void aSymlinkedSubdirectoryIsRefused() throws IOException {
        final Path elsewhere = Files.createDirectory(dir.resolve("elsewhere"));
        Files.createDirectories(check());
        Files.createSymbolicLink(check().resolve("sent"), elsewhere);

        assertThatThrownBy(() -> filter(filterThat("accepted", null)).refuse("github.com", PAGE))
                .as("a directory of the area reached through a link")
                .isInstanceOf(IOException.class);
        assertThat(Files.list(elsewhere).toList()).as("nothing written through it").isEmpty();
    }

    @Test
    void aRefusalWhoseAnswersCannotBeListedStandsWithTheUnnamedRule() throws IOException {
        final CommandRunner rejecting = filterThat("rejected", null);
        final Mailbox mailbox = new Mailbox(area());
        final CommandRunner unlistable =
                command -> {
                    final CommandResult result = rejecting.run(command);
                    try {
                        // Its answers are no directory any more, so they cannot be listed.
                        Files.delete(mailbox.checkFeedback());
                        Files.writeString(mailbox.checkFeedback(), "not a directory");
                    } catch (final IOException e) {
                        throw new IllegalStateException(e);
                    }
                    return result;
                };

        final Optional<String> rule = filter(unlistable).refuse("github.com", PAGE);

        assertThat(rule)
                .as("the refusal stands, its rule unnamed")
                .contains(InboundFilter.UNNAMED_RULE);
    }

    @Test
    void aMessageTheFilterCouldNotReadThrows() {
        assertThatThrownBy(() -> filter(filterThat("error", null)).refuse("github.com", PAGE))
                .as("an unread message is no verdict")
                .isInstanceOf(IOException.class);
    }
}
