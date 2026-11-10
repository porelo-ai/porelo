package org.fuin.porelo.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.fuin.porelo.machines.Ssh;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/** A real detached stub login supplies the credential a daemon start is waiting for. */
public final class SignInStartSteps {
    private final World world;
    private @Nullable String login;
    private @Nullable String provider;
    private @Nullable String repository;
    private @Nullable String directory;
    private @Nullable String container;
    private @Nullable Future<Ssh.Output> start;
    private final java.util.concurrent.ExecutorService calls =
            Executors.newVirtualThreadPerTaskExecutor();

    /** Creates steps for this scenario's machine and vault. */
    public SignInStartSteps(World world) {
        this.world = world;
    }

    private static String call(String method, Map<String, Object> parameters) {
        return "timeout 600 varlinkctl call unix:\"$XDG_RUNTIME_DIR/porelo/porelod.sock\" org.fuin.porelo.Tasks1."
                + method
                + " "
                + Shell.quote(Json.write(parameters));
    }

    /** Polls the named login until it reaches its input gate. */
    static String awaitingSignIn(String name) {
        return "timeout 10 sh -c "
                + Shell.quote(
                        "until podman logs \"$1\" 2>/dev/null | grep -q 'stub: waiting for sign-in'; do sleep 0.1; done")
                + " _ "
                + Shell.quote(name);
    }

    /** Starts a sign-in that cannot write its credential until input is sent to its terminal. */
    @Given("a {word} sign-in is running and waiting to store its credential")
    public void aSignInWaits(String agent) throws IOException {
        final Ssh.Output stopped = world.run("systemctl --user stop " + PoreloSteps.UNIT);
        assertThat(stopped.status()).as("stop the scenario's daemon: %s", stopped.all()).isZero();
        final Ssh.Output daemon =
                world.run(
                        PoreloSteps.DAEMON_START.replace(
                                "--setenv=PATH=",
                                "--setenv=PORELO_AGENT_STUB_LOGIN_WAIT=yes --setenv=PATH="));
        assertThat(daemon.status())
                .as("start the scenario's controlled daemon: %s", daemon.all())
                .isZero();
        final Ssh.Output declared = world.run("porelo agents --verbose");
        assertThat(declared.status())
                .as("read the installed stub's declaration: %s", declared.all())
                .isZero();
        final var stub =
                java.util.regex.Pattern.compile("(?m)^stub\\s[^\\r\\n]*(?:\\R +[^\\r\\n]*)*")
                        .matcher(declared.out());
        if (!stub.find()) {
            throw new AssertionError(
                    "the installed agents do not include the stub: " + declared.out());
        }
        final var selected =
                java.util.regex.Pattern.compile("provider: (\\S+) \\(default\\)")
                        .matcher(stub.group());
        if (!selected.find()) {
            throw new AssertionError("the stub declares no default provider: " + stub.group());
        }
        provider = selected.group(1);
        final Ssh.Output made = world.run("mktemp -d");
        assertThat(made.status()).as("make the scenario's repository: %s", made.all()).isZero();
        final String base = made.out().strip();
        directory = base;
        final String repo =
                "b534"
                        + base.substring(base.lastIndexOf('/') + 1)
                                .replaceAll("[^A-Za-z0-9]", "")
                                .toLowerCase(java.util.Locale.ROOT);
        final Ssh.Output added =
                world.run(
                        """
                set -eu
                base=%s
                git init -q --bare --initial-branch=main "$base/repo.git"
                git init -q --initial-branch=main "$base/seed"
                git -C "$base/seed" -c user.name=Test -c user.email=test@example.org commit -q --allow-empty -m first
                git -C "$base/seed" push -q "$base/repo.git" HEAD:main
                porelo project default add "$base/repo.git" --name %s
                """
                                .formatted(Shell.quote(base), Shell.quote(repo)));
        assertThat(added.status())
                .as("register the scenario's repository: %s", added.all())
                .isZero();
        repository = repo;
        final Ssh.Output began =
                world.run(call("AgentSignInStart", Map.of("agent", agent, "force", true)));
        assertThat(began.status()).as("start the stub's sign-in: %s", began.all()).isZero();
        final Object parsed = Json.parseExact(began.out());
        if (!(parsed instanceof Map<?, ?> answer)
                || !(answer.get("container") instanceof String name)) {
            throw new AssertionError("sign-in start did not answer a container: " + began.out());
        }
        assertThat(answer.get("started"))
                .as("the scenario must create its own sign-in, not take another one's")
                .isEqualTo(true);
        login = name;
        final Ssh.Output waiting = world.run(awaitingSignIn(name));
        assertThat(waiting.status())
                .as("the login reached its input gate: %s", waiting.all())
                .isZero();
        final Ssh.Output logs = world.run("podman logs " + Shell.quote(name));
        assertThat(logs.out())
                .as("the login must still be waiting before the task is requested")
                .contains("stub: waiting for sign-in");
    }

    /** Requests a task while the sign-in is still pending. */
    @When("a daemon start of the {word} task waits for that sign-in")
    public void aStartWaits(String agent) throws Exception {
        final String repo =
                java.util.Objects.requireNonNull(repository, "the scenario made its repository");
        final String selected =
                java.util.Objects.requireNonNull(provider, "the stub declared its provider");
        final String task = "porelo-default_" + repo;
        final Ssh.Output before = world.run("podman container exists " + Shell.quote(task));
        assertThat(before.status())
                .as("the scenario must not take a pre-existing task")
                .isEqualTo(1);
        container = task;
        // The call waits on another thread, which holds no account of its own: the scenario's
        // account and environment are taken here, on the step's thread, and handed to it.
        final Machine machine = world.machine();
        final String environment = world.environment();
        final Future<Ssh.Output> pending =
                calls.submit(
                        () ->
                                machine.run(
                                        environment
                                                + call(
                                                        "Start",
                                                        Map.of(
                                                                "project",
                                                                "default",
                                                                "repository",
                                                                repo,
                                                                "task",
                                                                repo,
                                                                "agent",
                                                                agent,
                                                                "provider",
                                                                selected,
                                                                "mode",
                                                                "AGENT",
                                                                "noGate",
                                                                true,
                                                                "clearance",
                                                                "deny",
                                                                "waitForSignIn",
                                                                true))));
        start = pending;
        assertThatThrownBy(() -> pending.get(300, TimeUnit.MILLISECONDS))
                .as("the start must wait before the credential is stored")
                .isInstanceOf(TimeoutException.class);
        final Ssh.Output absent =
                world.run(
                        "podman container exists "
                                + Shell.quote(java.util.Objects.requireNonNull(container)));
        assertThat(absent.status()).as("a waiting start has made no container").isEqualTo(1);
    }

    /** Completes the login through its terminal; Porelo's watcher stores the credential. */
    @When("the {word} sign-in is completed and its credential is stored")
    public void theSignInCompletes(String agent) throws IOException {
        world.run(
                "printf '\\n' | timeout 60 podman attach --sig-proxy=false "
                        + Shell.quote(java.util.Objects.requireNonNull(login)));
        final Ssh.Output ended = world.run(call("AgentSignInWait", Map.of("agent", agent)));
        assertThat(ended.status()).as("wait for the credential store: %s", ended.all()).isZero();
        final Object parsed = Json.parseExact(ended.out());
        if (!(parsed instanceof Map<?, ?> answer)) {
            throw new AssertionError("sign-in wait did not answer an object: " + ended.out());
        }
        assertThat(answer.get("stored"))
                .as("the real login's watcher stored the credential")
                .isEqualTo(true);
    }

    /** Verifies the waiting call continued and its real task is running. */
    @Then("the waiting {word} task is running")
    public void theTaskRuns(String agent) throws Exception {
        final Ssh.Output result =
                java.util.Objects.requireNonNull(start).get(600, TimeUnit.SECONDS);
        assertThat(result.status()).as("the waiting start continued: %s", result.all()).isZero();
        final Object parsed = Json.parseExact(result.out());
        if (!(parsed instanceof Map<?, ?> answer)) {
            throw new AssertionError("start did not answer an object: " + result.out());
        }
        assertThat(answer.get("exitCode")).as("the task start succeeded").isEqualTo(0L);
        final Ssh.Output running =
                world.run(
                        "podman inspect --format '{{.State.Running}}' "
                                + Shell.quote(java.util.Objects.requireNonNull(container)));
        assertThat(running.status()).as("inspect the started task: %s", running.all()).isZero();
        assertThat(running.out().strip())
                .as("the waiting %s task now runs", agent)
                .isEqualTo("true");
    }

    /** Removes only what this scenario made, before its daemon and vault are closed. */
    @After(order = 100)
    public void cleanUp() throws IOException {
        final String signIn = login;
        final String task = container;
        final String repo = repository;
        final String base = directory;
        if (signIn != null) world.run("podman rm --force " + Shell.quote(signIn));
        final Future<Ssh.Output> pending = start;
        if (pending != null) {
            try {
                pending.get(600, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException ex) {
                // The scenario's assertions name a failed call; cleanup still removes its task.
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while cleaning up the waiting start", ex);
            } catch (TimeoutException ex) {
                throw new IOException("the waiting start did not end before cleanup", ex);
            } finally {
                calls.shutdownNow();
            }
        } else {
            calls.shutdownNow();
        }
        if (task != null && pending != null)
            world.run("porelo task remove " + Shell.quote(task) + " --force");
        if (repo != null) world.run("porelo project default remove " + Shell.quote(repo));
        if (base != null) world.run("rm -rf " + Shell.quote(base));
    }
}
