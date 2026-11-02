package org.fuin.porelo.app;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.vault.RegistryText;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Model.CommandSpec;

/** How the organization's commands answer: one object, or a refusal by its reason. */
final class OrgOutput {

    private static final Map<String, Integer> EXITS =
            Map.ofEntries(
                    Map.entry(Refused.USAGE, 64),
                    Map.entry(Refused.NOT_ALLOWED, 65),
                    Map.entry(Refused.MOVED_ON, 65),
                    Map.entry(Refused.LOCKED, 65),
                    Map.entry(Refused.EXISTS, 65),
                    Map.entry(Refused.LAST_OF_ITS_KIND, 65),
                    Map.entry(Refused.RESERVED_NAME, 65),
                    Map.entry(Refused.ON_ANOTHER_HOST, 65),
                    Map.entry(Refused.NO_CARD, 65),
                    Map.entry(Refused.REFUSED_STATE, 65),
                    Map.entry(Refused.NOT_FOUND, 66),
                    Map.entry(Refused.NO_REPOSITORY, 66),
                    Map.entry(Refused.CANNOT_SAY, 69),
                    Map.entry(Refused.NO_KEY, 69),
                    Map.entry(Refused.NO_ORGANIZATION, 78));

    /** A reason this table does not know is nobody's expectation. */
    private static final int FAILED = 70;

    private OrgOutput() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Says a refusal: with {@code --json} one line on standard output, and its words on standard
     * error in any case.
     *
     * @param spec The command.
     * @param json Whether it answers as JSON.
     * @param refused The refusal.
     * @return The exit code.
     */
    static int refuse(final CommandSpec spec, final boolean json, final Refused refused) {
        return say(
                spec,
                json,
                refused.reason(),
                String.valueOf(refused.getMessage()),
                refused.revision(),
                EXITS.getOrDefault(refused.reason(), FAILED));
    }

    /**
     * Says what nobody expected on standard output, for {@code --json}: the words and the trace go
     * through the handler every command fails through, which keeps the trace in the failure log.
     *
     * @param spec The command.
     * @param json Whether it answers as JSON.
     * @param words What happened.
     */
    static void failed(final CommandSpec spec, final boolean json, final String words) {
        if (json) {
            print(spec, "failed", words, null);
        }
    }

    private static int say(
            final CommandSpec spec,
            final boolean json,
            final String reason,
            final String words,
            final @Nullable String revision,
            final int exit) {
        if (json) {
            print(spec, reason, words, revision);
        }
        spec.commandLine().getErr().println("porelo: " + RegistryText.printable(words));
        spec.commandLine().getErr().flush();
        return exit;
    }

    private static void print(
            final CommandSpec spec,
            final String reason,
            final String words,
            final @Nullable String revision) {
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        answer.put("reason", reason);
        answer.put("words", words);
        answer.put("revision", revision);
        answer(spec, answer);
    }

    /** Prints one answer, in which nothing a file held can move a terminal's cursor. */
    static void answer(final CommandSpec spec, final Map<String, @Nullable Object> answer) {
        spec.commandLine().getOut().println(Json.write(safe(answer)));
        spec.commandLine().getOut().flush();
    }

    /** Prints a line for a person, whatever a file put into it. */
    static void line(final CommandSpec spec, final String line) {
        spec.commandLine().getOut().println(RegistryText.printable(line));
        spec.commandLine().getOut().flush();
    }

    /** Keeps a card's lines, which are the card, and makes each line printable. */
    static @Nullable Object safe(final @Nullable Object value) {
        return switch (value) {
            case String text ->
                    Arrays.stream(text.split("\n", -1))
                            .map(RegistryText::printable)
                            .collect(Collectors.joining("\n"));
            case List<?> values -> values.stream().map(OrgOutput::safe).toList();
            case Map<?, ?> values -> {
                final Map<Object, @Nullable Object> shown = new LinkedHashMap<>();
                values.forEach((key, each) -> shown.put(key, safe(each)));
                yield shown;
            }
            case null, default -> value;
        };
    }
}
