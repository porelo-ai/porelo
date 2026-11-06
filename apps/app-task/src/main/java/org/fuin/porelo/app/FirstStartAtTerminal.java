package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** What {@code porelo task start} says and asks of a {@link FirstStart}. */
final class FirstStartAtTerminal {

    private final FirstStart firstStart;

    private final PrintWriter err;

    private final boolean colour;

    /**
     * Constructor.
     *
     * @param firstStart The question.
     * @param err Where it is said: nothing a script reads off a start's output changes.
     */
    FirstStartAtTerminal(final FirstStart firstStart, final PrintWriter err) {
        this(firstStart, err, colours(Path.of("/proc/self/fd/2"), System.getenv("NO_COLOR")));
    }

    /**
     * Constructor.
     *
     * @param firstStart The question.
     * @param err Where it is said.
     * @param colour Whether a warning is painted; the words say it either way.
     */
    FirstStartAtTerminal(final FirstStart firstStart, final PrintWriter err, final boolean colour) {
        this.firstStart = firstStart;
        this.err = err;
        this.colour = colour;
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
        return choose(directory, offer, unasked, Map.of());
    }

    /**
     * Looks at the checkout as {@link #choose(Path, Offer, String)} does; with a person to answer,
     * the plugins look up what they read before the question, through these proxy settings.
     *
     * @param directory Where the person stands.
     * @param offer Who answers, or {@code null} for nobody, which looks nothing up.
     * @param unasked What to do to be asked, for a start nobody is asked in.
     * @param proxies The person's proxy settings, which only a plugin's process gets.
     * @return Where the start goes, or {@code null} for as today.
     */
    WorkOrigin.@Nullable Choice choose(
            final Path directory,
            final @Nullable Offer offer,
            final String unasked,
            final Map<String, String> proxies) {
        final FirstStart.Answer answer;
        try {
            answer = firstStart.look(directory, offer == null ? null : proxies);
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
                            "no ecosystem adapter is installed, so nothing looked for what this"
                                    + " repository needs; this start runs in '"
                                    + DefaultProject.NAME
                                    + "'. 'porelo ecosystems' lists them.");
                    yield null;
                }
                case NOTHING_RECOGNIZED -> {
                    say(
                            "the ecosystem adapters recognized nothing here; this start runs in '"
                                    + DefaultProject.NAME
                                    + "'");
                    yield null;
                }
                case ASK_NEW, ASK_UNKNOWN_FILE -> asked(answer, offer, unasked);
            };
        } catch (FirstStart.Refused ex) {
            warn(
                    "not taken: "
                            + org.fuin.porelo.vault.RegistryText.printable(ex.detail())
                                    .replaceFirst("\\.$", "")
                            + ". This start runs in '"
                            + DefaultProject.NAME
                            + "', not from this file");
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
            final String words =
                    "nothing written and nothing opened: this start runs in '"
                            + DefaultProject.NAME
                            + "'. "
                            + unasked;
            // A file standing here and passed over is not the same as no file at all.
            if (isNew) {
                say(words);
            } else {
                warn(words);
            }
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

    /**
     * Returns whether a warning is painted: by where it goes, standard error, as a log taking it
     * must not hold escapes while the rest of a start is on a terminal.
     *
     * @param descriptor The link of the descriptor it goes to.
     * @param noColor {@code NO_COLOR}, or {@code null} when it is not set; set at all, even empty,
     *     it means none.
     * @return Whether to paint.
     */
    static boolean colours(final Path descriptor, final @Nullable String noColor) {
        return noColor == null && StandardInput.isTerminal(descriptor);
    }

    /** A start going on without the file a person wrote must not pass as one more line. */
    private void warn(final String words) {
        final String line = "porelo: warning: " + words;
        err.println(colour ? "\u001b[33m" + line + "\u001b[0m" : line);
        err.flush();
    }

    private void say(final String words) {
        err.println("porelo: " + words);
        err.flush();
    }
}
