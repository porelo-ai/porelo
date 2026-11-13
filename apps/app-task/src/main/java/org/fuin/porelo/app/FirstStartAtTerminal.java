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

    private final boolean full;

    /** What the last look found, or {@code null} before one, or for a directory in no checkout. */
    private FirstStart.@Nullable Outcome looked;

    private static final String ONLY_HERE =
            "  <- only this repository names it, and no curated set";

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
        this(firstStart, err, colour, false);
    }

    private FirstStartAtTerminal(
            final FirstStart firstStart,
            final PrintWriter err,
            final boolean colour,
            final boolean full) {
        this.firstStart = firstStart;
        this.err = err;
        this.colour = colour;
        this.full = full;
    }

    /**
     * Returns this question saying everything: each note of the adapters whole, and each finding
     * with its file and note. Without it a person reads one line, and what bears on trust.
     *
     * @param notes Whether to say everything.
     * @return The question.
     */
    FirstStartAtTerminal withNotes(final boolean notes) {
        return new FirstStartAtTerminal(firstStart, err, colour, notes);
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
        looked = answer.outcome();
        final boolean asking =
                answer.outcome() == FirstStart.Outcome.ASK_NEW
                        || answer.outcome() == FirstStart.Outcome.ASK_UNKNOWN_FILE;
        if (full || !asking) {
            answer.notes().forEach(this::say);
        }
        try {
            return switch (answer.outcome()) {
                case KNOWN ->
                        DefaultProject.is(answer.project())
                                ? firstStart.defaultChoice(directory)
                                : new WorkOrigin.Choice(
                                        answer.project(),
                                        answer.repository(),
                                        "'"
                                                + answer.project()
                                                + "', this machine's copy of the project.yml it"
                                                + " approved");
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
        if (!full) {
            summed(answer, isNew, file);
        } else {
            said(answer, isNew, file);
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
                                ? "This repository has no porelo.yml"
                                : "This project.yml is not approved here",
                        isNew
                                ? "Write porelo.yml here, for you to commit, allow its settings,"
                                        + " and start in 'default'?"
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

    /** Everything, as {@code --notes} asks: each finding with its file and its note. */
    private void said(final FirstStart.Answer answer, final boolean isNew, final Path file) {
        say(
                isNew
                        ? "this repository has no porelo.yml. Read from it and this machine:"
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
            say("  opens    " + host + (answer.marked().contains(host) ? ONLY_HERE : ""));
        }
    }

    /**
     * One line a person reads at a glance, and under it, whole, only what bears on trust: each
     * image line, as it runs as root of the build, and each host only this checkout names.
     */
    private void summed(final FirstStart.Answer answer, final boolean isNew, final Path file) {
        final java.util.List<String> items = new java.util.ArrayList<>();
        final java.util.List<String> whole = new java.util.ArrayList<>();
        for (final FirstStart.Found each : answer.found()) {
            if (each.unknown()) {
                items.add("leaves out " + each.what());
            } else if (each.what().startsWith("image ")) {
                whole.add(each.what() + "  " + each.value());
            } else if ("credential needed".equals(each.what())) {
                items.add("needs credential " + each.value());
            } else {
                items.add(each.what() + (each.value().isEmpty() ? "" : " " + each.value()));
            }
        }
        final int hosts = answer.hosts().size();
        final int notes = answer.notes().size();
        say(
                (isNew
                                ? "this repository has no porelo.yml"
                                : "this machine has not approved "
                                        + org.fuin.porelo.vault.RegistryText.printable(
                                                file.toString()))
                        + (items.isEmpty() ? "" : "; found " + String.join(", ", items))
                        + "; opens "
                        + hosts
                        + (hosts == 1 ? " host" : " hosts")
                        + " (details"
                        + (notes == 0 ? "" : " and " + notes + (notes == 1 ? " note" : " notes"))
                        + ": porelo task start --notes)");
        whole.forEach(line -> say("  " + line));
        for (final String host : answer.marked()) {
            say("  opens    " + host + ONLY_HERE);
        }
    }

    /**
     * Returns whether the last look put the adapters' findings to the person, or said they found
     * nothing: a start then asks nothing more of them.
     *
     * @return Whether the adapters were spoken of.
     */
    boolean spokeOfAdapters() {
        return looked != null
                && looked != FirstStart.Outcome.KNOWN
                && looked != FirstStart.Outcome.NOT_DEFAULT;
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
