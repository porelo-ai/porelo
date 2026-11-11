package org.fuin.porelo.app;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** A command that reads the organization: one answer, a refusal by its reason, or a failure. */
public abstract class OrgReading implements Callable<Integer>, PoreloFactory.ContextAware {

    @Option(names = "--json", description = "Answers as JSON.")
    private boolean json;

    @Spec private CommandSpec spec;

    /**
     * Says what is about to take long, where a program reading the answer does not read it.
     *
     * @param phase What it is doing.
     */
    final void saying(final String phase) {
        spec.commandLine().getErr().println("porelo: " + phase);
        spec.commandLine().getErr().flush();
    }

    private @Nullable PoreloContext context;

    /** Replaced by a test, which has no forge to ask. */
    OrgAccess.Opener opener = OrgAccess::real;

    OrgAccess.@Nullable Reading displayed;
    private boolean refreshAfter;

    /** Replaced by tests; a refresh belongs to a separate process after this answer exits. */
    Refresher refresher = OrgReading::refreshLater;

    @FunctionalInterface
    interface Refresher {
        void start(PoreloContext context, OrgAccess.Reading reading) throws IOException;
    }

    /** Selects the display path without changing the fresh path shared by decisions. */
    final OrgAccess.Reading display(PoreloContext machine, boolean refresh) throws IOException {
        OrgAccess.Reading reading =
                refresh ? OrgAccess.refresh(machine, opener) : OrgAccess.show(machine, opener);
        if (!reading.answers().whole()) {
            reading =
                    new OrgAccess.Reading(
                            reading.answers(),
                            null,
                            null,
                            reading.stale() && reading.why() != null
                                    ? reading.why()
                                    : OrgAccess.StaleWhy.incomplete());
        }
        displayed = reading;
        refreshAfter = !refresh && reading.stale();
        return reading;
    }

    private static void refreshLater(PoreloContext machine, OrgAccess.Reading reading)
            throws IOException {
        final String repository = OrgAccess.repository(machine).orElseThrow();
        final java.nio.file.Path log =
                machine.paths().xdg().state().resolve("organization/refresh.log");
        org.fuin.porelo.wire.Directories.create(log.getParent());
        if (!java.nio.file.Files.exists(log, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            try {
                java.nio.file.Files.createFile(
                        log,
                        java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                                java.nio.file.attribute.PosixFilePermissions.fromString(
                                        "rw-------")));
            } catch (java.nio.file.FileAlreadyExistsException createdMeanwhile) {
                // Another display scheduled a refresh at the same time.
            }
        }
        if (!java.nio.file.Files.isRegularFile(log, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("the organization refresh log is no regular file");
        }
        java.nio.file.Files.setPosixFilePermissions(
                log, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        final ProcessBuilder process =
                new ProcessBuilder(
                        PoreloBinary.path(),
                        "org",
                        "refresh-view",
                        "--repository",
                        repository,
                        "--after",
                        reading.checkedAt() == null ? "" : reading.checkedAt().toString());
        process.directory(java.nio.file.Path.of("/").toFile());
        process.redirectInput(java.nio.file.Path.of("/dev/null").toFile());
        process.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        process.redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()));
        process.start();
    }

    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() throws IOException {
        final PoreloContext machine = Objects.requireNonNull(context, "picocli sets the context");
        displayed = null;
        refreshAfter = false;
        try {
            final Map<String, @Nullable Object> answer = answer(machine);
            if (json) {
                OrgOutput.answer(spec, answer);
            } else {
                text(answer);
            }
            refreshAfterAnswer(machine);
            return 0;
        } catch (Refused refused) {
            final Map<String, @Nullable Object> extra = new java.util.LinkedHashMap<>();
            final OrgAccess.Reading reading = displayed;
            if (reading != null) {
                extra.put("stale", reading.stale());
                reading.age(extra);
            }
            final int exit = OrgOutput.refuse(spec, json, refused, extra);
            if (!json && reading != null) staleness(extra);
            refreshAfterAnswer(machine);
            return exit;
        } catch (IOException | RuntimeException ex) {
            OrgOutput.failed(spec, json, String.valueOf(ex.getMessage()));
            throw ex;
        }
    }

    private void refreshAfterAnswer(PoreloContext machine) {
        final OrgAccess.Reading reading = displayed;
        if (refreshAfter && reading != null) {
            try {
                refresher.start(machine, reading);
            } catch (IOException | RuntimeException ex) {
                saying("the organization refresh could not start; run porelo org --refresh");
            }
        }
    }

    /**
     * Answers, in the order the contract names the fields.
     *
     * @param machine The machine.
     * @return The answer.
     * @throws IOException When something unexpected happened.
     * @throws Refused When it says no.
     */
    abstract Map<String, @Nullable Object> answer(PoreloContext machine) throws IOException;

    /**
     * Says, beside what it shows, that a state is only the last one trusted.
     *
     * @param answer The answer.
     */
    final void staleness(final Map<String, @Nullable Object> answer) {
        if (Boolean.TRUE.equals(answer.get("stale"))) {
            line("Stale: this is the last state trusted here; refresh checks it again");
            if (answer.get("stale_why") instanceof Map<?, ?> why) {
                line("Stale because: " + because(why));
            }
        }
        if (answer.get("checked_at") != null) {
            line(
                    "Checked: "
                            + answer.get("checked_at")
                            + " ("
                            + answer.get("age_seconds")
                            + " s ago)");
        } else if (Boolean.TRUE.equals(answer.get("stale")) && answer.containsKey("checked_at")) {
            line("Checked: unknown");
        }
    }

    /**
     * Says why an answer is stale, in a person's words.
     *
     * @param why The answer's {@code stale_why}.
     * @return The sentence.
     */
    static String because(final Map<?, ?> why) {
        final Object cause = why.get("cause");
        final String said =
                why.get("said") instanceof String words && !words.isBlank() ? ": " + words : "";
        final String at = why.get("at") instanceof String when ? " (tried " + when + ")" : "";
        final String meaning =
                switch (String.valueOf(cause)) {
                    case "kept" -> "the state kept here is shown, and a check runs after it";
                    case "host-key" -> "the forge's ssh host key is not trusted on this machine";
                    case "key-refused" ->
                            "the forge refused the key offered; is the ssh agent reachable and"
                                    + " holding the key the forge lists?";
                    case "no-network" -> "the forge could not be reached over the network";
                    case "timeout" -> "the forge did not answer in time";
                    case "incomplete" -> "the check could not judge everything new";
                    case "failed" -> "the check failed";
                    default -> "the forge could not be reached";
                };
        return cause + ", " + meaning + said + at;
    }

    /**
     * Says an answer to a person.
     *
     * @param answer The answer.
     */
    abstract void text(Map<String, @Nullable Object> answer);

    /** Prints a line for a person, whatever a file put into it. */
    final void line(final String line) {
        OrgOutput.line(spec, line);
    }
}
