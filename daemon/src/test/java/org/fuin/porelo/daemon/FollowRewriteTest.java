package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.app.FollowSignedBy;
import org.fuin.porelo.app.FollowedProjects;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.app.Reconcile;
import org.fuin.porelo.app.SignedBy;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Guards the daemon's recovery preview against refusing a rewrite the real follow accepts. */
class FollowRewriteTest {
    private final CommandRunner runner =
            command ->
                    new ProcessCommandRunner()
                            .run(
                                    command.withEnvironment(
                                            Map.of(
                                                    "GIT_CONFIG_GLOBAL",
                                                    "/dev/null",
                                                    "GIT_CONFIG_NOSYSTEM",
                                                    "1",
                                                    "TZ",
                                                    "UTC")));

    @ParameterizedTest
    @ValueSource(strings = {"none", "key", "fingerprint", "several"})
    void a_follow_preview_honors_the_rewrite_decision(final String anchor, @TempDir final Path dir)
            throws Exception {
        // A client must be able to preview recovery without applying it or changing its trust.
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        final PoreloContext context =
                new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), args -> 0);
        final Path key = key(dir, "operator");
        final String publicKey = publicKey(key);
        final String extra = publicKey(key(dir, "extra"));
        final Path repo = Files.createDirectory(dir.resolve("published"));
        git(repo, "init", "-q", "-b", "main");
        git(repo, "config", "user.name", "Operator");
        git(repo, "config", "user.email", "operator@example.org");
        git(repo, "config", "gpg.format", "ssh");
        git(repo, "config", "user.signingkey", key.toString());
        Files.writeString(
                repo.resolve("project.yml"),
                "project:\n  name: demo\n  security_class: guarded\nimage:\n  base_image: ubuntu:24.04\n");
        git(repo, "add", "project.yml");
        git(repo, "commit", "-q", "-S", "-m", "baseline");
        final Reconcile.Result first =
                new FollowSignedBy(context)
                        .follow("demo", repo.toString(), publicKey, false, false)
                        .result();
        assertThat(first.outcome()).as(first.detail()).isEqualTo(Reconcile.Outcome.APPLIED);
        final FollowedProjects projects =
                new FollowedProjects(context.paths().projects().followed());
        final FollowedProjects.Followed before = projects.require("demo");
        final Path signers = context.paths().projects().configurationSigners();
        final String pins = Files.readString(signers);
        final String events = Files.readString(context.paths().eventLog());
        git(repo, "checkout", "--orphan", "replacement");
        git(repo, "commit", "-q", "-S", "-m", "replacement history");
        git(repo, "branch", "-M", "main");
        final String tip = git(repo, "rev-parse", "HEAD");
        final Map<String, Object> parameters =
                new LinkedHashMap<>(Map.of("name", "demo", "url", repo.toString(), "dryRun", true));
        switch (anchor) {
            case "key" -> parameters.put("signedBy", publicKey);
            case "fingerprint" -> parameters.put("signedBy", SignedBy.fingerprintOf(publicKey));
            case "several" -> parameters.put("signers", List.of(publicKey, extra));
            default -> {}
        }
        assertThat(call(context, parameters))
                .as("a rewrite still requires an explicit decision")
                .containsEntry("outcome", "REWRITTEN");
        parameters.put("acceptRewrite", true);

        final Map<String, Object> preview = call(context, parameters);

        assertThat(preview)
                .as("the daemon forwards the explicit rewrite decision")
                .containsEntry("outcome", "READY")
                .containsEntry("commit", tip)
                .containsEntry("recorded", false);
        assertThat(projects.require("demo"))
                .as("a preview preserves the followed record")
                .isEqualTo(before);
        assertThat(Files.readString(signers))
                .as("a preview preserves the account's trust")
                .isEqualTo(pins);
        assertThat(Files.readString(context.paths().eventLog()))
                .as("a preview records no application")
                .isEqualTo(events);
        assertThat(git(context.paths().projects().followedClone("demo"), "rev-parse", "HEAD"))
                .as("a preview preserves the applied clone")
                .isEqualTo(first.commit());
        parameters.put("dryRun", false);
        assertThat(call(context, parameters))
                .as("the real follow applies the same replacement tip")
                .containsEntry("outcome", "APPLIED")
                .containsEntry("commit", tip)
                .containsEntry("recorded", true);
        assertThat(projects.require("demo").commit())
                .as("the real follow records the replacement")
                .isEqualTo(tip);
    }

    private Map<String, Object> call(
            final PoreloContext context, final Map<String, Object> parameters) throws Exception {
        final Map<String, Object> result = new LinkedHashMap<>();
        ProjectMethods.follow(context)
                .call(
                        parameters,
                        new VarlinkServer.Replies() {
                            @Override
                            public void more(final Map<String, Object> reply) {
                                throw new AssertionError("Follow must send one final reply");
                            }

                            @Override
                            public void last(final Map<String, Object> reply) {
                                result.putAll(reply);
                            }

                            @Override
                            public boolean streaming() {
                                return false;
                            }

                            @Override
                            public boolean open() {
                                return true;
                            }
                        });
        return result;
    }

    private String git(final Path repo, final String... arguments) {
        final List<String> args = new ArrayList<>(List.of("git", "-C", repo.toString()));
        args.addAll(List.of(arguments));
        final var result = runner.run(Command.of(args));
        assertThat(result.successful()).as(args + ": " + result.standardError()).isTrue();
        return result.standardOutput().strip();
    }

    private Path key(final Path dir, final String name) {
        final Path key = dir.resolve(name);
        final var result =
                runner.run(
                        Command.of(
                                "ssh-keygen",
                                "-q",
                                "-t",
                                "ed25519",
                                "-N",
                                "",
                                "-f",
                                key.toString()));
        assertThat(result.successful()).as(result.standardError()).isTrue();
        return key;
    }

    private String publicKey(final Path key) throws Exception {
        final String[] fields = Files.readString(Path.of(key + ".pub")).strip().split("\\s+");
        return fields[0] + " " + fields[1];
    }
}
