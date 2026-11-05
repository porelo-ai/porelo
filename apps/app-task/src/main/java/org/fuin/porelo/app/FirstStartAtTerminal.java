package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/** What {@code porelo task start} says and asks of a {@link FirstStart}. */
final class FirstStartAtTerminal {

    private final FirstStart firstStart;

    private final PrintWriter err;

    /**
     * Constructor.
     *
     * @param firstStart The question.
     * @param err Where it is said: nothing a script reads off a start's output changes.
     */
    FirstStartAtTerminal(final FirstStart firstStart, final PrintWriter err) {
        this.firstStart = firstStart;
        this.err = err;
    }

    /**
     * Looks at the checkout, says what it found and, with a person to answer, asks.
     *
     * @param directory Where the person stands.
     * @param offer Who answers, or {@code null} for nobody.
     * @param unasked What to do to be asked, for a start nobody is asked in.
     * @return Where the start goes, or {@code null} for as today: {@code default}, or an
     *     organization's project.
     */
    WorkOrigin.@Nullable Choice choose(
            final Path directory, final @Nullable Offer offer, final String unasked) {
        final FirstStart.Answer answer;
        try {
            answer = firstStart.look(directory);
        } catch (FirstStart.Refused ex) {
            // Outside a checkout the start says so itself.
            return null;
        }
        answer.notes().forEach(this::say);
        try {
            return switch (answer.outcome()) {
                case KNOWN ->
                        new WorkOrigin.Choice(
                                answer.project(),
                                answer.repository(),
                                "'"
                                        + answer.project()
                                        + "', this machine's copy of the project.yml it approved");
                case NOT_DEFAULT -> null;
                case NO_PLUGIN -> {
                    say(
                            "no build-tool plugin is installed, so nothing looked for what this"
                                    + " repository needs; this start runs in '"
                                    + DefaultProject.NAME
                                    + "'. 'porelo ecosystems' lists them.");
                    yield null;
                }
                case NOTHING_RECOGNIZED -> {
                    say(
                            "the build-tool plugins recognized nothing here; this start runs in '"
                                    + DefaultProject.NAME
                                    + "'");
                    yield null;
                }
                case ASK_NEW, ASK_UNKNOWN_FILE -> asked(answer, offer, unasked);
            };
        } catch (FirstStart.Refused ex) {
            say(
                    "not taken: "
                            + org.fuin.porelo.vault.RegistryText.printable(ex.detail())
                            + "; this start runs in '"
                            + DefaultProject.NAME
                            + "'");
            return null;
        } finally {
            answer.discard();
        }
    }

    private WorkOrigin.@Nullable Choice asked(
            final FirstStart.Answer answer, final @Nullable Offer offer, final String unasked)
            throws FirstStart.Refused {
        final boolean isNew = answer.outcome() == FirstStart.Outcome.ASK_NEW;
        final Path file = java.util.Objects.requireNonNull(answer.target());
        if (answer.refusal() != null && answer.found().isEmpty()) {
            throw answer.refusal();
        }
        say(
                isNew
                        ? "this repository has no project.yml. Read from it and this machine:"
                        : "this machine has not approved "
                                + org.fuin.porelo.vault.RegistryText.printable(file.toString())
                                + ", which says:");
        for (final FirstStart.Found each : answer.found()) {
            if (each.unknown()) {
                say(
                        "  unknown  "
                                + each.what()
                                + ": "
                                + each.value()
                                + " (set in "
                                + each.source()
                                + "). A yes writes the file without it.");
            } else {
                say(
                        "  found    "
                                + each.what()
                                + (each.value().isEmpty() ? "" : " " + each.value())
                                + (each.source().isEmpty() ? "" : " (" + each.source() + ")")
                                + (each.note().isEmpty() ? "" : ": " + each.note()));
            }
        }
        for (final String host : answer.hosts()) {
            say(
                    "  opens    "
                            + host
                            + (answer.marked().contains(host)
                                    ? "  <- only this repository names it, and no curated set"
                                    : ""));
        }
        if (answer.refusal() != null) {
            throw answer.refusal();
        }
        if (!isNew) {
            // A summary is a choice of what matters, and a person is answering for all of it.
            say("the file in full, with each file it names:");
            answer.file().lines().forEach(line -> say("  | " + line));
        }
        if (offer == null) {
            say(
                    "nothing written and nothing opened: this start runs in '"
                            + DefaultProject.NAME
                            + "'. "
                            + unasked);
            return null;
        }
        final boolean yes =
                offer.confirm(
                        isNew
                                ? "This repository has no project.yml"
                                : "This project.yml is not approved here",
                        isNew
                                ? "Write project.yml here as project '"
                                        + answer.project()
                                        + "', for you to commit, and start from it?"
                                : "Approve it as project '"
                                        + answer.project()
                                        + "' and start from it?",
                        "",
                        !answer.marked().isEmpty());
        if (!yes) {
            say(
                    "nothing kept: this start runs in '"
                            + DefaultProject.NAME
                            + "', and the next one asks again");
            return null;
        }
        final String project = firstStart.take(answer);
        return new WorkOrigin.Choice(
                project,
                answer.repository(),
                "'"
                        + project
                        + "', from "
                        + org.fuin.porelo.vault.RegistryText.printable(file.toString())
                        + (isNew ? ", which is yours to commit and adapt" : ""));
    }

    private void say(final String words) {
        err.println("porelo: " + words);
        err.flush();
    }
}
