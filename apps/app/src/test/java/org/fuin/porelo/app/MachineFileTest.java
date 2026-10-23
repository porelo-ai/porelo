package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests for {@link MachineFile}, Porelo's part of a person's machine file. */
class MachineFileTest {

    private static final Path DRIVE = Path.of("/mnt/drive");

    private static MachineFile read(final String text) {
        return MachineFile.read(new StringReader(text), DRIVE, "machine.yml");
    }

    @Test
    void readsTheProjectAndTheKeysAndIgnoresTheRest() {

        final MachineFile file =
                read(
                        """
                        provider: hetzner
                        project:
                            name: porelo
                            url: "git@github.com:porelo-ai/porelo-project.git"
                            signed_by: "SHA256:abc"
                        keys:
                            - { name: github-deploy, path: keys/github-deploy }
                        """);

        assertThat(file.project()).as("project").isEqualTo("porelo");
        assertThat(file.signedBy()).as("key").isEqualTo("SHA256:abc");
        assertThat(file.keys())
                .as("a key by its absolute path on the drive")
                .containsExactly(
                        new MachineFile.Key(
                                "github-deploy", Path.of("/mnt/drive/keys/github-deploy")));
    }

    @Test
    void aFileWithoutKeysHasNone() {

        final MachineFile file =
                read("project: { name: porelo, url: /srv/porelo.git, signed_by: SHA256:abc }\n");

        assertThat(file.keys()).as("keys").isEmpty();
    }

    @Test
    void refusesAProjectWithoutTheKeyItIsCheckedAgainst() {

        // A play must never follow what it did not check, so there is no unverified form here.
        assertThatThrownBy(() -> read("project: { name: porelo, url: /srv/porelo.git }\n"))
                .as("invalid machine input is refused")
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
                .as("invalid machine input is refused")
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
                .as("invalid machine input is refused")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside /mnt/drive");
    }

    @ParameterizedTest
    @ValueSource(strings = {"tasks", "agents"})
    void refusesInstructionsThatTheMachineDoesNotCarryOut(final String section) {
        assertThatThrownBy(
                        () ->
                                read(
                                        "project: { name: porelo, url: /srv/p.git, signed_by: SHA256:a }\n"
                                                + section
                                                + ": []\n"))
                .as("machine instructions must never silently promise tasks or agent selection")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("every agent")
                .hasMessageContaining("porelo task start");
    }
}
