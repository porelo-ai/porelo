package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.credential.Credential;
import org.fuin.porelo.core.credential.CredentialRegistry;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.SigningKey;
import org.fuin.porelo.vault.VaultEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Protects access cleanup for keys made by earlier versions. */
class LeftDeployKeysTest {
    private static final char[] PASSPHRASE = "test passphrase".toCharArray();
    @TempDir Path dir;

    @AfterEach
    void forgetThePassphrase() {
        PoreloContext.forgetAsked();
    }

    private PoreloContext context() {
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
                new PoreloContext(
                                new FakeCommandRunner(),
                                new PoreloPaths(xdg, dir.resolve("bin")),
                                args -> 0)
                        .asking(prompt -> PASSPHRASE.clone());
        context.vault().write(Map.of(), PASSPHRASE);
        context.openerAsking(false, new PrintWriter(new StringWriter()));
        return context;
    }

    private static Project project(String name, String repository, String upstream) {
        return ProjectReader.read(
                new StringReader(
                        """
            project: { name: "%s", security_class: "guarded" }
            image: { base_image: "ubuntu:24.04" }
            repositories:
              %s:
                upstream: "%s"
            """
                                .formatted(name, repository, upstream)),
                "test");
    }

    static LeftDeployKeys.Key earlierKey(
            PoreloContext context,
            String project,
            String repository,
            String upstream,
            boolean legacy)
            throws Exception {
        final String entry =
                legacy
                        ? "deploy-" + project + "-" + repository
                        : "deploy:" + project + ":" + repository;
        final String machine = "porelo@" + MachineName.hostName();
        final SigningKey key = SigningKey.generate(machine);
        context.vault()
                .update(
                        context.opener().orElseThrow(),
                        all -> {
                            all.put(
                                    entry,
                                    new VaultEntry(
                                            key.seedBase64(),
                                            "ssh-key",
                                            Map.of("upstream", upstream, "made_on", machine)));
                            return all;
                        });
        new CredentialDeclarations(context)
                .declare(
                        new Credential(
                                entry,
                                Credential.Kind.SSH_KEY,
                                CredentialRegistry.normalise(upstream),
                                null,
                                Credential.ANY,
                                Credential.Source.VAULT));
        return new LeftDeployKeys.Key(
                entry,
                repository,
                upstream,
                key.authorizedKeysLine(),
                SignedBy.fingerprintOf(key.authorizedKeysLine()),
                "porelo " + MachineName.hostName() + " " + project + "/" + repository,
                !project.equals(repository),
                false);
    }

    @Test
    void projectsWhoseNamesMeetKeepKeysOfTheirOwnAndClearingOneLeavesTheOther() throws Exception {
        // A colliding name must not delete another project's access.
        final PoreloContext context = context();
        final Project web = project("web", "app-api", "git@forge.example.org:o/one.git");
        final LeftDeployKeys.Key first =
                earlierKey(context, "web", "app-api", "git@forge.example.org:o/one.git", false);
        final LeftDeployKeys.Key second =
                earlierKey(context, "web-app", "api", "git@forge.example.org:o/two.git", false);

        assertThat(LeftDeployKeys.forget(context, web))
                .as("only the chosen project's key is named")
                .containsExactly(first);
        assertThat(context.vault().read(PASSPHRASE))
                .as("the other project's credential survives")
                .containsKey(second.entry())
                .doesNotContainKey(first.entry());
        assertThat(context.credentialRegistry().forUrl("git", first.upstream()))
                .as("the forgotten key's declaration is removed")
                .isNull();
        assertThat(LeftDeployKeys.forget(context, web))
                .as("a forgotten key is never named twice")
                .isEmpty();
    }

    @Test
    void aKeyKeptUnderTheOldNameIsTakenOnlyByTheRepositoryItWasMadeFor() throws Exception {
        // A shared old name must not let one project forget another project's key.
        final PoreloContext context = context();
        final LeftDeployKeys.Key key =
                earlierKey(context, "web-app", "api", "git@forge.example.org:o/two.git", true);
        final Project other = project("web", "app-api", "git@forge.example.org:o/one.git");
        final Project owner = project("web-app", "api", key.upstream());

        assertThat(LeftDeployKeys.held(context, other))
                .as("an old name alone does not prove ownership")
                .isEmpty();
        assertThat(LeftDeployKeys.forget(context, other))
                .as("another project's key cannot be forgotten")
                .isEmpty();
        assertThat(LeftDeployKeys.held(context, owner))
                .as("a preview names the old key without forgetting it")
                .containsExactly(key);
        assertThat(context.vault().read(PASSPHRASE))
                .as("previewing preserves the secret half")
                .containsKey(key.entry());
        assertThat(LeftDeployKeys.forget(context, owner))
                .as("the old key is still forgotten and named")
                .containsExactly(key);
        assertThat(context.vault().read(PASSPHRASE))
                .as("the old secret half is gone")
                .doesNotContainKey(key.entry());
    }

    @Test
    void unfollowForgetsAnEarlierKeyAndNamesItOnce() throws Exception {
        // Unfollowing must not leave a hidden credential that restores access later.
        final PoreloContext context = context();
        final String upstream = "git@forge.example.org:o/one.git";
        final LeftDeployKeys.Key key = earlierKey(context, "web", "api", upstream, false);
        final Path file = context.paths().projects().followedClone("web").resolve("project.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(
                file,
                "project: { name: web, security_class: guarded }\nimage: { base_image: \"ubuntu:24.04\" }\nrepositories:\n  api:\n    upstream: "
                        + upstream
                        + "\n");
        Files.createDirectories(context.paths().projects().projectRegistry());
        Files.writeString(context.paths().projects().projectRegistry().resolve("web"), file + "\n");
        new FollowedProjects(context.paths().projects().followed())
                .follow("web", "fixture.git", true);
        final StringWriter out = new StringWriter();
        final CommandLine command = PoreloCli.commandLine(context);
        command.setOut(new PrintWriter(out, true));
        command.setErr(new PrintWriter(out, true));

        assertThat(command.execute("project", "unfollow", "web", "--force"))
                .as("unfollow completes")
                .isZero();
        assertThat(out.toString())
                .as("the person is told exactly once which forge key remains")
                .containsOnlyOnce(key.fingerprint());
        assertThat(context.vault().read(PASSPHRASE))
                .as("unfollow removes the private half")
                .doesNotContainKey(key.entry());
        assertThat(context.credentialRegistry().forUrl("git", upstream))
                .as("unfollow removes its declaration")
                .isNull();
    }

    @Test
    void clearingForgetsAnEarlierKeyAndNamesItOnce() throws Exception {
        // Clearing must not report success while a reusable private key remains in the vault.
        final PoreloContext context = context();
        final String upstream = "git@forge.example.org:o/one.git";
        final LeftDeployKeys.Key key = earlierKey(context, "web", "api", upstream, false);
        final Path file = context.paths().projects().followedClone("web").resolve("project.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(
                file,
                "project: { name: web, security_class: guarded }\nimage: { base_image: \"ubuntu:24.04\" }\nrepositories:\n  api:\n    upstream: "
                        + upstream
                        + "\n");
        Files.createDirectories(context.paths().projects().projectRegistry());
        Files.writeString(context.paths().projects().projectRegistry().resolve("web"), file + "\n");

        final Clearing.Result cleared = new Clearing(context).project("web", false, true);

        assertThat(cleared.keys())
                .as("clearing names the key exactly once for removal at the forge")
                .containsExactly(key);
        assertThat(context.vault().read(PASSPHRASE))
                .as("clearing forgets the private half")
                .doesNotContainKey(key.entry());
        assertThat(context.credentialRegistry().forUrl("git", upstream))
                .as("clearing removes its declaration")
                .isNull();
    }
}
