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
import org.fuin.porelo.app.SignedBy;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Guards clients against authorizing or claiming pins for a refused access range. */
class FollowAccessTest {
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
    @CsvSource({
        "NOT_SIGNED,key,true",
        "UNKNOWN_KEY,key,true",
        "REWRITTEN,key,true",
        "UNREADABLE,key,true",
        "UNUSABLE,key,true",
        "UNKNOWN_KEY,several,true",
        "UNKNOWN_KEY,pinned,true",
        "NOT_SIGNED,key,false",
        "UNKNOWN_KEY,key,false",
        "REWRITTEN,key,false",
        "UNREADABLE,key,false",
        "UNUSABLE,key,false",
        "UNKNOWN_KEY,several,false",
        "UNKNOWN_KEY,pinned,false"
    })
    void an_access_refusal_is_the_same_in_a_preview_and_a_real_follow(
            final String refusal,
            final String anchor,
            final boolean dryRun,
            @TempDir final Path dir)
            throws Exception {
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
        final Path access = repository(dir, "access");
        Files.writeString(access.resolve("access.yml"), "{}\n");
        commit(access, key);
        final Path project = repository(dir, "project");
        Files.writeString(
                project.resolve("project.yml"),
                "project:\n  name: demo\n  security_class: guarded\nimage:\n  base_image: ubuntu:24.04\n"
                        + "access:\n  repository: '"
                        + access
                        + "'\n");
        commit(project, key);
        final boolean followedBefore = refusal.equals("REWRITTEN") || anchor.equals("pinned");
        final Path record = context.paths().projects().followed().resolve("demo.json");
        if (followedBefore) {
            new FollowSignedBy(context).follow("demo", project.toString(), publicKey, false, false);
        }
        final byte[] before = followedBefore ? Files.readAllBytes(record) : null;
        final String inForce =
                followedBefore
                        ? new FollowedProjects(context.paths().projects().followed())
                                .require("demo")
                                .commit()
                        : "";
        if (refusal.equals("REWRITTEN")) {
            git(access, "checkout", "--orphan", "replacement");
        }
        Files.writeString(
                access.resolve("access.yml"),
                refusal.equals("UNUSABLE") ? "[invalid access]\n" : "# changed\n{}\n");
        commit(
                access,
                refusal.equals("NOT_SIGNED")
                        ? null
                        : refusal.equals("UNKNOWN_KEY") ? key(dir, "stranger") : key);
        final String rejected = git(access, "rev-parse", "HEAD");
        if (refusal.equals("UNREADABLE")) {
            final Path shallow = dir.resolve("shallow");
            git(dir, "clone", "-q", "--depth=1", access.toUri().toString(), shallow.toString());
            Files.writeString(
                    project.resolve("project.yml"),
                    Files.readString(project.resolve("project.yml"))
                            .replace(access.toString(), shallow.toString()));
            commit(project, key);
        }
        final Map<String, Object> parameters =
                new LinkedHashMap<>(
                        Map.of("name", "demo", "url", project.toString(), "dryRun", dryRun));
        if (anchor.equals("key")) {
            parameters.put("signedBy", publicKey);
        } else if (anchor.equals("several")) {
            parameters.put("signers", List.of(publicKey, publicKey(key(dir, "extra"))));
        }

        final Map<String, Object> preview = call(context, parameters);
        assertThat(preview)
                .as("a preview must refuse the same access range")
                .containsEntry("outcome", refusal)
                .containsEntry("recorded", false)
                .containsEntry("commit", inForce);
        assertThat(preview.get("refused"))
                .as("the refused snapshot is named")
                .asString()
                .isNotBlank();
        assertThat(preview.get("pinned"))
                .as("a refused access range claims no pins")
                .isEqualTo(List.of());
        if (refusal.equals("NOT_SIGNED")
                || refusal.equals("UNKNOWN_KEY")
                || refusal.equals("REWRITTEN")
                || refusal.equals("UNUSABLE")) {
            assertThat(preview.get("refused"))
                    .as("the rejected access commit is identified")
                    .isEqualTo(rejected);
        }
        if (followedBefore) {
            assertThat(Files.readAllBytes(record))
                    .as("the existing follow is preserved")
                    .isEqualTo(before);
        } else {
            assertThat(record).as("a first refusal publishes no follow").doesNotExist();
            assertThat(context.paths().projects().configurationSigners())
                    .as("refused keys never become trusted")
                    .doesNotExist();
        }
    }

    @ParameterizedTest
    @CsvSource({"true", "false"})
    void a_refused_whole_key_preview_claims_no_pin(final boolean dryRun, @TempDir final Path dir)
            throws Exception {
        // A UI must not tell a person an unverified authority has been pinned.
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                name.equals("XDG_CONFIG_HOME")
                                        ? dir.resolve("config").toString()
                                        : null,
                        dir);
        final PoreloContext context =
                new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), args -> 0);
        final Path key = key(dir, "operator");
        final Path repo = repository(dir, "project");
        Files.writeString(
                repo.resolve("project.yml"),
                "project:\n  name: demo\n  security_class: guarded\nimage:\n  base_image: ubuntu:24.04\n");
        commit(repo, null);
        final var reply =
                call(
                        context,
                        Map.of(
                                "name",
                                "demo",
                                "url",
                                repo.toString(),
                                "signedBy",
                                publicKey(key),
                                "dryRun",
                                dryRun));
        assertThat(reply)
                .as("unsigned configuration refuses before pinning")
                .containsEntry("outcome", "NOT_SIGNED")
                .containsEntry("recorded", false)
                .containsEntry("pinned", List.of());
    }

    @ParameterizedTest
    @CsvSource({
        "UNKNOWN_HOST_KEY,true,key", "UNKNOWN_HOST_KEY,false,key",
        "HOST_KEY_CHANGED,true,several", "HOST_KEY_CHANGED,false,several",
        "NO_CREDENTIAL,true,fingerprint", "NO_CREDENTIAL,false,fingerprint",
        "UNKNOWN_HOST_KEY,true,pinned", "UNKNOWN_HOST_KEY,false,pinned"
    })
    void access_recovery_names_the_access_repository(
            final String refusal,
            final boolean dryRun,
            final String anchor,
            @TempDir final Path dir)
            throws Exception {
        // Trust and credential actions must target the access host that actually refused the fetch.
        final String accessUrl = "git@access.example.org:demo/access.git";
        final Path key = key(dir, "operator");
        final String publicKey = publicKey(key);
        final List<String> scanned = new ArrayList<>();
        final CommandRunner transport =
                command -> {
                    if (command.arguments().contains("fetch")
                            && command.arguments().contains(accessUrl)) {
                        final String detail =
                                switch (refusal) {
                                    case "UNKNOWN_HOST_KEY" -> "Host key verification failed";
                                    case "HOST_KEY_CHANGED" ->
                                            "REMOTE HOST IDENTIFICATION HAS CHANGED";
                                    default -> "repository unavailable";
                                };
                        return new CommandResult(command, 1, "", detail);
                    }
                    if (command.arguments().getFirst().equals("ssh-keyscan")) {
                        scanned.add(command.arguments().getLast());
                        return new CommandResult(
                                command, 0, "access.example.org " + publicKey + "\n", "");
                    }
                    return runner.run(command);
                };
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
                new PoreloContext(transport, new PoreloPaths(xdg, dir.resolve("bin")), args -> 0);
        final Path project = repository(dir, "project");
        Files.writeString(
                project.resolve("project.yml"),
                "project:\n  name: demo\n  security_class: guarded\nimage:\n  base_image: ubuntu:24.04\n"
                        + "access:\n  repository: '"
                        + accessUrl
                        + "'\n");
        commit(project, key);
        final Map<String, Object> parameters =
                new LinkedHashMap<>(
                        Map.of("name", "demo", "url", project.toString(), "dryRun", dryRun));
        switch (anchor) {
            case "pinned" -> {
                final Path signers = context.paths().projects().configurationSigners();
                Files.createDirectories(signers.getParent());
                Files.writeString(signers, "demo " + publicKey + "\n");
            }
            case "several" ->
                    parameters.put("signers", List.of(publicKey, publicKey(key(dir, "extra"))));
            case "fingerprint" -> parameters.put("signedBy", SignedBy.fingerprintOf(publicKey));
            default -> parameters.put("signedBy", publicKey);
        }

        final var reply = call(context, parameters);

        assertThat(reply)
                .as("the access refusal is returned without recording or pinning")
                .containsEntry("outcome", refusal)
                .containsEntry("recorded", false)
                .containsEntry("pinned", List.of());
        if (refusal.equals("NO_CREDENTIAL")) {
            assertThat(reply.get("storeCommand"))
                    .as("the access host's credential is the missing one")
                    .asString()
                    .contains("git.ssh.access.example.org");
        } else if (!dryRun) {
            assertThat(reply.get("host"))
                    .as("host confirmation belongs to the access repository")
                    .isEqualTo("access.example.org");
            assertThat(scanned)
                    .as("only the refusing access host is scanned")
                    .containsExactly("access.example.org");
            assertThat(reply.get("hostKeys"))
                    .as("the access host's offered key is returned")
                    .asList()
                    .hasSize(1);
        }
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
                                throw new AssertionError("one reply required");
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

    private Path repository(final Path dir, final String name) throws Exception {
        final Path repo = Files.createDirectory(dir.resolve(name));
        git(repo, "init", "-q", "-b", "main");
        git(repo, "config", "user.name", "Operator");
        git(repo, "config", "user.email", "operator@example.org");
        return repo;
    }

    private void commit(final Path repo, final Path key) {
        git(repo, "add", ".");
        if (key == null) {
            git(repo, "commit", "-q", "--no-gpg-sign", "-m", "configuration");
        } else {
            git(
                    repo,
                    "-c",
                    "gpg.format=ssh",
                    "-c",
                    "user.signingkey=" + key,
                    "commit",
                    "-q",
                    "-S",
                    "-m",
                    "configuration");
        }
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
