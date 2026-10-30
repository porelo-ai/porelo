package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.agent.api.AgentDefinition;
import org.fuin.porelo.agent.api.AgentDefinitionReader;
import org.fuin.porelo.agent.api.LoginFlow;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.project.SecurityClass;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for logging in with an agent.
 *
 * <p>What can be asserted without a real agent is the shape of the refusals and the shape of the
 * image this builds - and the second matters more than it looks, because a login container that
 * came out looking like a task container would be given a task's ruleset and a task's broker, and
 * an agent cannot log in through a broker that swaps in the credential it is trying to obtain.
 */
class AgentLoginTest {

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private final StringWriter out = new StringWriter();

    private PoreloContext context(Path dir) {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), command -> 0);
    }

    @Test
    void sweepsALoginContainerAnEarlierRunLeftBehind(@TempDir Path dir) {

        // Reported from a machine carrying one thirteen hours old, outliving an interrupted login.
        // A login container is never reused - the name carries the millisecond it was made - so one
        // still there is litter, and a teardown cannot cover a kill.
        runner.answering("ps", "porelo-login-1788886971400\nporelo-uc-shell-1\n");

        // Swept by a login that passed its refusals, before anything is built.
        AgentLogin.login(
                context(dir),
                definition(dir, "paste-code"),
                false,
                false,
                new PrintWriter(out, true),
                directory -> false,
                (command, directory) ->
                        new AgentLogin.Result(
                                AgentLogin.Outcome.NOTHING_TO_COLLECT, "", "", 0, ""));

        assertThat(runner.lines())
                .anyMatch(
                        line ->
                                line.startsWith("podman rm")
                                        && line.contains("porelo-login-1788886971400"));
        // And nothing else: a task is not litter, whatever state it is in.
        assertThat(runner.lines()).noneMatch(line -> line.contains("porelo-uc-shell-1"));
    }

    @Test
    void aMachineWithNoAgentSaysSoRatherThanFailing(@TempDir Path dir) {

        final AgentLogin.Result result =
                AgentLogin.login(context(dir), null, false, false, new PrintWriter(out, true));

        assertThat(result.outcome()).isEqualTo(AgentLogin.Outcome.NO_SUCH_AGENT);
        assertThat(result.detail()).isNotBlank();
        assertThat(runner.invocations()).as("nothing is swept for a login refused").isEmpty();
    }

    @Test
    void anAgentNotInstalledHasNoLoginToNameAsAWayOut(@TempDir Path dir) {
        assertThat(AgentLogin.signsInHere(context(dir), "absent")).isFalse();
    }

    @Test
    void theHintFollowsTheDeclaredFlow() {
        assertThat(String.join("\n", AgentLogin.hint(null, "box")))
                .as("an undeclared flow keeps the forward it always had")
                .contains("ssh -L <port>:localhost:<port> box");
        assertThat(String.join("\n", AgentLogin.hint(LoginFlow.PASTE_CODE, "box")))
                .contains("paste the returned code")
                .doesNotContain("ssh -L");
        assertThat(String.join("\n", AgentLogin.hint(LoginFlow.DEVICE_CODE, "box")))
                .contains("device code")
                .doesNotContain("ssh -L");
        assertThat(AgentLogin.hint(LoginFlow.LOOPBACK, "box")).isEmpty();
    }

    @Test
    void forceIsWhatDistinguishesADeliberateSecondLogin() {

        // The check itself needs a real agent to exercise - it asks the agent's own extractor
        // whether this machine already holds a credential - so what is pinned here is that the
        // decision has a switch at all, and that it is off by default. A second authorization may
        // invalidate the first, and that is the provider's business rather than something Porelo
        // can promise either way.
        assertThat(AgentLogin.Outcome.valueOf("ALREADY_SIGNED_IN")).isNotNull();
    }

    @Test
    void theLoginImageCarriesSomethingForTheAgentToOpenAUrlWith() {

        // Without it the login stops dead: an agent asked to authenticate tries to open a browser,
        // a container has none and no display, and the attempt neither succeeds nor reports
        // anything - leaving somebody at a prompt that never continues, inside a container they did
        // not know how to leave. That is what happened the first time.
        final String rendered =
                org.fuin.porelo.runtime.Containerfile.render(
                        AgentLogin.loginProject(),
                        org.fuin.porelo.runtime.ImageLayers.none()
                                .and(AgentLogin.browserShim(), java.util.List.of()));

        // All three standard names, because which one an agent reaches for is its own business.
        assertThat(rendered)
                .contains("/usr/local/bin/xdg-open")
                .contains("sensible-browser")
                .contains("www-browser");
        assertThat(rendered)
                .as("setting BROWSER can change the adapter's chosen sign-in flow")
                .doesNotContain("BROWSER");
    }

    private static AgentDefinition definition(Path directory, String flow) {
        return AgentDefinitionReader.read(
                new java.io.StringReader(
                        """
                        name: example
                        binary: example-cli
                        git_identity:
                          name: Example
                          email: noreply@example.com
                        headless:
                          prompt_flag: "-p"
                        config_dir: %s
                        login:
                          arguments: [auth, login]
                        %s
                        """
                                .formatted(directory, flow.isEmpty() ? "" : "  flow: " + flow)),
                "test.yaml");
    }

    @ParameterizedTest
    @ValueSource(strings = {"192.0.2.1 50000 192.0.2.2 22", ""})
    void refusesALoopbackSignInOverSshBeforeAskingTheVaultOrLaunching(
            String ssh, @TempDir Path dir) {
        final PoreloContext base = context(dir);
        final PoreloContext remote =
                new PoreloContext(
                                runner,
                                base.paths(),
                                command -> {
                                    throw new AssertionError("no build or container may start");
                                },
                                name -> "SSH_CONNECTION".equals(name) ? ssh : null)
                        .asking(
                                prompt -> {
                                    throw new AssertionError("the vault must not be asked");
                                });
        remote.vault().write(Map.of(), "test-passphrase".toCharArray());

        final AgentLogin.Result result =
                AgentLogin.login(
                        remote,
                        definition(dir, "loopback"),
                        false,
                        false,
                        new PrintWriter(out, true),
                        directory -> {
                            throw new AssertionError("no existing credential may be read");
                        },
                        (command, directory) -> {
                            throw new AssertionError("no login may launch");
                        });

        assertThat(result.outcome())
                .as("a loopback redirect cannot reach the SSH account")
                .isEqualTo(AgentLogin.Outcome.LOOPBACK_OVER_SSH);
        assertThat(result.detail()).as("the refusal identifies the agent").contains("example");
        assertThat(runner.invocations()).as("no container operation on refusal").isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"true, false", "false, true", "true, true"})
    void neitherPreviewNorForceBypassesTheSshRefusal(
            boolean dryRun, boolean force, @TempDir Path dir) {
        final PoreloContext remote =
                new PoreloContext(
                        runner,
                        context(dir).paths(),
                        command -> 0,
                        name -> "SSH_CONNECTION".equals(name) ? "connected" : null);

        final AgentLogin.Result result =
                AgentLogin.login(
                        remote,
                        definition(dir, "loopback"),
                        dryRun,
                        force,
                        new PrintWriter(out, true),
                        directory -> false,
                        (command, directory) -> {
                            throw new AssertionError("a refused login must not launch");
                        });

        assertThat(result.outcome())
                .as("flags do not make the remote redirect reachable")
                .isEqualTo(AgentLogin.Outcome.LOOPBACK_OVER_SSH);
    }

    @ParameterizedTest
    @CsvSource({
        "loopback, false",
        "paste-code, true",
        "device-code, true",
        "'', true",
        "'', false"
    })
    void launchesLocalLoopbackAndRemoteCodeOrUndeclaredSignIns(
            String flow, boolean ssh, @TempDir Path dir) {
        final PoreloContext allowed =
                new PoreloContext(
                        runner,
                        context(dir).paths(),
                        command -> 0,
                        name -> ssh && "SSH_CONNECTION".equals(name) ? "connected" : null);
        final List<List<String>> launched = new ArrayList<>();
        final AgentLogin.Result ran =
                new AgentLogin.Result(AgentLogin.Outcome.NOTHING_TO_COLLECT, "", "", 0, "");

        final AgentLogin.Result result =
                AgentLogin.login(
                        allowed,
                        definition(dir, flow),
                        false,
                        false,
                        new PrintWriter(out, true),
                        directory -> false,
                        (command, directory) -> {
                            launched.add(List.copyOf(command));
                            assertThat(directory)
                                    .as("the declared directory is collected")
                                    .isEqualTo(dir.toString());
                            return ran;
                        });

        assertThat(launched)
                .as("the permitted flow reaches the launch with the declared command")
                .containsExactly(List.of("example-cli", "auth", "login"));
        assertThat(result).as("the launch's result reaches the caller").isSameAs(ran);
    }

    @ParameterizedTest
    @CsvSource({
        "loopback, true, false",
        "loopback, false, true",
        "paste-code, true, true",
        "device-code, true, true",
        "'', true, true"
    })
    void namesTheLoginAsAWayOutOnlyWhereItCanRun(
            String flow, boolean ssh, boolean offered, @TempDir Path dir) {
        // Every place that tells a person to sign in asks this; a login it would then refuse is
        // no way out.
        final PoreloContext here =
                new PoreloContext(
                        runner,
                        context(dir).paths(),
                        command -> 0,
                        name -> ssh && "SSH_CONNECTION".equals(name) ? "connected" : null);

        assertThat(AgentLogin.signsInHere(here, definition(dir, flow))).isEqualTo(offered);
    }

    @Test
    void theLoginImageIsOfflineAndNamedForWhatItIs() {

        // It is never registered as a project and never runs a task. Offline because none of
        // Porelo's networking applies to it: the container is run without the annotation that makes
        // the hooks fire, so an egress class would describe something that is not there.
        assertThat(AgentLogin.loginProject().securityClass()).isEqualTo(SecurityClass.OFFLINE);
        assertThat(AgentLogin.loginProject().name()).isEqualTo("porelo-login");
    }

    @Test
    void theLoginImageDoesNotBorrowATasksName() {

        // Sharing 'porelo/<project>' would have a login rebuild the image a task uses, and a task
        // rebuild the one a login uses - each quietly undoing the other's layers.
        assertThat(AgentLogin.loginProject().imageName()).doesNotContain("porelo/demo");
        assertThat(AgentLogin.loginProject().imageName()).contains("porelo-login");
    }

    @Test
    void aConfigDirectoryIsResolvedInsideTheContainerAndNotOnTheNode() {

        // Off by one user, and it would have looked like a login that produced nothing: the agent
        // runs as 'agent' in the image, so '~/.claude' is /home/agent/.claude there while the
        // node's expansion gives whoever is running Porelo. Pinned to the container's path, never
        // compared with this machine's: run as 'agent' in a task, the two are the same.
        assertThat(AgentLogin.inContainer("~/.claude"))
                .as("the container's home, whoever runs Porelo")
                .isEqualTo("/home/agent/.claude");
        assertThat(AgentLogin.inContainer("/etc/somewhere"))
                .as("an absolute path as it is")
                .isEqualTo("/etc/somewhere");
    }

    @Test
    void nothingIsBuiltOrRunForAPreviewOnAMachineWithNoAgent(@TempDir Path dir) {

        AgentLogin.login(context(dir), null, true, false, new PrintWriter(out, true));

        assertThat(runner.invocations())
                .noneSatisfy(command -> assertThat(command.arguments()).contains("build"));
    }

    @Test
    void copiesTheConfigDirectorysContentsRatherThanTheDirectory() {

        // Why a login had never stored anything, for any agent whose config directory holds the
        // credential as a file inside it. 'podman cp container:/home/agent/.claude target' copies
        // the DIRECTORY, leaving target/.claude/.credentials.json - and the extractor is handed
        // target and looks for target/.credentials.json, one level above the file. So every login
        // found nothing, said "it may have been cancelled", and was believed, because nobody had
        // run one end to end.
        //
        // Measured against podman rather than read: with the trailing "/." the contents land in the
        // target, without it the directory does.
        assertThat(AgentLogin.contentsOf("~/.claude")).isEqualTo("/home/agent/.claude/.");
        assertThat(AgentLogin.contentsOf("/etc/agent")).isEqualTo("/etc/agent/.");
        // And the plain form is still what names the directory itself, for anything that wants it.
        assertThat(AgentLogin.inContainer("~/.claude")).isEqualTo("/home/agent/.claude");
    }
}
