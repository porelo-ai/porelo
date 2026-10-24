package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.vault.SshSignature;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/** Answers a word meant only for the room's helpers without involving any task or queue. */
final class HelperAnswer {

    private static final String LINE =
            "This is Porelo's technical account; it passes your messages on to the tasks and does not answer itself. Mention a task to reach it.";

    private final PoreloContext context;
    private final Path adapter;
    private final Map<String, String> secrets;
    private final TransportLifecycle.@Nullable Conversation conversation;

    HelperAnswer(
            final PoreloContext context,
            final Path adapter,
            final Map<String, String> secrets,
            final TransportLifecycle.@Nullable Conversation conversation) {
        this.context = context;
        this.adapter = adapter;
        this.secrets = secrets;
        this.conversation = conversation;
    }

    /** Returns why the single attempt failed, or {@code null} when the person was answered. */
    @Nullable String send(final Path inbound, final String person) throws IOException {
        if (conversation == null || conversation.conversation().isBlank()) {
            return "the project's conversation is unavailable";
        }
        if (secrets.isEmpty()) {
            return "the project's transport credentials are unavailable";
        }
        // A private host directory keeps this answer out of every task's mailbox and send queue.
        final Path directory = Files.createTempDirectory(inbound, ".helper-answer-");
        final Path message = directory.resolve("answer.json");
        final Path signature = directory.resolve("answer.json.sig");
        final Path shown = directory.resolve("answer.json.shown");
        try {
            final byte[] bytes = message(person);
            Files.write(message, bytes);
            Files.writeString(
                    signature,
                    SshSignature.sign(
                            HostKey.loadOrCreate(
                                    context.paths().messaging().messageKey(), "porelo"),
                            bytes,
                            MessageIntake.NAMESPACE),
                    StandardCharsets.UTF_8);
            final TransportDescription described =
                    TransportDescription.of(context.runner(), adapter);
            if (described.takes(TransportSend.SHOWN)) {
                Files.writeString(shown, LINE, StandardCharsets.UTF_8);
            }
            final CommandResult result =
                    context.runner()
                            .run(
                                    HelperEnvironment.chosen(
                                            TransportSend.command(
                                                            adapter,
                                                            message,
                                                            signature,
                                                            conversation.conversation(),
                                                            described,
                                                            shown,
                                                            person)
                                                    .withEnvironment(secrets)));
            // A transport's output may echo its credential, so only its exit status is reported.
            return result.successful()
                    ? null
                    : "the transport refused it, exit " + result.exitCode();
        } catch (final CommandException ex) {
            return "the transport could not be run";
        } finally {
            Files.deleteIfExists(shown);
            Files.deleteIfExists(signature);
            Files.deleteIfExists(message);
            Files.delete(directory);
        }
    }

    private static byte[] message(final String person) {
        return Json.write(
                        Map.of(
                                "messageId",
                                "porelo-" + UUID.randomUUID(),
                                "role",
                                MessageIntake.AGENT,
                                "parts",
                                List.of(Map.of("text", LINE, "mediaType", "text/plain")),
                                "metadata",
                                Map.of("from", "porelo", "to", person, "kind", "answer")))
                .getBytes(StandardCharsets.UTF_8);
    }
}
