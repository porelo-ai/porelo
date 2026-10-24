package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/** The words and transitions of a task's start, shared by every surface. */
public final class StartSteps implements TaskRunner.StartProgress {
    private final Consumer<StartStep> consumer;
    private final StartStepFile recording;
    private @Nullable StartStep current;
    private @Nullable Path directory;
    private String scheme = "";
    private String refusal = "";

    /**
     * Creates a listener.
     *
     * @param consumer Where transitions go.
     */
    public StartSteps(Consumer<StartStep> consumer) {
        this(consumer, new PrintWriter(System.err, true));
    }

    /**
     * Creates a listener with a destination for best-effort progress warnings.
     *
     * @param consumer Where transitions go.
     * @param warnings Where the first persistence failure is reported.
     */
    public StartSteps(Consumer<StartStep> consumer, PrintWriter warnings) {
        this.consumer = consumer;
        this.recording = new StartStepFile(warnings);
    }

    /**
     * Begins a stage.
     *
     * @param step The stage.
     */
    public void started(StartStep.Step step) {
        if (current != null && current.step() == step) {
            return;
        }
        if (current != null) {
            done(current.step());
        }
        refusal = "";
        publish(step, StartStep.State.STARTED, "", "");
    }

    /**
     * Begins enrollment on a transport.
     *
     * @param scheme The transport scheme.
     */
    public void conversation(String scheme) {
        this.scheme = scheme;
        started(StartStep.Step.CONVERSATION);
    }

    /**
     * Reports an outside wait.
     *
     * @param step The stage.
     * @param waitingOn The dependency.
     */
    public void waiting(StartStep.Step step, String waitingOn) {
        publish(step, StartStep.State.WAITING, StartStep.cleanWaitingOn(waitingOn), "");
    }

    /**
     * Completes a stage.
     *
     * @param step The stage.
     */
    public void done(StartStep.Step step) {
        publish(step, StartStep.State.DONE, "", "");
        current = null;
        if (step == StartStep.Step.HELPERS || step == StartStep.Step.AGENT) {
            clear();
        }
    }

    /**
     * Refuses a stage.
     *
     * @param step The stage.
     * @param cause Why it failed.
     */
    public void failed(StartStep.Step step, String cause) {
        publish(step, StartStep.State.FAILED, "", StartStep.cleanCause(cause));
        current = null;
        clear();
    }

    /**
     * Keeps the first printed refusal of this stage.
     *
     * @param line The original error line.
     */
    public void refusal(String line) {
        if (refusal.isEmpty()) {
            refusal = StartStep.cleanCause(line.lines().findFirst().orElse(""));
        }
    }

    /**
     * Refuses an active dependency stage before its wrapper can hide the last error line.
     *
     * @param step The stage that uses the dependency.
     * @param error The dependency's stderr.
     */
    public void dependencyFailed(StartStep.Step step, String error) {
        if (current != null && current.step() == step) {
            dependencyFailed(error);
        }
    }

    /**
     * Refuses the current step with the dependency's last diagnostic.
     *
     * @param stderr The dependency's stderr.
     */
    public void dependencyFailed(String stderr) {
        if (current != null) {
            failed(current.step(), stderr);
        }
    }

    /**
     * Ends a launch's active stage.
     *
     * @param code Its exit code.
     */
    public void finish(int code) {
        if (current != null) {
            if (code == 0) {
                done(current.step());
            } else {
                failed(current.step(), refusal);
            }
        }
        clear();
    }

    /**
     * Starts recording after the task's state exists.
     *
     * @param directory The existing state directory.
     */
    public void recordIn(Path directory) {
        this.directory = directory;
        if (current != null) {
            recording.write(directory, current);
        }
    }

    /** Removes a phase when the launch ends. */
    public void clear() {
        final Path state = directory;
        if (state != null) {
            recording.clear(state);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void imageStarted() {
        started(StartStep.Step.IMAGE);
        waiting(StartStep.Step.IMAGE, "podman build");
    }

    /** {@inheritDoc} */
    @Override
    public void imageDone() {
        done(StartStep.Step.IMAGE);
    }

    /** {@inheritDoc} */
    @Override
    public void containerStarted() {
        started(StartStep.Step.CONTAINER);
    }

    /** {@inheritDoc} */
    @Override
    public void containerDone() {
        done(StartStep.Step.CONTAINER);
    }

    /**
     * Renders one transition.
     *
     * @param out The terminal.
     * @param step The transition.
     */
    public static void render(PrintWriter out, StartStep step) {
        switch (step.state()) {
            case STARTED -> out.println("step      " + step.text());
            case WAITING -> out.println("waiting   " + step.waitingOn());
            case FAILED ->
                    out.println(
                            "failed    "
                                    + step.text()
                                    + (step.cause().isEmpty() ? "" : ": " + step.cause()));
            case DONE -> {}
        }
        out.flush();
    }

    private void publish(
            StartStep.Step step, StartStep.State state, String waitingOn, String cause) {
        final String text =
                switch (step) {
                    case SOURCE -> "Reading the project and its source";
                    case CHECKS -> "Checking the machine, the egress and the upstream";
                    case CREDENTIALS -> "Opening the vault and the task's credentials";
                    case WORKSPACE -> "Bringing the gate's mirror up to its upstream";
                    case CONVERSATION -> "Joining the project's conversation on " + scheme;
                    case IMAGE -> "Preparing and building the task's image";
                    case CONTAINER -> "Creating and starting the container";
                    case HELPERS -> "Starting the task's gate, credential proxy and helpers";
                    case AGENT -> "Running the agent";
                };
        final StartStep event = new StartStep(step, state, text, waitingOn, cause);
        current = event;
        final Path path = directory;
        if (path != null) {
            recording.write(path, event);
        }
        consumer.accept(event);
    }
}
