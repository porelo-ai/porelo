package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Tests for {@link MachineFile}, Porelo's part of a person's machine file. */
class MachineFileTest {

    private static final Path DRIVE = Path.of("/mnt/drive");

    private static MachineFile read(final String text) {
        return MachineFile.read(new StringReader(text), DRIVE, "machine.yml");
    }

    @Test
    void readsTheProjectTheTasksAndTheKeysAndIgnoresTheRest() {

        final MachineFile file =
                read(
                        """
                        provider: hetzner
                        project:
                            name: porelo
                            url: "git@github.com:porelo-ai/porelo-project.git"
                            signed_by: "SHA256:abc"
                        tasks:
                            - { name: core-1, repository: core }
                        keys:
                            - { name: github-deploy, path: keys/github-deploy }
                        """);

        assertThat(file.project()).as("project").isEqualTo("porelo");
        assertThat(file.signedBy()).as("key").isEqualTo("SHA256:abc");
        assertThat(file.tasks())
                .as("tasks")
                .containsExactly(new MachineFile.Task("core-1", "core"));
        assertThat(file.keys())
                .as("a key by its absolute path on the drive")
                .containsExactly(
                        new MachineFile.Key(
                                "github-deploy", Path.of("/mnt/drive/keys/github-deploy")));
    }

    @Test
    void aFileWithoutTasksOrKeysHasNone() {

        final MachineFile file =
                read("project: { name: porelo, url: /srv/porelo.git, signed_by: SHA256:abc }\n");

        assertThat(file.tasks()).as("tasks").isEmpty();
        assertThat(file.keys()).as("keys").isEmpty();
    }

    @Test
    void refusesAProjectWithoutTheKeyItIsCheckedAgainst() {

        // A play must never follow what it did not check, so there is no unverified form here.
        assertThatThrownBy(() -> read("project: { name: porelo, url: /srv/porelo.git }\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signed_by");
    }

    @Test
    void refusesAKeyThatClimbsOutOfTheDrive() {

        // Followed blindly, '..' would point the vault at any file on the host.
        assertThatThrownBy(
                        () ->
                                read(
                                        """
                                        project: { name: porelo, url: /srv/p.git, signed_by: SHA256:a }
                                        keys:
                                            - { name: stolen, path: ../../etc/shadow }
                                        """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside /mnt/drive");
    }

    @Test
    void refusesAnAbsoluteKeyPathElsewhere() {

        assertThatThrownBy(
                        () ->
                                read(
                                        """
                                        project: { name: porelo, url: /srv/p.git, signed_by: SHA256:a }
                                        keys:
                                            - { name: elsewhere, path: /root/.ssh/id_ed25519 }
                                        """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside /mnt/drive");
    }

    @Test
    void aTaskNamesItsAgentAndThePackageThatInstallsIt() {

        final MachineFile file =
                read(
                        """
                        project: { name: porelo, url: /srv/p.git, signed_by: SHA256:a }
                        tasks:
                            - { name: core-1, repository: core, agent: pi }
                            - { name: core-2, repository: core, agent: claude, package: vendor-claude }
                            - { name: core-3, repository: core }
                        """);

        assertThat(file.tasks())
                .as("the agent each task runs, and what installs it where the file says")
                .containsExactly(
                        new MachineFile.Task("core-1", "core", "pi", null),
                        new MachineFile.Task("core-2", "core", "claude", "vendor-claude"),
                        new MachineFile.Task("core-3", "core", null, null));
    }

    @Test
    void refusesAnAgentNamedInAnotherForm() {

        assertThatThrownBy(
                        () ->
                                read(
                                        """
                                        project: { name: porelo, url: /srv/p.git, signed_by: SHA256:a }
                                        tasks:
                                            - { name: core-1, repository: core, agent: "Pi --force" }
                                        """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("core-1")
                .hasMessageContaining("agent");
    }

    @Test
    void refusesAPackageThatIsNoPackagesName() {

        assertThatThrownBy(
                        () ->
                                read(
                                        """
                                        project: { name: porelo, url: /srv/p.git, signed_by: SHA256:a }
                                        tasks:
                                            - { name: core-1, repository: core, agent: pi, package: "pi; rm -rf /" }
                                        """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("core-1")
                .hasMessageContaining("package");
    }

    @Test
    void refusesAPackageWithoutTheAgentItInstalls() {

        assertThatThrownBy(
                        () ->
                                read(
                                        """
                                        project: { name: porelo, url: /srv/p.git, signed_by: SHA256:a }
                                        tasks:
                                            - { name: core-1, repository: core, package: porelo-agent-pi }
                                        """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("core-1")
                .hasMessageContaining("no 'agent' it installs");
    }

    @Test
    void refusesTasksThatAreNotAList() {

        assertThatThrownBy(
                        () ->
                                read(
                                        """
                                        project: { name: porelo, url: /srv/p.git, signed_by: SHA256:a }
                                        tasks: core-1
                                        """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'tasks' is not a list");
    }
}
