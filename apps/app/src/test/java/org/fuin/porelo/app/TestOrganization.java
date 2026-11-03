package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.organization.OrganizationWrites;
import org.fuin.porelo.organization.gittree.CommitSigning;
import org.fuin.porelo.organization.gittree.FetchAccess;
import org.fuin.porelo.organization.gittree.GitTreeStore;
import org.fuin.porelo.organization.gittree.GitTreeWrites;

/**
 * An organization of the new structure in a test's directory, founded by michael, which the machine
 * of a {@link RegistryCheckout} names in its settings: the real store and its real writes, on a
 * repository and a forge of the test's own.
 */
final class TestOrganization {

    /** The address the settings name, which no test ever reaches. */
    static final String ADDRESS = "https://forge.example.org/acme/organization.git";

    final RegistryCheckout machine;

    final Path repo;

    org.fuin.porelo.organization.IdentitySource identities;
    URI forgeAddress = URI.create("https://forge.example.org");
    private String writeAddress;
    private FetchAccess writeAccess = FetchAccess.NONE;

    /** Whether a change finds it out of reach, the read before it having found it. */
    boolean unreachableForChanges;

    /** What the next change ends with once the store took it: its answer lost, or the run dead. */
    @org.jspecify.annotations.Nullable Exception afterPush;

    TestOrganization(final RegistryCheckout machine) throws IOException {
        this.machine = machine;
        repo = Files.createDirectory(machine.dir.resolve("organization"));
        identities = machine.forge;
        writeAddress = repo.toString();
        git("init", "-q", "-b", "main");
        git("config", "user.name", "Organization");
        git("config", "user.email", "organization@example.org");
        git("config", "gpg.format", "ssh");
        git("config", "receive.denyCurrentBranch", "updateInstead");
        machine.forge.owners.put("michael", true);
        machine.forge.keys.put("michael", List.of(machine.key("michael")));
        Files.writeString(
                repo.resolve("owners.yml"),
                "owners:\n  - login: michael\n    key: \"" + machine.key("michael") + "\"\n");
        git("add", "-A");
        git(
                "-c",
                "user.signingkey=" + machine.keyFiles.get("michael"),
                "commit",
                "-q",
                "-S",
                "-m",
                "found");
        final Path config = machine.paths.xdg().config();
        Files.createDirectories(config);
        Files.writeString(
                config.resolve("organization.yml"),
                "adapter: git\nsettings:\n  repository: " + ADDRESS + "\n");
    }

    /** Runs git in the organization's repository, whatever this account's configuration says. */
    String git(final String... args) {
        final List<String> all = new ArrayList<>(List.of("git"));
        all.addAll(List.of(args));
        return machine.runner
                .runOrFail(
                        Command.of(all)
                                .in(repo)
                                .withEnvironment(
                                        Map.of(
                                                "GIT_CONFIG_GLOBAL",
                                                "/dev/null",
                                                "GIT_CONFIG_NOSYSTEM",
                                                "1")))
                .trimmedOutput();
    }

    /** Leaves the repository reachable and holding nothing, as a forge holds one made empty. */
    void holdsNothing() {
        git("update-ref", "-d", "refs/heads/main");
    }

    /** Takes the repository away, leaving its address and what this machine last read of it. */
    void outOfReach() throws IOException {
        Files.move(repo, machine.dir.resolve("away"));
    }

    /** Where a change goes: its repository, or a path that is not there. */
    private String whereChangesGo() {
        return unreachableForChanges ? machine.dir.resolve("nowhere.git").toString() : writeAddress;
    }

    /** Opens this organization, as the machine opens the one its settings name. */
    IdentityOrganization.Opener opener() {
        return (context, repository) -> {
            assertThat(repository).isEqualTo(ADDRESS);
            return new IdentityOrganization.Opened(
                    GitTreeStore.at(
                            machine.runner,
                            repo.toString(),
                            "acme",
                            "forge.example.org",
                            FetchAccess.NONE,
                            context.paths().xdg().state().resolve("organization")),
                    identities,
                    forgeAddress,
                    signing ->
                            interrupted(
                                    new GitTreeWrites(
                                            machine.runner,
                                            whereChangesGo(),
                                            "acme",
                                            writeAccess,
                                            identities,
                                            signing)));
        };
    }

    /** A Git SSH transport stand-in authenticates through the agent with a separate key. */
    void authenticateWith(final String holder) throws IOException {
        final Path publicKey = Path.of(machine.keyFiles.get(holder) + ".pub");
        final Path challenge = machine.dir.resolve("authentication-challenge");
        final Path allowed = machine.dir.resolve("authentication-keys");
        final Path transport = machine.dir.resolve("git-ssh");
        Files.writeString(challenge, "the transport asks who is pushing\n");
        Files.writeString(allowed, "alice " + machine.key(holder) + "\n");
        Files.writeString(
                transport,
                """
                #!/bin/sh
                set -eu
                rm -f '%s.sig'
                ssh-keygen -q -Y sign -n transport -f '%s' '%s'
                ssh-keygen -q -Y verify -n transport -f '%s' -I alice -s '%s.sig' < '%s' > /dev/null
                for argument do
                    case "$argument" in
                        'git-upload-pack '*) exec git-upload-pack '%s' ;;
                        'git-receive-pack '*) printf 'authenticated\\n' >> '%s'; exec git-receive-pack '%s' ;;
                    esac
                done
                exit 1
                """
                        .formatted(
                                challenge,
                                publicKey,
                                challenge,
                                allowed,
                                challenge,
                                challenge,
                                repo,
                                machine.dir.resolve("authenticated-push"),
                                repo));
        Files.setPosixFilePermissions(
                transport, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        writeAddress = "ssh://git@forge.example.org/acme/organization.git";
        writeAccess =
                address ->
                        new FetchAccess.Lease() {
                            @Override
                            public Map<String, String> environment() {
                                return Map.of(
                                        "GIT_SSH_COMMAND",
                                        transport.toString(),
                                        "GIT_SSH_VARIANT",
                                        "ssh",
                                        "SSH_AUTH_SOCK",
                                        java.util.Objects.requireNonNull(
                                                machine.environment().apply("SSH_AUTH_SOCK")));
                            }
                        };
    }

    /** The writes as they are, but for what {@link #afterPush} says of the next change. */
    private OrganizationWrites interrupted(final OrganizationWrites writes) {
        return (OrganizationWrites)
                java.lang.reflect.Proxy.newProxyInstance(
                        OrganizationWrites.class.getClassLoader(),
                        new Class<?>[] {OrganizationWrites.class},
                        (proxy, method, args) -> {
                            final Object written;
                            try {
                                written = method.invoke(writes, args);
                            } catch (java.lang.reflect.InvocationTargetException ex) {
                                throw ex.getCause();
                            }
                            final Exception then = afterPush;
                            if (then != null) {
                                afterPush = null;
                                throw then;
                            }
                            return written;
                        });
    }

    /** Serves an older state again, as a forge may: whatever came after it is gone from here. */
    void servesAgain(final String revision) {
        git("reset", "-q", "--hard", revision);
    }

    /** The writes of someone holding these logins' key files, for what a test sets up. */
    OrganizationWrites writes() {
        final Map<String, Path> files = new HashMap<>();
        machine.keys
                .keySet()
                .forEach(each -> files.put(machine.key(each), machine.keyFiles.get(each)));
        return new GitTreeWrites(
                machine.runner,
                repo.toString(),
                "acme",
                FetchAccess.NONE,
                machine.forge,
                new CommitSigning(Map.of(), files));
    }

    /** What a change by this login with this key rests on, the store as it is now. */
    OrganizationWrites.Change change(final String login, final String holder, final String id) {
        return new OrganizationWrites.Change(
                new OrganizationWrites.Acting(login, machine.key(holder)), tip(), id);
    }

    /** Files a login's first card, on the forge's word, and answers the card. */
    String filed(final String login) throws IOException {
        machine.forge.keys.put(login, new ArrayList<>(List.of(machine.key(login))));
        final String card = machine.card(login, login);
        writes().addCard(change(login, login, "first-" + login), card);
        return card;
    }

    /** The commit the organization stands at. */
    String tip() {
        return git("rev-parse", "HEAD");
    }

    /** How many commits it holds. */
    int commits() {
        return Integer.parseInt(git("rev-list", "--count", "HEAD"));
    }

    /** The card it holds of a login, with its last line end, as it was filed. */
    String card(final String login) throws IOException {
        return Files.readString(repo.resolve("people/" + login + ".card"));
    }

    /** Stops the organization's forge listing only what it listed, adding these keys of a login. */
    void listed(final String login, final String... holders) {
        final List<String> keys =
                new ArrayList<>(machine.forge.keys.getOrDefault(login, List.of()));
        for (final String holder : holders) {
            keys.add(machine.key(holder));
        }
        machine.forge.keys.put(login, keys);
    }

    /** The owners file as it stands. */
    String owners() throws IOException {
        return Files.readString(repo.resolve("owners.yml"));
    }

    /** Who signed a commit, among the logins of this machine, as a reader judges it. */
    String signer(final String commit) throws IOException {
        final Path signers = machine.dir.resolve("allowed-" + System.nanoTime());
        final StringBuilder lines = new StringBuilder();
        machine.keys
                .keySet()
                .forEach(
                        each ->
                                lines.append(each)
                                        .append(" namespaces=\"git\" ")
                                        .append(machine.key(each))
                                        .append('\n'));
        Files.writeString(signers, lines.toString());
        return machine.runner
                .run(
                        Command.of(
                                        "git",
                                        "-c",
                                        "gpg.ssh.allowedSignersFile=" + signers,
                                        "log",
                                        "-1",
                                        "--format=%GS",
                                        commit)
                                .in(repo)
                                .withEnvironment(
                                        Map.of(
                                                "GIT_CONFIG_GLOBAL",
                                                "/dev/null",
                                                "GIT_CONFIG_NOSYSTEM",
                                                "1")))
                .trimmedOutput();
    }
}
