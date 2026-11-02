package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.organization.OfflineOrganization;
import org.fuin.porelo.organization.OrganizationException;
import org.fuin.porelo.organization.gittree.FetchAccess;
import org.fuin.porelo.organization.gittree.GitTreeStore;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.SigningKey;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * {@code porelo org fetch}: what it takes and what it hands the adapter. Harm guarded: it runs as
 * root with no person, so a credential named by a file, a pin it misread or a stale answer becomes
 * who may log in on the host.
 */
class OrgFetchCommandTest {

    private static final String SETTINGS =
            "adapter: git\nsettings:\n  repository: git@github.com:acme/organization.git\n";

    private static final String SINCE = "a".repeat(40);

    @TempDir private Path dir;

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private final List<Set<String>> pins = new ArrayList<>();

    private final List<String> sinces = new ArrayList<>();

    private final List<FetchAccess> accesses = new ArrayList<>();

    private final String key = PersonCard.keyLine(SigningKey.generate("hank"));

    private boolean unreachable;

    private boolean unprovable;

    @BeforeEach
    void files() throws Exception {
        Files.writeString(dir.resolve("settings"), SETTINGS);
        Files.writeString(dir.resolve("key"), "the deploy key\n");
        Files.writeString(dir.resolve("known"), "github.com ssh-ed25519 AAAA\n");
        Files.writeString(dir.resolve("pin"), "hank " + key + "\n");
    }

    private int run(final String... more) {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    default -> null;
                                },
                        dir);
        final CommandLine line =
                PoreloCli.commandLine(
                        new PoreloContext(
                                new ProcessCommandRunner(),
                                new PoreloPaths(xdg, dir.resolve("bin")),
                                arguments -> 0));
        line.setOut(new PrintWriter(out));
        line.setErr(new PrintWriter(err));
        ((OrgFetchCommand)
                                line.getSubcommands()
                                        .get("org")
                                        .getSubcommands()
                                        .get("fetch")
                                        .getCommand())
                        .fetcher =
                (machine, address, access) -> {
                    accesses.add(access);
                    return new OfflineOrganization() {
                        @Override
                        public Map<String, Object> check(
                                final String host, final String pinned, final String since) {
                            throw new AssertionError("a host's pin is a set");
                        }

                        @Override
                        public Map<String, Object> check(
                                final String host, final Set<String> pinned, final String since)
                                throws java.io.IOException {
                            if (unprovable) {
                                throw new OrganizationException(
                                        "a shallow history proves nothing", null);
                            }
                            if (unreachable) {
                                GitTreeStore.at(
                                                new ProcessCommandRunner(),
                                                dir.resolve("gone").toString(),
                                                "acme",
                                                "github.com",
                                                FetchAccess.NONE,
                                                dir.resolve("gone-state"))
                                        .read(new NoForge());
                            }
                            pins.add(pinned);
                            sinces.add(since);
                            return Map.of("revision", "r1");
                        }
                    };
                };
        final List<String> arguments =
                new ArrayList<>(
                        List.of(
                                "org",
                                "fetch",
                                "--json",
                                "--host",
                                "build",
                                "--settings",
                                dir.resolve("settings").toString(),
                                "--ssh-key",
                                dir.resolve("key").toString(),
                                "--known-hosts",
                                dir.resolve("known").toString(),
                                "--pin",
                                dir.resolve("pin").toString()));
        arguments.addAll(List.of(more));
        return line.execute(arguments.toArray(String[]::new));
    }

    private static final class NoForge implements org.fuin.porelo.organization.IdentitySource {
        @Override
        public java.util.Optional<Keys> keys(final String login) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<Boolean> owns(final String organisation, final String login) {
            return java.util.Optional.empty();
        }
    }

    private Object reason() {
        return ((Map<?, ?>) Json.parse(out.toString().strip())).get("reason");
    }

    @Test
    void thePinIsHandedOnAsEveryKeyItHolds() throws Exception {
        final String backup = PersonCard.keyLine(SigningKey.generate("backup"));
        Files.writeString(dir.resolve("pin"), "hank " + key + "\n\nhank " + backup + "\n");

        assertThat(run("--since", SINCE)).as("what it said: %s", err).isZero();

        assertThat(pins.getFirst())
                .containsExactlyInAnyOrder(
                        PersonCard.fingerprint(key), PersonCard.fingerprint(backup));
        assertThat(sinces.getFirst()).isEqualTo(SINCE);
    }

    @Test
    void theFetchUsesTheHostsKeyAndPinnedHostKeyAndNothingOfAnAccount() throws Exception {
        assertThat(run()).isZero();

        final String command = accesses.getFirst().lend("x").environment().get("GIT_SSH_COMMAND");
        assertThat(command)
                .contains("-i '" + dir.resolve("key") + "'")
                .contains("UserKnownHostsFile='" + dir.resolve("known") + "'")
                .as("first use is not trusted")
                .contains("StrictHostKeyChecking=yes")
                .contains("IdentitiesOnly=yes")
                .contains("IdentityAgent=none")
                .contains("GlobalKnownHostsFile=/dev/null")
                .contains("-F /dev/null");
    }

    @Test
    void settingsNamingACredentialOfTheirOwnAreRefused() throws Exception {
        Files.writeString(
                dir.resolve("settings"),
                "adapter: git\nsettings:\n  repository: git@github.com:acme/o.git\n  token: t\n");

        assertThat(run()).isEqualTo(64);

        assertThat(reason()).isEqualTo("usage");
        assertThat(accesses).as("nothing was fetched").isEmpty();
    }

    @Test
    void anAddressAKeyCannotReachIsRefused() throws Exception {
        Files.writeString(
                dir.resolve("settings"),
                "adapter: git\nsettings:\n  repository: https://github.com/acme/o.git\n");

        assertThat(run()).isEqualTo(64);
    }

    @Test
    void aPinThatIsNotOneIsRefusedWithoutSayingWhatItHeld() throws Exception {
        Files.writeString(dir.resolve("pin"), "a-secret-looking-line\n");

        assertThat(run()).isEqualTo(64);

        assertThat(out + err.toString()).doesNotContain("a-secret-looking-line");
    }

    @Test
    void anEmptyPinIsRefusedSinceNothingCouldThenBeSignedForIt() throws Exception {
        Files.writeString(dir.resolve("pin"), "\n");

        assertThat(run()).isEqualTo(64);
    }

    @Test
    void aSinceThatIsNoCommitsFullNameIsRefused() {
        assertThat(run("--since", "HEAD")).isEqualTo(64);
    }

    @Test
    void aHostThatIsNoHostsNameIsRefused() {
        final int exit = run("--host", "../etc");

        assertThat(exit).isEqualTo(64);
    }

    @Test
    void aStoreThatCannotBeReachedCannotSayAndIsNeverAStaleAnswer() {
        unreachable = true;

        assertThat(run()).as("what it said: %s", err).isEqualTo(69);

        assertThat(reason()).isEqualTo("cannot-say");
    }

    @Test
    void aHistoryThatProvesNothingIsRefusedAndNotAFailure() {
        unprovable = true;

        assertThat(run()).as("what it said: %s", err).isEqualTo(65);

        assertThat(out.toString()).contains("refused-state");
    }

    @Test
    void theForgesPinnedHostKeyFileIsNeverRewritten() throws Exception {
        assertThat(run()).isZero();

        try (FetchAccess.Lease lease = accesses.get(0).lend("git@github.com:acme/org.git")) {
            assertThat(lease.environment().toString() + lease.arguments())
                    .contains("UpdateHostKeys=no");
        }
    }
}
