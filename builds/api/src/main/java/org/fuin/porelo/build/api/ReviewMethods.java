package org.fuin.porelo.build.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.jspecify.annotations.Nullable;

/** The adapter's side of {@link ReviewProtocol#INTERFACE}, served beside the build contract. */
final class ReviewMethods {

    private ReviewMethods() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Serves the review contract on a server that already serves the build contract.
     *
     * @param server The adapter's one server.
     * @param reader What answers.
     * @param forge The forge it reads, as its build reader names it.
     */
    static void register(
            final VarlinkServer server, final ReviewReader reader, final String forge) {
        server.serving(ReviewProtocol.INTERFACE, idl());

        server.method(
                ReviewProtocol.INTERFACE,
                "Describe",
                (parameters, replies) -> {
                    final Map<String, Object> answer = new LinkedHashMap<>();
                    answer.put("protocolVersion", Integer.valueOf(ReviewProtocol.VERSION));
                    answer.put("forge", forge);
                    replies.last(answer);
                });

        server.method(
                ReviewProtocol.INTERFACE,
                "PullRequest",
                (parameters, replies) -> {
                    final String url = required(parameters, "url");
                    replies.last(
                            answering(() -> reader.pullRequest(access(parameters), url)).asMap());
                });

        server.method(
                ReviewProtocol.INTERFACE,
                "PullRequestOf",
                (parameters, replies) -> {
                    final String upstream = required(parameters, "upstream");
                    final String branch = required(parameters, "branch");
                    final String url =
                            answering(
                                    () ->
                                            reader.pullRequestOf(
                                                    access(parameters), upstream, branch));
                    final Map<String, Object> answer = new LinkedHashMap<>();
                    if (url != null) {
                        answer.put("url", url);
                    }
                    replies.last(answer);
                });

        server.method(
                ReviewProtocol.INTERFACE,
                "Comments",
                (parameters, replies) -> {
                    final String url = required(parameters, "url");
                    final Object cursor = parameters.get("cursor");
                    final ConversationPage page =
                            answering(
                                    () ->
                                            reader.comments(
                                                    access(parameters),
                                                    url,
                                                    cursor == null
                                                            ? null
                                                            : String.valueOf(cursor)));
                    final Map<String, Object> answer = new LinkedHashMap<>();
                    answer.put("document", new String(page.document(), StandardCharsets.UTF_8));
                    if (page.cursor() != null) {
                        answer.put("cursor", page.cursor());
                    }
                    replies.last(answer);
                });

        server.method(
                ReviewProtocol.INTERFACE,
                "Post",
                (parameters, replies) -> {
                    final String url = required(parameters, "url");
                    final String document = required(parameters, "document");
                    replies.last(
                            Map.of(
                                    "thread",
                                    answering(
                                            () -> reader.post(access(parameters), url, document))));
                });
    }

    /**
     * Returns the interface description, as the jar carries it.
     *
     * @return The IDL.
     */
    static String idl() {
        try (InputStream in =
                ReviewMethods.class.getResourceAsStream(ReviewProtocol.INTERFACE + ".varlink")) {
            if (in == null) {
                throw new IllegalStateException(
                        "The jar has no " + ReviewProtocol.INTERFACE + ".varlink");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Cannot read " + ReviewProtocol.INTERFACE + ".varlink", ex);
        }
    }

    /** A refusal travels as the contract's error of that name, carrying only what it declares. */
    private static <T> T answering(final Supplier<T> call) {
        try {
            return call.get();
        } catch (ForgeReviewRefused ex) {
            final Map<String, Object> said = new LinkedHashMap<>();
            if (ex.reason() == ForgeReviewRefused.Reason.RATE_LIMITED) {
                said.put("retryAfter", Long.valueOf(ex.retryAfterSeconds()));
            }
            if (ex.reason() == ForgeReviewRefused.Reason.NOT_IN_PROFILE && !ex.field().isEmpty()) {
                said.put("field", ex.field());
            }
            throw new VarlinkException(ReviewProtocol.error(ex.reason()), said);
        }
    }

    private static ForgeAccess access(final Map<String, Object> parameters) {
        try {
            return new ForgeAccess(text(parameters.get("api")), text(parameters.get("token")));
        } catch (IllegalArgumentException ex) {
            throw new VarlinkException(
                    "org.varlink.service.InvalidParameter", Map.of("parameter", "token"));
        }
    }

    private static String required(final Map<String, Object> parameters, final String name) {
        final String value = text(parameters.get(name));
        if (value.isBlank()) {
            throw new VarlinkException(
                    "org.varlink.service.InvalidParameter", Map.of("parameter", name));
        }
        return value;
    }

    private static String text(final @Nullable Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
