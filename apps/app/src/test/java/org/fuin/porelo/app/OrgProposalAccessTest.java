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
                                "the token's variable must reach git even outside the organization's URL prefix")
                        .containsEntry("FORGE_TOKEN", "lent-token");
                assertThat(String.join(" ", lease.arguments()))
                        .as("git gets a scoped helper naming the source, without its secret")
                        .contains("--env", "FORGE_TOKEN")
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
                assertThat(lease.environment()).doesNotContainValue("lent-token");
            }
        } finally {
            if (previous == null) System.clearProperty(PoreloBinary.NAMED);
            else System.setProperty(PoreloBinary.NAMED, previous);
        }
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
