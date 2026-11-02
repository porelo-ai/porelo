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

    /** Whether a change finds it out of reach, the read before it having found it. */
    boolean unreachableForChanges;

    TestOrganization(final RegistryCheckout machine) throws IOException {
        this.machine = machine;
        repo = Files.createDirectory(machine.dir.resolve("organization"));
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

    /** Takes the repository away, leaving its address and what this machine last read of it. */
    void outOfReach() throws IOException {
        Files.move(repo, machine.dir.resolve("away"));
    }

    /** Where a change goes: its repository, or a path that is not there. */
    private String whereChangesGo() {
        return unreachableForChanges
                ? machine.dir.resolve("nowhere.git").toString()
                : repo.toString();
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
                    machine.forge,
                    URI.create("https://forge.example.org"),
                    signing ->
                            new GitTreeWrites(
                                    machine.runner,
                                    whereChangesGo(),
                                    "acme",
                                    FetchAccess.NONE,
                                    machine.forge,
                                    signing));
        };
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
