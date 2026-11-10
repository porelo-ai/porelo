package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.gate.GateMode;
import org.fuin.porelo.gate.GitGate;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Approval must neither bypass the host's review nor overwrite somebody else's work. */
class GateHostApprovalTest {

    private static final String TASK_ID = "f1".repeat(32);
    private static final String TOKEN = "test-task-forge-secret";

    private static final String LENT_TOKEN = "test-person-lent-forge-secret";

    @TempDir Path dir;

    private final ProcessCommandRunner runner = new ProcessCommandRunner();
    private final HostVaultOwner owner = new HostVaultOwner();
    private final List<Map<?, ?>> created = new ArrayList<>();
    private final List<Boolean> authenticated = new ArrayList<>();
    private final List<Boolean> gitAuthenticated = new ArrayList<>();
    private HttpServer forge;
    private Path conf;
    private Path upstream;
    private Path work;
    private Path mirror;
    private PoreloContext context;
    private PoreloContext lendingContext;
    private String first;
    private String base;
    private String url;
    private String head = "porelo/fix";
    private int status = 201;

    @BeforeEach
    void aHostWithAForgeAndAWaitingPush() throws Exception {
        upstream = dir.resolve("acme/repo.git");
        Files.createDirectories(upstream.getParent());
        git(dir, "init", "--bare", "-q", "-b", "main", upstream.toString());
        git(upstream, "config", "http.receivepack", "true");
        work = dir.resolve("work");
        git(dir, "clone", "-q", upstream.toString(), work.toString());
        git(work, "config", "user.name", "Developer");
        git(work, "config", "user.email", "developer@example.org");
        base = commit("base");
        git(work, "push", "-q", "origin", "main");
        final XdgPaths xdg = XdgPaths.of(name -> null, dir.resolve("home"));
        // A unit test must never ask the real host's helper for a person's forge token.
        lendingContext =
                new PoreloContext(
                        command -> {
                            assertThat(command.arguments())
                                    .containsExactly(
                                            "sudo",
                                            "-n",
                                            "/usr/sbin/porelo-host",
                                            "forge",
                                            "lend-token");
                            assertThat(command.input()).isEmpty();
                            assertThat(command.binaryInput()).isNull();
                            assertThat(command.environment()).isEmpty();
                            return new CommandResult(command, 0, LENT_TOKEN + "\n", "");
                        },
                        new PoreloPaths(xdg, dir.resolve("bin")),
                        args -> 0);
        // Approval also anchors an event; the helper's strict runner must never receive it.
        context =
                new PoreloContext(
                                command -> {
                                    // The approval runs git for real, without a terminal.
                                    if (command.program().equals("git")) {
                                        return runner.run(command);
                                    }
                                    // The task is up, under the id its start recorded.
                                    if (command.program().equals("podman")) {
                                        return new CommandResult(
                                                command,
                                                0,
                                                "porelo-demo-fix\tUp 4 minutes\t1700000000\t0"
                                                        + "\tdemo\tguarded\t\t\t"
                                                        + TASK_ID
                                                        + "\tfix\n",
                                                "");
                                    }
                                    assertThat(command.arguments())
                                            .as("approval anchors its event in the journal")
                                            .containsExactly("logger", "--journald");
                                    assertThat(command.input())
                                            .as("the approval event withholds both credentials")
                                            .startsWith("MESSAGE=porelo event approved ")
                                            .contains("\nSYSLOG_IDENTIFIER=porelo-events\n")
                                            .doesNotContain(TOKEN, LENT_TOKEN);
                                    assertThat(command.binaryInput()).isNull();
                                    assertThat(command.environment()).isEmpty();
                                    return new CommandResult(command, 0, "", "");
                                },
                                lendingContext.paths(),
                                args -> 0)
                        .withHostVault(owner);
        final byte[] share = new byte[32];
        context.vault()
                .write(
                        Map.of(
                                TaskSecrets.PREFIX + "porelo-demo-fix/forge-token",
                                VaultEntry.of(TOKEN)),
                        share);
        owner.keep(share);
        TaskIdentityFixture.record(context.paths(), "porelo-demo-fix", "demo", "fix", TASK_ID);
        forge = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        forge.createContext("/api/v1/repos/acme/repo", this::api);
        forge.createContext("/acme/repo.git", this::gitHttp);
        forge.start();
        url = "http://127.0.0.1:" + forge.getAddress().getPort();
        conf = dir.resolve("forge.conf");
        Files.writeString(conf, "git_port=" + forge.getAddress().getPort() + "\n");
        final Path project = dir.resolve("project.yml");
        Files.writeString(
                project,
                """
                project:
                  name: demo
                  security_class: guarded
                image:
                  from: ubuntu:24.04
                repositories:
                  repo:
                    upstream: "%s/acme/repo.git"
                    central: "git@central.example:acme/repo.git"
                """
                        .formatted(url));
        carried("\"repo\"");
        Files.createDirectories(context.paths().xdg().data().resolve("projects"));
        Files.writeString(context.paths().xdg().data().resolve("projects/demo"), project + "\n");
        mirror = context.paths().xdg().data().resolve("mirrors/demo/repo.git");
        new GitGate(runner, mirror, GateMode.GATEKEEPING, upstream.toString()).initialize();
        git(mirror, "config", "http.proxy", "");
        first = commit("Reviewed change");
        waiting(first);
    }

    @AfterEach
    void closeTheForgeAndVault() {
        if (forge != null) forge.stop(0);
        owner.close();
    }

    /** Writes this host's file of the project, beside its forge's, as root does. */
    private void carried(final String repositories) throws Exception {
        final Path files = Files.createDirectories(dir.resolve("organization/projects"));
        Files.writeString(
                files.resolve("demo.json"),
                """
                {"project": "demo", "revision": "56420b3", "address": "git@central.example:acme/demo.git",
                 "fetch_from": null, "signing_keys": [], "homeserver": null, "room": null,
                 "repositories": [%s]}
                """
                        .formatted(repositories));
    }

    // Work on a repository the host does not carry has no copy on its forge and no push judge.
    @Test
    void approvalOfARepositoryThisHostNoLongerCarriesForwardsNothing() throws Exception {
        carried("\"other\"");

        final Run result = approve();

        assertThat(result.code()).as("the approval is refused").isNotZero();
        assertThat(remote("porelo/fix")).as("nothing reached the forge").isEmpty();
        assertThat(created).as("no review was opened").isEmpty();
        assertThat(pending()).as("the push still waits").contains("fix");
    }

    // A direct push to main bypasses the host's required pull-request review.
    @Test
    void approvalOnAHostPushesTheTaskBranchAndOpensAPullRequestWithItsToken() {
        final Run result = approve();

        assertThat(result.code()).as(result.err()).isZero();
        assertThat(remote("porelo/fix"))
                .as("the host takes reviewed work on the task branch")
                .isEqualTo(first);
        assertThat(remote("main")).as("main still needs the forge review").isEqualTo(base);
        assertThat(created).as("one pull request is opened").hasSize(1);
        assertThat(created.getFirst().get("head"))
                .as("the request reviews the task branch")
                .isEqualTo("porelo/fix");
        assertThat(created.getFirst().get("base")).as("the review targets main").isEqualTo("main");
        assertThat(created.getFirst().get("title"))
                .as("the subject titles the review")
                .isEqualTo("Reviewed change");
        assertThat(created.getFirst().get("body").toString())
                .as("the authoring task is named")
                .contains("fix");
        assertThat(authenticated)
                .as("all API calls carry the task's token")
                .isNotEmpty()
                .containsOnly(true);
        assertThat(gitAuthenticated)
                .as("git carries the person's independently lent token")
                .isNotEmpty()
                .containsOnly(true);
        assertThat(result.out())
                .as("the person can open the review")
                .contains("pull request " + url + "/acme/repo/pulls/1");
        assertThat(result.out() + result.err())
                .as("the token stays out of answers")
                .doesNotContain(TOKEN, LENT_TOKEN);
    }

    // A repeated approval must not create a second review for the same branch.
    @Test
    void aSecondApprovalReusesTheOpenPullRequest() {
        assertThat(approve().code()).as("first approval succeeds").isZero();
        final String second = commit("Next reviewed change");
        waiting(second);

        final Run result = approve();

        assertThat(result.code()).as(result.err()).isZero();
        assertThat(remote("porelo/fix")).as("the review follows the new push").isEqualTo(second);
        assertThat(created).as("the existing review is reused").hasSize(1);
        assertThat(result.out())
                .as("the same review is returned")
                .contains("pull request " + url + "/acme/repo/pulls/1");
    }

    // An amended commit belongs to the same review and may replace only this gate's last push.
    @Test
    void anAmendedApprovalReplacesTheTasksBranchAndKeepsOnePullRequest() {
        assertThat(approve("--branch", "porelo/fix").code()).as("first approval succeeds").isZero();
        git(work, "commit", "--amend", "--allow-empty", "-q", "-m", "Amended reviewed change");
        final String amended = git(work, "rev-parse", "HEAD");
        waiting(amended);

        final Run result = approve("--branch", "porelo/fix");

        assertThat(result.code())
                .as("an amendment of the gate's own push: " + result.err())
                .isZero();
        assertThat(remote("porelo/fix"))
                .as("the amendment replaces the prior approval")
                .isEqualTo(amended);
        assertThat(created).as("the review survives the amendment").hasSize(1);
    }

    // Somebody else's head must not be overwritten even after this task had an approval.
    @Test
    void aHeadTheGateDidNotForwardStillRefusesAndOffersTheNextBranch() {
        assertThat(approve("--branch", "porelo/fix").code()).as("first approval succeeds").isZero();
        git(work, "checkout", "-q", "--detach", base);
        final String other = commit("Somebody else's work");
        git(work, "push", "-q", "--force", "origin", "HEAD:refs/heads/porelo/fix");
        git(work, "checkout", "-q", "--detach", first);
        git(work, "commit", "--amend", "--allow-empty", "-q", "-m", "Amended reviewed change");
        final String amended = git(work, "rev-parse", "HEAD");
        waiting(amended);

        final Run result = approve("--branch", "porelo/fix");

        assertThat(result.code()).as("unrecognized work is refused").isNotZero();
        assertThat(remote("porelo/fix")).as("the other person's work stays").isEqualTo(other);
        assertThat(result.err())
                .as("a safe way forward is offered")
                .contains("--branch porelo/fix-2");
        assertThat(pending()).as("the refused push still waits").contains("fix");
        assertThat(created).as("no second review was made").hasSize(1);
    }

    // Losing the pending ref on an API refusal would make the already-pushed work hard to retry.
    @Test
    void aForgeRefusalSaysThePushHappenedAndLeavesItWaitingWithoutLeakingTheToken() {
        status = 403;

        final Run result = approve("--branch", "porelo/fix");

        assertThat(remote("porelo/fix"))
                .as("the push happened before the API refusal")
                .isEqualTo(first);
        assertThat(result.code()).as("the failed review creation is not success").isNotZero();
        assertThat(result.err())
                .as("the refusal explains both acts")
                .contains("pushed", "403", "scope denied")
                .doesNotContain(TOKEN);
        assertThat(pending()).as("the person can retry approval").contains("fix");
        status = 201;
        assertThat(approve("--branch", "porelo/fix").code()).as("retry opens the review").isZero();
        assertThat(pending()).as("only complete approval removes the pending ref").isEmpty();
    }

    // An explicit destination must keep precedence over the host default.
    @Test
    void anExplicitBranchWinsOnAHost() {
        head = "review/selected";
        final Run result = approve("--branch", head);
        assertThat(result.code()).as(result.err()).isZero();
        assertThat(remote(head)).as("the named branch receives the push").isEqualTo(first);
        assertThat(created).as("the named branch gets a review").hasSize(1);
        assertThat(created.getFirst().get("head"))
                .as("the API uses the chosen branch")
                .isEqualTo(head);
    }

    // Reusing a review aimed elsewhere would leave the promised main-branch review unmade.
    @Test
    void aPullRequestToAnotherBaseDoesNotSatisfyTheApproval() {
        created.add(Map.of("head", "porelo/fix", "base", "release"));

        final Run result = approve("--branch", "porelo/fix");

        assertThat(result.code()).as(result.err()).isZero();
        assertThat(created).as("a review targeting the default branch is still needed").hasSize(2);
        assertThat(created.getLast().get("base"))
                .as("the new review targets main")
                .isEqualTo("main");
        assertThat(result.out())
                .as("the new review is returned")
                .contains("pull request " + url + "/acme/repo/pulls/2");
    }

    private Run approve(String... extra) {
        final GateApproveCommand command =
                new GateApproveCommand() {
                    @Override
                    Path forgeConf() {
                        return conf;
                    }

                    @Override
                    GitGate gate(final Project project) {
                        return super.gate(project)
                                .using(
                                        new HostForgeLending(
                                                lendingContext,
                                                url -> {
                                                    throw new AssertionError(
                                                            "the host must lend its forge token");
                                                },
                                                conf,
                                                () -> true));
                    }
                };
        command.setContext(context);
        final CommandLine cli = new CommandLine(command);
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        final List<String> args =
                new ArrayList<>(List.of("-p", "demo", "-r", "repo", "fix", "--no-input"));
        args.addAll(List.of(extra));
        return new Run(cli.execute(args.toArray(String[]::new)), out.toString(), err.toString());
    }

    private record Run(int code, String out, String err) {}

    private String commit(String subject) {
        git(work, "commit", "-q", "--allow-empty", "-m", subject);
        return git(work, "rev-parse", "HEAD");
    }

    private void waiting(String commit) {
        git(work, "push", "-q", "--force", mirror.toString(), commit + ":refs/porelo/incoming/fix");
    }

    private List<String> pending() {
        return new GitGate(runner, mirror, GateMode.GATEKEEPING, upstream.toString()).pending();
    }

    private String remote(String branch) {
        return git(upstream, "for-each-ref", "--format=%(objectname)", "refs/heads/" + branch);
    }

    private String git(Path in, String... args) {
        final List<String> all = new ArrayList<>(List.of("git", "-C", in.toString()));
        all.addAll(List.of(args));
        return runner.runOrFail(Command.of(all)).standardOutput().strip();
    }

    private void api(HttpExchange exchange) throws IOException {
        authenticated.add(
                ("token " + TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization")));
        if (exchange.getRequestURI().getPath().endsWith("/pulls")) {
            if (exchange.getRequestMethod().equals("GET")) {
                answer(exchange, 200, created.isEmpty() ? List.of() : List.of(pull()));
            } else {
                if (status != 201) {
                    answer(exchange, status, Map.of("message", "scope denied " + TOKEN));
                } else {
                    created.add(
                            (Map<?, ?>)
                                    Json.parse(
                                            new String(
                                                    exchange.getRequestBody().readAllBytes(),
                                                    StandardCharsets.UTF_8)));
                    answer(exchange, 201, pull());
                }
            }
        } else {
            answer(exchange, 200, Map.of("default_branch", "main"));
        }
    }

    private Map<String, Object> pull() {
        return Map.of(
                "head",
                Map.of("ref", head),
                "base",
                Map.of("ref", created.getLast().get("base")),
                "html_url",
                url + "/acme/repo/pulls/" + created.size());
    }

    private void answer(HttpExchange exchange, int code, Object body) throws IOException {
        final byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (exchange) {
            exchange.getResponseBody().write(bytes);
        }
    }

    private void gitHttp(HttpExchange exchange) throws IOException {
        gitAuthenticated.add(
                ("Basic "
                                + java.util.Base64.getEncoder()
                                        .encodeToString(
                                                ("x-access-token:" + LENT_TOKEN)
                                                        .getBytes(StandardCharsets.UTF_8)))
                        .equals(exchange.getRequestHeaders().getFirst("Authorization")));
        final ProcessBuilder builder = new ProcessBuilder("git", "http-backend");
        final Map<String, String> env = builder.environment();
        env.put("GIT_CONFIG_GLOBAL", "/dev/null");
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_PROJECT_ROOT", dir.toString());
        env.put("GIT_HTTP_EXPORT_ALL", "1");
        env.put("PATH_INFO", exchange.getRequestURI().getPath());
        env.put(
                "QUERY_STRING",
                java.util.Objects.requireNonNullElse(exchange.getRequestURI().getRawQuery(), ""));
        env.put("REQUEST_METHOD", exchange.getRequestMethod());
        env.put(
                "CONTENT_TYPE",
                java.util.Objects.requireNonNullElse(
                        exchange.getRequestHeaders().getFirst("Content-Type"), ""));
        final Process process = builder.start();
        try (var input = process.getOutputStream()) {
            exchange.getRequestBody().transferTo(input);
        }
        final byte[] response = process.getInputStream().readAllBytes();
        final String text = new String(response, StandardCharsets.ISO_8859_1);
        final int seam = text.indexOf("\r\n\r\n");
        for (String header : text.substring(0, seam).split("\r\n")) {
            final int colon = header.indexOf(':');
            exchange.getResponseHeaders()
                    .add(header.substring(0, colon), header.substring(colon + 1).strip());
        }
        exchange.sendResponseHeaders(200, response.length - seam - 4);
        try (exchange) {
            exchange.getResponseBody().write(response, seam + 4, response.length - seam - 4);
        }
        try {
            process.waitFor();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException(ex);
        }
    }
}
