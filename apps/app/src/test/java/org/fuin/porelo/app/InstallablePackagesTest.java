package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;

/** Test for {@link InstallablePackages}. */
class InstallablePackagesTest {

    private FakeCommandRunner debian() {
        return new FakeCommandRunner()
                .answering("command -v apt-cache", "")
                .answering("command -v dpkg-query", "")
                .answering(
                        "apt-cache showpkg porelo-agent",
                        """
                        Package: porelo-agent
                        Versions:

                        Reverse Depends:
                        Dependencies:
                        Provides:
                        Reverse Provides:
                        porelo-agent-claude 1.2.3
                        porelo-agent-omp 0.9.0
                        """)
                .answering(
                        "apt-cache showpkg porelo-transport",
                        """
                        Package: porelo-transport
                        Reverse Provides:
                        porelo-message-transport-local 1.0.0
                        """)
                .answering(
                        "apt-cache showpkg porelo-homeserver",
                        """
                        Package: porelo-homeserver
                        Reverse Provides:
                        porelo-matrix-homeserver 0.1.0
                        """)
                .answering(
                        "apt-cache show porelo-matrix-homeserver",
                        "Version: 0.1.0\nDescription: The machine's own homeserver for Porelo's conversations\n")
                .answering(
                        "apt-cache show porelo-agent-claude",
                        "Version: 1.2.3\nDescription: Claude Code inside a Porelo task\n")
                .answering(
                        "apt-cache show porelo-agent-omp",
                        "Version: 0.9.0\nDescription: oh-my-pi inside a Porelo task\n")
                .answering(
                        "apt-cache show porelo-message-transport-local",
                        "Version: 1.0.0\nDescription: Carries messages between mailboxes here\n");
    }

    @Test
    void lists_what_the_repository_provides() {
        final FakeCommandRunner runner =
                debian().failing(
                                "dpkg-query -W -f=${Version} porelo-agent-omp",
                                1,
                                "no packages found")
                        .answering("dpkg-query -W -f=${Version} porelo-agent-claude", "1.2.3")
                        .failing(
                                "dpkg-query -W -f=${Version} porelo-message-transport-local",
                                1,
                                "none")
                        .failing("dpkg-query -W -f=${Version} porelo-matrix-homeserver", 1, "none");

        final var found = new InstallablePackages(runner).list();

        assertThat(found)
                .extracting(InstallablePackages.Installable::name)
                .containsExactly(
                        "porelo-agent-claude",
                        "porelo-agent-omp",
                        "porelo-message-transport-local",
                        "porelo-matrix-homeserver");
        // The homeserver a transport's conversation runs on, offered beside it: without it a new
        // machine that chose Matrix had a first task start that failed for want of one.
        assertThat(found)
                .extracting(InstallablePackages.Installable::kind)
                .as("what a person chooses between, not how it was asked for")
                .containsExactly("agent", "agent", "transport", "homeserver");
        assertThat(found.get(0).installed()).as("what is already here says so").isTrue();
        assertThat(found.get(1).installed()).isFalse();
        assertThat(found.get(0).description()).isEqualTo("Claude Code inside a Porelo task");
    }

    /**
     * A virtual package with several providers cannot be installed by its own name - the package
     * manager asks or refuses - so only real names may reach a person choosing.
     */
    @Test
    void never_offers_the_virtual_name_itself() {
        assertThat(new InstallablePackages(debian()).list())
                .extracting(InstallablePackages.Installable::name)
                .doesNotContain(
                        InstallablePackages.PROVIDES_AGENT, InstallablePackages.PROVIDES_TRANSPORT);
    }

    @Test
    void a_machine_with_no_package_manager_offers_nothing() {
        final FakeCommandRunner bare = new FakeCommandRunner().failing("command -v", 1, "");

        assertThat(new InstallablePackages(bare).list()).isEmpty();
    }
}
