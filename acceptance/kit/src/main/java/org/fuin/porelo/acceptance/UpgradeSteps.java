package org.fuin.porelo.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.After;
import io.cucumber.java.Scenario;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.fuin.porelo.machines.Ssh;
import org.fuin.porelo.wire.Json;

/** One real package upgrade, confined to an explicitly named disposable account. */
public final class UpgradeSteps {
    private static final String ROOT = "$HOME/target/porelo-upgrade";
    private static final String DRIVER = "python3 \"" + ROOT + "/driver.py\" ";
    private final World world;
    private final PoreloSteps porelo;
    private boolean staged;
    private final StringBuilder installationReport = new StringBuilder();

    /** Shares the scenario's connection, vault and daemon steps. */
    public UpgradeSteps(World world, PoreloSteps porelo) {
        this.world = world;
        this.porelo = porelo;
    }

    /** Checks the pins before changing the account, and installs the predecessor's full payload. */
    @Given("the pinned predecessor is installed in the isolated upgrade account")
    public void installPredecessor() throws IOException {
        final Map<String, String> pins = new LinkedHashMap<>();
        for (final String name :
                java.util.List.of(
                        "UPGRADE_USER",
                        "BASELINE",
                        "BASELINE_SHA256",
                        "BASELINE_VERSION",
                        "CANDIDATE",
                        "CANDIDATE_SHA256",
                        "CANDIDATE_VERSION",
                        "STUB",
                        "STUB_SHA256",
                        "MCP",
                        "MCP_SHA256",
                        "FILTER",
                        "FILTER_SHA256")) {
            final String variable = "PORELO_ACCEPTANCE_" + name;
            final String value = System.getenv(variable);
            if (value == null || value.isBlank()) {
                throw new AssertionError("Set " + variable + " before running the upgrade feature");
            }
            pins.put(name, value);
        }
        final String script;
        try (final var in =
                Objects.requireNonNull(
                        UpgradeSteps.class.getResourceAsStream("task-upgrade.py"),
                        "the acceptance kit carries its upgrade driver")) {
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        check(
                world.run(
                        "umask 077; mkdir -p \"$HOME/target\" && mkdir \""
                                + ROOT
                                + "\" && cat > \""
                                + ROOT
                                + "/driver.py\"",
                        script));
        staged = true;
        final Ssh.Output installed = run("prepare", Json.write(pins));
        installationReport.append(installed.out());
    }

    /** Starts the old task with the baseline, never by renaming a candidate's container. */
    @When("the predecessor starts the kept upgrade task")
    public void startOldTask() throws IOException {
        run("start-old", "");
    }

    /**
     * Opens the old task's agent session as a person does, with the predecessor's attach: its start
     * brings up the container alone, and only an attach starts the agent in its session.
     */
    @When("the predecessor attaches to the kept upgrade task and detaches")
    public void firstAttach() throws IOException {
        try (final Terminal terminal = world.openTerminal()) {
            terminal.type("porelo task attach porelo-porelo-kept");
            terminal.await("stub: at work", Terminal.BUILD_PATIENCE);
            terminal.press(new byte[] {2, 'd'});
        }
    }

    /**
     * Saves observable workspace, session, mail, route and child state before replacing anything.
     */
    @When("the old task keeps its upgrade witnesses")
    public void keepWitnesses() throws IOException {
        run("witnesses", "");
    }

    /** Replaces CLI, daemon and hooks while the task and its old helpers remain running. */
    @When("the candidate replaces the predecessor while the old task runs")
    public void installCandidate() throws IOException {
        porelo.theDaemonsUnitIsStopped();
        installationReport.append(run("install-candidate", "").out());
        porelo.theDaemonIsStartedAgain();
        run("installation", "");
    }

    /** Proves the old container and helpers survived, and its mounted routes still answer. */
    @Then("the old upgrade task still owns its running container and helpers")
    public void stillRunning() throws IOException {
        run("running", "");
    }

    /** Exercises streams and child ownership through the old endpoint after daemon recreation. */
    @Then("the old upgrade task's watches and MCP ownership return")
    public void watchesReturn() throws IOException {
        run("watches", "");
    }

    /** Uses a real terminal for attach; the session's first input must still be remembered. */
    @Then("the old upgrade task can be attached to")
    public void attach() throws IOException {
        try (final Terminal terminal = world.openTerminal()) {
            terminal.type("porelo task attach porelo-porelo-kept");
            terminal.await("upgrade-session-before");
            terminal.press(new byte[] {2, 'd'});
        }
    }

    /** Resumes by logical identity, so a second container under the new spelling cannot pass. */
    @When("the old upgrade task is stopped and resumed by logical name")
    public void resume() throws IOException {
        run("resume", "");
    }

    /** Verifies retained bytes, session, signed mail and the original route token after resume. */
    @Then("the resumed upgrade task retains its witnesses")
    public void retained() throws IOException {
        run("retained", "");
    }

    /** Checks both new encodings against containers actually created by the candidate. */
    @When("new upgrade tasks are started with the candidate's names")
    public void newNames() throws IOException {
        run("new-names", "");
    }

    /** Removes only containers whose ids this scenario recorded, even after a failed assertion. */
    @Then("only the upgrade scenario's tasks are removed")
    public void removeTasks() throws IOException {
        run("remove-tasks", "");
    }

    /** Runs before the shared daemon and vault teardown, reporting any cleanup failure. */
    @After(order = 200)
    public void cleanUp() throws IOException {
        if (staged) {
            run("cleanup", "");
        }
    }

    /** Keeps the package versions and checked digests with the scenario's run report. */
    @After(order = 201)
    public void reportInstallation(Scenario scenario) {
        if (!installationReport.isEmpty()) {
            scenario.log(installationReport.toString());
        }
    }

    private Ssh.Output run(String action, String input) throws IOException {
        final Ssh.Output output = world.run(DRIVER + Shell.quote(action), input);
        check(output);
        return output;
    }

    private void check(Ssh.Output output) {
        assertThat(output.status()).as("upgrade script: %s", world.redact(output.all())).isZero();
    }
}
