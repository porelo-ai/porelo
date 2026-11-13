package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.organization.gittree.ForgeAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

/** A token declared for the organization's URL also reaches its same-forge personal fork. */
@Isolated
class OrgProposalAccessTest {
    /** The one name a lent token reaches git under, whatever variable it was declared in. */
    private static final String LENT_UNDER = "PORELO_GIT_CREDENTIAL_TOKEN";

    @TempDir Path dir;

    @Test
    void anOrganizationScopedTokenReachesTheSameForgeForkWithoutEnteringArguments()
            throws Exception {
        String previous = System.getProperty(PoreloBinary.NAMED);
        System.setProperty(PoreloBinary.NAMED, dir.resolve("porelo").toString());
        try {
            PoreloContext machine = context();
            ForgeAddress origin =
                    ForgeAddress.of("https://forge.example.org/acme/organization.git");
            assertThat(OrganizationAccess.forgeToken(machine, origin))
                    .as("the API and the fork borrow the same declared token")
                    .isEqualTo("lent-token");
            try (var lease =
                    OrgProposalAccess.lent(machine, origin)
                            .lend("https://forge.example.org/alice/organization.git")) {
                assertThat(lease.environment())
                        .as(
                                "the token must reach git even outside the organization's URL prefix,"
                                        + " under porelo's own name")
                        .containsEntry(LENT_UNDER, "lent-token")
                        .doesNotContainKey("FORGE_TOKEN");
                assertThat(String.join(" ", lease.arguments()))
                        .as(
                                "git gets a scoped helper naming the lease's variable, without its secret")
                        .contains("--env", LENT_UNDER)
                        .doesNotContain("lent-token");
            }
        } finally {
            if (previous == null) System.clearProperty(PoreloBinary.NAMED);
            else System.setProperty(PoreloBinary.NAMED, previous);
        }
    }

    @Test
    void theOrganizationTokenIsNotOfferedToAnotherForge() throws Exception {
        PoreloContext machine = context();
        ForgeAddress origin = ForgeAddress.of("https://forge.example.org/acme/organization.git");
        try (var lease =
                OrgProposalAccess.lent(machine, origin)
                        .lend("https://other.example.org/alice/organization.git")) {
            assertThat(lease.environment())
                    .as("a different forge receives no organization token")
                    .doesNotContainKey("FORGE_TOKEN");
        }
    }

    @Test
    void aTokenDeclaredInAVariableGitReadsItselfIsNeverHandedToGit() throws Exception {
        // Git echoes a GIT_TRACE it cannot take, and the echo reaches a refusal.
        String previous = System.getProperty(PoreloBinary.NAMED);
        System.setProperty(PoreloBinary.NAMED, dir.resolve("porelo").toString());
        try {
            PoreloContext machine =
                    new PoreloContext(
                            new ProcessCommandRunner(),
                            new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                            args -> 0,
                            name -> "GIT_TRACE".equals(name) ? "lent-token" : null);
            Path declarations = machine.paths().xdg().config().resolve("credentials.yml");
            Files.createDirectories(declarations.getParent());
            Files.writeString(
                    declarations,
                    "credentials:\n  - match: https://forge.example.org/acme/\n    kind: token\n    source: env\n    env: GIT_TRACE\n");
            try (var lease =
                    OrganizationAccess.lent(machine, "")
                            .lend("https://forge.example.org/acme/organization.git")) {
                assertThat(lease.environment()).doesNotContainKey("GIT_TRACE");
            }
        } finally {
            if (previous == null) System.clearProperty(PoreloBinary.NAMED);
            else System.setProperty(PoreloBinary.NAMED, previous);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "HTTPS_PROXY",
                "ALL_PROXY",
                "https_proxy",
                "http_proxy",
                "NO_PROXY",
                "CURL_CA_BUNDLE",
                "SSL_CERT_FILE",
                "HOME",
                "XDG_CONFIG_HOME",
                "PATH",
                "GIT_TRACE",
                "PORELO_VAULT",
                "SHELLOPTS",
                "BASHOPTS",
                "PS4",
                "POSIXLY_CORRECT",
                "BASH_ENV",
                "FORGE_TOKEN"
            })
    void aTokenIsNeverHandedToGitUnderTheNameItWasDeclaredIn(final String name) throws Exception {
        // With a token in HTTPS_PROXY git said "Unsupported proxy syntax in '<the token>'"; bash
        // read SHELLOPTS at its start and echoed what it could not take.
        String previous = System.getProperty(PoreloBinary.NAMED);
        System.setProperty(PoreloBinary.NAMED, dir.resolve("porelo").toString());
        try {
            PoreloContext machine = declared(name, "lent-token:bad");
            try (var lease =
                    OrganizationAccess.lent(machine, "")
                            .lend("https://forge.example.org/acme/organization.git")) {
                assertThat(lease.environment())
                        .as("no name a person chose steers git, its shell or its transport")
                        .doesNotContainKey(name)
                        .as("the token still reaches git's helper, under porelo's own name")
                        .containsEntry(LENT_UNDER, "lent-token:bad");
            }
        } finally {
            if (previous == null) System.clearProperty(PoreloBinary.NAMED);
            else System.setProperty(PoreloBinary.NAMED, previous);
        }
    }

    @Test
    void aLeaseNamesTheTokenItLentInEachForm() throws Exception {
        String previous = System.getProperty(PoreloBinary.NAMED);
        System.setProperty(PoreloBinary.NAMED, dir.resolve("porelo").toString());
        try {
            PoreloContext machine = declared("FORGE_TOKEN", "lent-token");
            try (var lease =
                    OrganizationAccess.lent(machine, "")
                            .lend("https://forge.example.org/acme/organization.git")) {
                assertThat(lease.lent())
                        .as("what git could say back is known to whoever reads git's words")
                        .contains(
                                "lent-token",
                                java.util.Base64.getEncoder()
                                        .encodeToString(
                                                "x-access-token:lent-token"
                                                        .getBytes(
                                                                java.nio.charset.StandardCharsets
                                                                        .UTF_8)));
            }
            try (var lease =
                    OrgProposalAccess.lent(
                                    machine,
                                    ForgeAddress.of(
                                            "https://forge.example.org/acme/organization.git"))
                            .lend("https://forge.example.org/alice/organization.git")) {
                assertThat(lease.lent()).as("the fork's lease names it too").contains("lent-token");
            }
        } finally {
            if (previous == null) System.clearProperty(PoreloBinary.NAMED);
            else System.setProperty(PoreloBinary.NAMED, previous);
        }
    }

    @Test
    void aTokenDeclaredInTheAgentsVariableIsNeverTakenForTheAgentsSocket() throws Exception {
        // The production path: the socket comes from this machine's own SSH_AUTH_SOCK, which here
        // is the declared token.
        String previous = System.getProperty(PoreloBinary.NAMED);
        System.setProperty(PoreloBinary.NAMED, dir.resolve("porelo").toString());
        try {
            PoreloContext machine = declared("SSH_AUTH_SOCK", "lent-token");
            try (var lease =
                    OrganizationAccess.lent(machine)
                            .lend("https://forge.example.org/acme/organization.git")) {
                assertThat(lease.environment()).doesNotContainEntry("SSH_AUTH_SOCK", "lent-token");
            }
            try (var lease =
                    OrgProposalAccess.lent(
                                    machine,
                                    ForgeAddress.of(
                                            "https://forge.example.org/acme/organization.git"))
                            .lend("https://forge.example.org/alice/organization.git")) {
                assertThat(lease.environment()).doesNotContainEntry("SSH_AUTH_SOCK", "lent-token");
            }
        } finally {
            if (previous == null) System.clearProperty(PoreloBinary.NAMED);
            else System.setProperty(PoreloBinary.NAMED, previous);
        }
    }

    @Test
    void aTokenFileReadOnceIsWhatGitIsHandedThoughTheFileChangesAfter() throws Exception {
        // Read twice, a token rotated between the reads was lent while the other was removed.
        String previous = System.getProperty(PoreloBinary.NAMED);
        System.setProperty(PoreloBinary.NAMED, dir.resolve("porelo").toString());
        try {
            Path file = dir.resolve("token");
            Files.writeString(file, "lent-token\n");
            PoreloContext machine = fileDeclared(file);
            try (var lease =
                    OrganizationAccess.lent(machine, "")
                            .lend("https://forge.example.org/acme/organization.git")) {
                Files.writeString(file, "rotated-token\n");
                assertThat(lease.environment())
                        .as("the helper is handed the value the lease read")
                        .containsEntry(LENT_UNDER, "lent-token");
                assertThat(String.join(" ", lease.arguments()))
                        .as("and reads nothing again")
                        .contains("--env", LENT_UNDER)
                        .doesNotContain(file.toString());
                assertThat(lease.lent()).contains("lent-token");
            }
        } finally {
            if (previous == null) System.clearProperty(PoreloBinary.NAMED);
            else System.setProperty(PoreloBinary.NAMED, previous);
        }
    }

    @Test
    void aTokenFileThatIsNoRegularFileIsNotRead() throws Exception {
        // A FIFO with no writer blocked the lease before any limit ran.
        String previous = System.getProperty(PoreloBinary.NAMED);
        System.setProperty(PoreloBinary.NAMED, dir.resolve("porelo").toString());
        try {
            Path fifo = dir.resolve("token-fifo");
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    new ProcessBuilder("mkfifo", fifo.toString()).start().waitFor() == 0,
                    "mkfifo is not here");
            PoreloContext machine = fileDeclared(fifo);
            final var lending =
                    java.util.concurrent.CompletableFuture.supplyAsync(
                            () -> {
                                try (var lease =
                                        OrganizationAccess.lent(machine, "")
                                                .lend(
                                                        "https://forge.example.org/acme/organization.git")) {
                                    return lease.environment();
                                }
                            });
            assertThat(lending.get(10, java.util.concurrent.TimeUnit.SECONDS))
                    .doesNotContainKey(LENT_UNDER);
        } finally {
            if (previous == null) System.clearProperty(PoreloBinary.NAMED);
            else System.setProperty(PoreloBinary.NAMED, previous);
        }
    }

    private PoreloContext fileDeclared(final Path file) throws Exception {
        PoreloContext machine =
                new PoreloContext(
                        new ProcessCommandRunner(),
                        new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                        args -> 0,
                        name -> null);
        Path declarations = machine.paths().xdg().config().resolve("credentials.yml");
        Files.createDirectories(declarations.getParent());
        Files.writeString(
                declarations,
                "credentials:\n  - match: https://forge.example.org/acme/\n    kind: token\n    source: file\n    file: "
                        + file
                        + "\n");
        return machine;
    }

    @Test
    void aLeaseNamesATokenLentFromAFile() throws Exception {
        String previous = System.getProperty(PoreloBinary.NAMED);
        System.setProperty(PoreloBinary.NAMED, dir.resolve("porelo").toString());
        try {
            Path file = dir.resolve("token");
            Files.writeString(file, "lent-token\n");
            PoreloContext machine =
                    new PoreloContext(
                            new ProcessCommandRunner(),
                            new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                            args -> 0,
                            name -> null);
            Path declarations = machine.paths().xdg().config().resolve("credentials.yml");
            Files.createDirectories(declarations.getParent());
            Files.writeString(
                    declarations,
                    "credentials:\n  - match: https://forge.example.org/acme/\n    kind: token\n    source: file\n    file: "
                            + file
                            + "\n");
            try (var lease =
                    OrganizationAccess.lent(machine, "")
                            .lend("https://forge.example.org/acme/organization.git")) {
                assertThat(lease.lent()).contains("lent-token");
            }
        } finally {
            if (previous == null) System.clearProperty(PoreloBinary.NAMED);
            else System.setProperty(PoreloBinary.NAMED, previous);
        }
    }

    private PoreloContext declared(final String variable, final String value) throws Exception {
        PoreloContext machine =
                new PoreloContext(
                        new ProcessCommandRunner(),
                        new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                        args -> 0,
                        name -> variable.equals(name) ? value : null);
        Path declarations = machine.paths().xdg().config().resolve("credentials.yml");
        Files.createDirectories(declarations.getParent());
        Files.writeString(
                declarations,
                "credentials:\n  - match: https://forge.example.org/acme/\n    kind: token\n    source: env\n    env: "
                        + variable
                        + "\n");
        return machine;
    }

    PoreloContext context() throws Exception {
        PoreloContext machine =
                new PoreloContext(
                        new ProcessCommandRunner(),
                        new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                        args -> 0,
                        name -> "FORGE_TOKEN".equals(name) ? "lent-token" : null);
        Path declarations = machine.paths().xdg().config().resolve("credentials.yml");
        Files.createDirectories(declarations.getParent());
        Files.writeString(
                declarations,
                "credentials:\n  - match: https://forge.example.org/acme/\n    kind: token\n    source: env\n    env: FORGE_TOKEN\n");
        return machine;
    }
}
