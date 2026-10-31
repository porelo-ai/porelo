package org.fuin.porelo.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Guards against a registry path accepted from a signer the per-path rule does not allow. */
class RegistryHistoryTest {
    @TempDir Path dir;
    private Path repo;
    private final ProcessCommandRunner runner = new ProcessCommandRunner();
    private final Map<String, Path> keyFiles = new HashMap<>();
    private final Map<String, SigningKey> keys = new HashMap<>();
    private final Forge forge = new Forge();

    /** A forge that answers what a test sets, and "could not be asked" otherwise. */
    private static final class Forge implements ForgeDirectory {
        final Map<String, List<String>> keys = new HashMap<>();
        final Map<String, Boolean> owners = new HashMap<>();
        final List<String> asked = new ArrayList<>();

        /** The logins whose list came only in part, as when GitHub's signing keys did not. */
        final Set<String> partial = new HashSet<>();

        /**
         * The logins the forge cannot answer for the first time they are asked, as when limited.
         */
        final Set<String> silentOnce = new HashSet<>();

        @Override
        public Optional<Keys> keys(String login) {
            asked.add("keys " + login);
            if (silentOnce.remove(login)) {
                return Optional.empty();
            }
            return Optional.ofNullable(keys.get(login))
                    .map(listed -> new Keys(listed, !partial.contains(login)));
        }

        @Override
        public Optional<Boolean> owns(String organisation, String login) {
            asked.add("owns " + organisation + " " + login);
            return Optional.ofNullable(owners.get(login));
        }
    }

    @BeforeEach
    void registry() throws Exception {
        repo = Files.createDirectory(dir.resolve("registry"));
        for (String login : List.of("michael", "alice", "alice-new", "bob", "carol", "hank")) {
            final Path file = dir.resolve(login);
            runner.runOrFail(
                    Command.of(
                            "ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", file.toString()));
            keyFiles.put(login, file);
            keys.put(
                    login,
                    new SigningKey(
                            Base64.getDecoder()
                                    .decode(OpenSshPrivateKey.seedBase64(Files.readString(file))),
                            "test"));
        }
        git("init", "-q", "-b", "main");
        git("config", "user.name", "Registry");
        git("config", "user.email", "registry@example.org");
        git("config", "gpg.format", "ssh");
    }

    private String git(String... args) {
        final List<String> all = new ArrayList<>(List.of("git"));
        all.addAll(List.of(args));
        return runner.runOrFail(
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

    private void write(String path, String text) throws IOException {
        final Path file = repo.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    /** Commits everything, signed by this login's key, or unsigned for {@code null}. */
    private String commit(@Nullable String signer) {
        git("add", "-A");
        if (signer == null) {
            git("commit", "-q", "--allow-empty", "-m", "registry change");
        } else {
            git(
                    "-c",
                    "user.signingkey=" + keyFiles.get(signer),
                    "commit",
                    "-q",
                    "--allow-empty",
                    "-S",
                    "-m",
                    "registry change");
        }
        return git("rev-parse", "HEAD");
    }

    private String key(String login) {
        return PersonCard.keyLine(keys.get(login));
    }

    private String pin(String login) {
        return PersonCard.fingerprint(key(login));
    }

    private String owners(String hostAdmin) {
        return "owners:\n  - login: michael\n    key: \""
                + key("michael")
                + "\"\nhost_admins:\n  walk6m:\n    - login: "
                + hostAdmin.replace("-new", "")
                + "\n      key: \""
                + key(hostAdmin)
                + "\"\n";
    }

    /** The owners file, accepted as trusted since the forge knows michael as owner. */
    private String founded(String hostAdmin) throws IOException {
        forge.owners.put("michael", true);
        forge.keys.put("michael", List.of(key("michael") + " laptop"));
        write("owners.yml", owners(hostAdmin));
        return commit("michael");
    }

    private String card(String login, String own, @Nullable String predecessor, String name) {
        return PersonCard.write(
                login,
                name,
                "",
                keys.get(own),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2030, 1, 1),
                predecessor == null ? null : keys.get(predecessor));
    }

    private String hostCard(String signer) {
        return HostCard.write(
                "walk6m",
                "walk6m.example.org",
                2201,
                key("hank"),
                3000,
                2222,
                8448,
                keys.get(signer));
    }

    private String access(String project) {
        return "admins:\n  alice: \""
                + pin("alice")
                + "\"\nprojects:\n  - name: "
                + project
                + "\n    admin: alice\n    access_repository: ssh://git@example.org/"
                + project
                + ".git\n";
    }

    private RegistryState read(@Nullable RegistryState trusted) throws IOException {
        final RegistryState state = new RegistryHistory(runner).read(repo, trusted, forge, "acme");
        // Whatever a walk or a settling leaves, the next read must take back as it was: a record
        // the read refuses stops every later read and filing.
        if (state.commit() != null) {
            assertThat(RegistryState.fromJson(state.toJson()))
                    .as("the trust record round-trips")
                    .isEqualTo(state);
        }
        return state;
    }

    /**
     * Asserts a read discarded its trust record and walked afresh: the answer a fresh read gives,
     * after one warning naming why.
     */
    private void readAfresh(RegistryState state, String why) throws IOException {
        final RegistryState fresh = read(null);
        assertThat(state)
                .as("the answer of a fresh read")
                .usingRecursiveComparison()
                .ignoringFields("warnings")
                .isEqualTo(fresh);
        assertThat(state.warnings().getFirst())
                .startsWith(
                        "the registry trust record was discarded and the registry read afresh from"
                                + " its first commit: ")
                .contains(why);
        assertThat(state.warnings().subList(1, state.warnings().size()))
                .isEqualTo(fresh.warnings());
    }

    private RegistryState.Person person(RegistryState state, String login) {
        return state.people().stream()
                .filter(person -> person.login().equals(login))
                .findFirst()
                .orElseThrow();
    }

    /** A first owners file the forge confirms is trusted, and the read reaches its commit. */
    @Test
    void the_first_owners_file_is_trusted_when_the_forge_lists_its_signer_as_owner_with_that_key()
            throws Exception {
        final String head = founded("hank");
        final RegistryState state = read(null);
        assertThat(state.owners())
                .as("the owner the forge confirmed")
                .singleElement()
                .satisfies(
                        owner -> {
                            assertThat(owner.login()).isEqualTo("michael");
                            assertThat(owner.fingerprint()).isEqualTo(pin("michael"));
                        });
        assertThat(state.hostAdmins().get("walk6m"))
                .as("host admins come from the accepted owners file")
                .extracting(RegistryState.Owner::login)
                .containsExactly("hank");
        assertThat(state.warnings()).as("nothing to warn about").isEmpty();
        assertThat(state.revision()).as("an accepted commit is the revision").isEqualTo(head);
        assertThat(forge.asked)
                .as("the forge is asked about the founder")
                .contains("owns acme michael");
    }

    /** Anybody with write access could otherwise found the registry's ownership. */
    @Test
    void the_first_owners_file_signed_by_somebody_not_owning_the_organisation_is_refused()
            throws Exception {
        forge.owners.put("michael", false);
        forge.keys.put("michael", List.of(key("michael")));
        write("owners.yml", owners("hank"));
        final String head = commit("michael");
        final RegistryState state = read(null);
        assertThat(state.owners()).as("no owner is taken from a refused file").isEmpty();
        assertThat(state.warnings())
                .as("commit and path are named")
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .startsWith(head + " owners.yml: ")
                .contains("not an owner of the forge organisation acme");
        assertThat(state.lastInForce())
                .as("the refusal is kept for the answer")
                .isEqualTo(
                        new RegistryState.Refusal(
                                head,
                                "owners.yml",
                                "michael is not an owner of the forge organisation acme"));
        assertThat(state.revision()).as("no commit was accepted").isNull();
    }

    /**
     * Without a token or offline, a writer who owns nothing could otherwise found the registry, and
     * a later read with a token would never look at that founding again.
     */
    @Test
    void
            the_first_owners_file_the_forge_cannot_decide_stops_the_read_before_it_and_is_judged_again()
                    throws Exception {
        write("owners.yml", owners("hank"));
        final String root = commit("michael");
        final RegistryState first = read(null);
        assertThat(first.owners()).as("no owner without a plain yes").isEmpty();
        assertThat(first.commit()).as("nothing before the first commit to keep").isNull();
        assertThat(first.revision()).isNull();
        assertThat(first.lastInForce()).as("undecided is no refusal").isNull();
        assertThat(first.warnings())
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .startsWith(root + " owners.yml: undecided")
                .contains("read again with a forge token");

        // The same founding on a line that has a commit before it.
        git("checkout", "-q", "--orphan", "other");
        git("rm", "-q", "-r", "--cached", ".");
        Files.delete(repo.resolve("owners.yml"));
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        final String before = commit("alice");
        write("owners.yml", owners("hank"));
        final String founding = commit("michael");
        write("people/bob.card", card("bob", "bob", null, "Bob"));
        commit("bob");
        final RegistryState stopped = read(null);
        assertThat(stopped.commit()).as("the state ends at the commit before").isEqualTo(before);
        assertThat(stopped.people())
                .extracting(RegistryState.Person::login)
                .as("nothing after it is walked")
                .containsExactly("alice");
        assertThat(stopped.warnings())
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .startsWith(founding + " owners.yml: undecided");

        forge.owners.put("michael", true);
        forge.keys.put("michael", List.of(key("michael")));
        final RegistryState again = read(RegistryState.fromJson(stopped.toJson()));
        assertThat(again.owners())
                .extracting(RegistryState.Owner::login)
                .as("the next read judges the founding again")
                .containsExactly("michael");
        assertThat(again.people()).extracting(RegistryState.Person::login).contains("bob");
        assertThat(again.warnings()).isEmpty();
    }

    /** A key list the forge gave only in part cannot confirm a founder whose key it lacks. */
    @Test
    void the_first_owners_file_is_undecided_when_the_forge_lists_keys_only_in_part()
            throws Exception {
        forge.owners.put("michael", true);
        forge.keys.put("michael", List.of(key("bob")));
        forge.partial.add("michael");
        write("owners.yml", owners("hank"));
        final String head = commit("michael");
        final RegistryState state = read(null);
        assertThat(state.owners()).isEmpty();
        assertThat(state.lastInForce()).isNull();
        assertThat(state.warnings())
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .startsWith(head + " owners.yml: undecided");
    }

    /** An owner of the organisation founds the registry only with a key the forge lists. */
    @Test
    void
            the_first_owners_file_signed_with_a_key_the_forge_does_not_list_for_the_founder_is_refused()
                    throws Exception {
        forge.owners.put("michael", true);
        forge.keys.put("michael", List.of(key("bob")));
        write("owners.yml", owners("hank"));
        final String head = commit("michael");
        final RegistryState state = read(null);
        assertThat(state.owners()).isEmpty();
        assertThat(state.lastInForce())
                .isEqualTo(
                        new RegistryState.Refusal(
                                head,
                                "owners.yml",
                                "the forge lists no key " + pin("michael") + " for michael"));
    }

    /** Without its owners file nobody owns the registry, and the next one would found it anew. */
    @Test
    void an_accepted_owners_file_cannot_be_removed_even_by_an_owner() throws Exception {
        founded("hank");
        Files.delete(repo.resolve("owners.yml"));
        final String removal = commit("michael");
        forge.owners.put("bob", true);
        forge.keys.put("bob", List.of(key("bob")));
        write(
                "owners.yml",
                owners("hank").replace("michael", "bob").replace(key("michael"), key("bob")));
        final String seizure = commit("bob");
        final RegistryState state = read(null);
        assertThat(state.owners())
                .extracting(RegistryState.Owner::login)
                .as("the last good owners")
                .containsExactly("michael");
        assertThat(state.warnings())
                .containsExactly(
                        removal + " owners.yml: owners.yml cannot be removed once accepted",
                        seizure
                                + " owners.yml: signed by "
                                + pin("bob")
                                + ", not a registry owner");
    }

    /** Removing a file that was never accepted must not make the registry look accepted. */
    @Test
    void removing_a_path_never_accepted_changes_nothing_and_accepts_nothing() throws Exception {
        forge.owners.put("michael", false);
        forge.keys.put("michael", List.of(key("michael")));
        write("owners.yml", owners("hank"));
        final String refused = commit("michael");
        Files.delete(repo.resolve("owners.yml"));
        commit("bob");
        final RegistryState state = read(null);
        assertThat(state.revision()).as("no commit was accepted").isNull();
        assertThat(state.lastInForce())
                .as("the refusal stays in force")
                .extracting(RegistryState.Refusal::commit)
                .isEqualTo(refused);
    }

    /** A forge owner's signature must not found a file naming somebody else as owner. */
    @Test
    void the_first_owners_file_must_be_signed_by_an_owner_it_names() throws Exception {
        forge.owners.put("alice", true);
        forge.keys.put("alice", List.of(key("alice")));
        forge.owners.put("michael", true);
        forge.keys.put("michael", List.of(key("michael")));
        write("owners.yml", owners("hank"));
        commit("alice");
        final RegistryState state = read(null);
        assertThat(state.owners()).as("signer named in no entry").isEmpty();
        assertThat(state.warnings())
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("not by an owner it names");
    }

    /** An owners file changed by somebody else than an owner it named keeps the old owners. */
    @Test
    void an_owners_file_changed_by_a_non_owner_keeps_the_last_good_owners() throws Exception {
        founded("hank");
        write(
                "owners.yml",
                owners("hank").replace("michael", "bob").replace(key("michael"), key("bob")));
        final String change = commit("bob");
        final RegistryState state = read(null);
        assertThat(state.owners())
                .extracting(RegistryState.Owner::login)
                .containsExactly("michael");
        assertThat(state.warnings())
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .startsWith(change + " owners.yml: signed by " + pin("bob"));
    }

    /**
     * The owners file with carol's key as michael's backup and bob's as hank's, michael and hank
     * holding the keys named.
     */
    private String backedOwners(String michael, String hank) {
        return "owners:\n  - login: michael\n    key: \""
                + key(michael)
                + "\"\n  - login: michael\n    key: \""
                + key("carol")
                + "\"\n    backup: true\nhost_admins:\n  walk6m:\n    - login: hank\n      key: \""
                + key(hank)
                + "\"\n    - login: hank\n      key: \""
                + key("bob")
                + "\"\n      backup: true\n";
    }

    /** A backup key replaces the key it backs, and changes nothing else. */
    @Test
    void a_backup_key_changes_only_the_entry_it_backs() throws Exception {
        forge.owners.put("michael", true);
        forge.keys.put("michael", List.of(key("michael")));
        write("owners.yml", backedOwners("michael", "hank"));
        commit("michael");
        write("owners.yml", backedOwners("alice-new", "hank"));
        commit("carol");
        write(
                "owners.yml",
                backedOwners("alice-new", "hank")
                        .replace(
                                "host_admins:",
                                "  - login: bob\n    key: \"" + key("bob") + "\"\nhost_admins:"));
        final String more = commit("carol");
        write("owners.yml", backedOwners("alice-new", "alice"));
        commit("bob");
        write("owners.yml", backedOwners("michael", "alice"));
        final String other = commit("bob");

        final RegistryState state = read(null);
        assertThat(state.owners())
                .extracting(RegistryState.Owner::fingerprint)
                .as("michael's key replaced by his backup key, nobody added")
                .containsExactly(pin("alice-new"), pin("carol"));
        assertThat(state.hostAdmins().get("walk6m"))
                .extracting(RegistryState.Owner::fingerprint)
                .as("hank's key replaced by his backup key for the host")
                .containsExactly(pin("alice"), pin("bob"));
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        commit("alice");
        assertThat(read(state).owners())
                .extracting(RegistryState.Owner::fingerprint)
                .as("a read from the trust record keeps a login's key and its backup key apart")
                .containsExactly(pin("alice-new"), pin("carol"));
        assertThat(state.warnings())
                .containsExactly(
                        more
                                + " owners.yml: signed by "
                                + pin("carol")
                                + ", a backup key, which may change only the entries it backs",
                        other
                                + " owners.yml: signed by "
                                + pin("bob")
                                + ", a backup key, which may change only the entries it backs");
    }

    /**
     * A backup key may only put one new key, not revoked, in place of the key it backs: it must not
     * make itself the owner, remove the owner it backs, put another backup key in its place or
     * bring back a revoked key.
     */
    @Test
    void a_backup_key_neither_promotes_itself_nor_removes_swaps_or_brings_back_a_revoked_key()
            throws Exception {
        forge.owners.put("michael", true);
        forge.keys.put("michael", List.of(key("michael")));
        forge.keys.put("alice", List.of(key("alice")));
        final String owners = backedOwners("michael", "hank");
        final String hank = "  - login: hank\n    key: \"" + key("hank") + "\"\n";
        final String rest = hank + owners.substring(owners.indexOf("host_admins:"));
        write("owners.yml", owners.replace("host_admins:", hank + "host_admins:"));
        commit("michael");
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        commit("alice");
        write("people/alice.card", card("alice", "alice-new", null, "Alice"));
        commit("michael");
        forge.keys.put("alice", List.of(key("alice"), key("alice-new")));
        final String base = git("rev-parse", "HEAD");
        final Map<String, String> michaels =
                Map.of(
                        "promotes itself",
                        "  - login: michael\n    key: \"" + key("carol") + "\"\n",
                        "removes the owner it backs",
                        "",
                        "swaps the backup key",
                        "  - login: michael\n    key: \""
                                + key("michael")
                                + "\"\n  - login: michael\n    key: \""
                                + key("alice-new")
                                + "\"\n    backup: true\n",
                        "brings back a revoked key",
                        "  - login: michael\n    key: \""
                                + key("alice")
                                + "\"\n  - login: michael\n    key: \""
                                + key("carol")
                                + "\"\n    backup: true\n");
        for (Map.Entry<String, String> attempt : michaels.entrySet()) {
            git("reset", "-q", "--hard", base);
            write("owners.yml", "owners:\n" + attempt.getValue() + rest);
            final String forged = commit("carol");

            final RegistryState state = read(null);

            assertThat(state.revoked()).containsExactly(pin("alice"));
            assertThat(state.owners())
                    .extracting(RegistryState.Owner::fingerprint, RegistryState.Owner::backup)
                    .as("a backup key that %s changes nothing", attempt.getKey())
                    .containsExactly(
                            org.assertj.core.api.Assertions.tuple(pin("michael"), false),
                            org.assertj.core.api.Assertions.tuple(pin("carol"), true),
                            org.assertj.core.api.Assertions.tuple(pin("hank"), false));
            assertThat(state.warnings())
                    .as(attempt.getKey())
                    .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .startsWith(forged + " owners.yml: signed by " + pin("carol"));
        }
        git("reset", "-q", "--hard", base);
        write(
                "owners.yml",
                owners.substring(0, owners.indexOf("host_admins:"))
                        + hank
                        + "host_admins:\n  walk6m:\n    - login: hank\n      key: \""
                        + key("bob")
                        + "\"\n");
        commit("bob");
        assertThat(read(null).hostAdmins().get("walk6m"))
                .extracting(RegistryState.Owner::fingerprint, RegistryState.Owner::backup)
                .as("a host admin's backup key does not make itself the host's admin")
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple(pin("hank"), false),
                        org.assertj.core.api.Assertions.tuple(pin("bob"), true));
    }

    /** A backup key is kept offline for one entry; it signs no host's files and founds nothing. */
    @Test
    void a_backup_key_signs_no_host_file_and_founds_no_registry() throws Exception {
        forge.owners.put("michael", true);
        forge.keys.put("michael", List.of(key("michael"), key("carol")));
        write("owners.yml", backedOwners("michael", "hank"));
        final String founding = commit("carol");
        assertThat(read(null).warnings())
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .startsWith(
                        founding
                                + " owners.yml: the first owners.yml is signed by "
                                + pin("carol"));

        // The same file again, a byte apart, so the owner's commit founds the registry.
        write("owners.yml", backedOwners("michael", "hank") + "\n");
        commit("michael");
        write("hosts/walk6m/card", hostCard("hank"));
        write("hosts/walk6m/access.yml", access("demo"));
        commit("hank");
        write("hosts/walk6m/access.yml", access("other"));
        final String forged = commit("bob");
        final RegistryState state = read(null);
        assertThat(state.hosts().getFirst().projects())
                .extracting(RegistryState.Project::name)
                .containsExactly("demo");
        assertThat(state.warnings())
                .last(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .startsWith(
                        forged
                                + " hosts/walk6m/access.yml: signed by "
                                + pin("bob")
                                + ", not a host admin");
    }

    /** A card is trusted only when the forge lists its key for its login. */
    @Test
    void a_card_is_trusted_refused_or_first_seen_by_the_keys_the_forge_lists() throws Exception {
        founded("hank");
        forge.keys.put("alice", List.of("# a comment", key("alice") + " alice@laptop"));
        forge.keys.put("bob", List.of(key("carol")));
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        commit("alice");
        write("people/bob.card", card("bob", "bob", null, "Bob"));
        commit("bob");
        write("people/carol.card", card("carol", "carol", null, "Carol"));
        commit("carol");
        final RegistryState state = read(null);
        assertThat(person(state, "alice").state()).as("listed").isEqualTo(RegistryState.TRUSTED);
        assertThat(person(state, "bob").state()).as("not listed").isEqualTo(RegistryState.REFUSED);
        assertThat(person(state, "bob").why())
                .as("refused with why")
                .contains("the forge lists other keys for bob");
        assertThat(person(state, "carol").state())
                .as("not yet checked")
                .isEqualTo(RegistryState.FIRST_SEEN);
        assertThat(person(state, "alice").why()).isEmpty();
    }

    /** A card changed by another key than its own keeps the last good state. */
    @Test
    void a_card_changed_by_another_key_keeps_the_last_good_state_and_names_commit_and_path()
            throws Exception {
        founded("hank");
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        commit("alice");
        write("people/alice.card", card("alice", "alice", null, "Mallory"));
        final String forged = commit("bob");
        final RegistryState state = read(null);
        assertThat(person(state, "alice").name()).as("the last good card").isEqualTo("Alice");
        assertThat(state.warnings())
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .startsWith(forged + " people/alice.card: signed by " + pin("bob"));
        assertThat(state.lastInForce())
                .extracting(RegistryState.Refusal::commit, RegistryState.Refusal::path)
                .containsExactly(forged, "people/alice.card");
    }

    /** Only the card's own key or a registry owner may withdraw a person. */
    @Test
    void a_card_is_removed_only_by_its_own_key_or_a_registry_owner() throws Exception {
        founded("hank");
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        commit("alice");
        Files.delete(repo.resolve("people/alice.card"));
        final String forged = commit("bob");
        final RegistryState refused = read(null);
        assertThat(refused.people()).as("bob cannot remove alice").hasSize(1);
        assertThat(refused.lastInForce())
                .extracting(RegistryState.Refusal::commit)
                .isEqualTo(forged);
        git("reset", "-q", "--hard", "HEAD~1");
        Files.delete(repo.resolve("people/alice.card"));
        commit("michael");
        final RegistryState state = read(null);
        assertThat(state.people()).as("an owner can").isEmpty();
        assertThat(state.warnings()).isEmpty();
    }

    /** A change of key handed on by the old key keeps the person's trust. */
    @Test
    void a_change_of_key_signed_by_the_new_key_and_handed_on_by_the_old_is_accepted()
            throws Exception {
        founded("hank");
        forge.keys.put("alice", List.of(key("alice")));
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        commit("alice");
        write("people/alice.card", card("alice", "alice-new", "alice", "Alice"));
        commit("alice-new");
        final RegistryState state = read(null);
        assertThat(person(state, "alice").fingerprint()).isEqualTo(pin("alice-new"));
        assertThat(person(state, "alice").state())
                .as("the state carries over")
                .isEqualTo(RegistryState.TRUSTED);
        assertThat(state.revoked()).as("a handed-on key is not revoked").isEmpty();
        assertThat(state.warnings()).isEmpty();
    }

    /** A lost key's card is replaced only through a registry owner, and the old key is revoked. */
    @Test
    void a_card_replacing_a_lost_key_is_accepted_signed_by_an_owner_and_refused_by_anybody_else()
            throws Exception {
        founded("hank");
        forge.keys.put("alice", List.of(key("alice")));
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        final String accepted = commit("alice");
        for (String stranger : List.of("alice-new", "bob")) {
            git("reset", "-q", "--hard", accepted);
            write("people/alice.card", card("alice", "alice-new", null, "Alice"));
            final String replacement = commit(stranger);
            final RegistryState state = read(null);
            assertThat(person(state, "alice").fingerprint())
                    .as("%s cannot take the login without the old key", stranger)
                    .isEqualTo(pin("alice"));
            assertThat(state.lastInForce())
                    .extracting(RegistryState.Refusal::commit, RegistryState.Refusal::path)
                    .containsExactly(replacement, "people/alice.card");
            assertThat(state.revoked()).isEmpty();
        }
        git("reset", "-q", "--hard", accepted);
        write("people/alice.card", card("alice", "alice-new", null, "Alice"));
        commit("michael");
        // The replacement is judged against the forge afresh, which lists the new key.
        forge.keys.put("alice", List.of(key("alice"), key("alice-new")));
        final RegistryState state = read(null);
        assertThat(person(state, "alice").fingerprint())
                .as("an owner replaces it")
                .isEqualTo(pin("alice-new"));
        assertThat(state.revoked()).as("the old key is revoked").containsExactly(pin("alice"));
        assertThat(state.warnings()).isEmpty();
    }

    /** A revoked key's later signature must not change anything it could change before. */
    @Test
    void a_revoked_keys_later_commit_is_refused() throws Exception {
        founded("alice");
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        write("hosts/walk6m/access.yml", access("demo"));
        commit("alice");
        write("people/alice.card", card("alice", "alice-new", null, "Alice"));
        commit("michael");
        write("hosts/walk6m/access.yml", access("other"));
        final String late = commit("alice");
        final RegistryState state = read(null);
        assertThat(state.hosts().getFirst().projects())
                .extracting(RegistryState.Project::name)
                .as("the host admin's revoked key no longer changes the host")
                .containsExactly("demo");
        assertThat(state.warnings())
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .isEqualTo(
                        late
                                + " hosts/walk6m/access.yml: commit signed by "
                                + pin("alice")
                                + ", which is revoked");
    }

    /** A host's access file changed by another key than its host admin's keeps the old one. */
    @Test
    void a_host_access_file_changed_by_another_key_than_a_host_admin_keeps_the_last_good_state()
            throws Exception {
        founded("hank");
        write("hosts/walk6m/card", hostCard("hank"));
        write("hosts/walk6m/access.yml", access("demo"));
        commit("hank");
        write("hosts/walk6m/access.yml", access("other"));
        final String forged = commit("michael");
        final RegistryState state = read(null);
        final RegistryState.Host host = state.hosts().getFirst();
        assertThat(host.projects()).extracting(RegistryState.Project::name).containsExactly("demo");
        assertThat(host.admins())
                .as("project admins by login and fingerprint")
                .containsExactly(new RegistryState.Admin("alice", pin("alice")));
        assertThat(host.card()).as("the card is accepted").isNotNull();
        assertThat(host.card().answer("walk6m"))
                .as("the card answered as the verifier answers it")
                .containsEntry("signed_by", pin("hank"))
                .containsEntry("ssh_port", 2201);
        assertThat(state.warnings())
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .startsWith(
                        forged
                                + " hosts/walk6m/access.yml: signed by "
                                + pin("michael")
                                + ", not a host admin");
    }

    /** A host card is checked as a host card: signed by the host admin who commits it. */
    @Test
    void a_host_card_signed_by_another_key_than_its_committer_is_refused() throws Exception {
        founded("hank");
        write("hosts/walk6m/card", hostCard("bob"));
        final String forged = commit("hank");
        final RegistryState state = read(null);
        assertThat(state.hosts()).as("no host from a refused card").isEmpty();
        assertThat(state.warnings())
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .startsWith(forged + " hosts/walk6m/card: the host card of walk6m is signed by");
    }

    /** A commit cannot grant itself the right it uses: each path is judged by the state before. */
    @Test
    void a_commit_is_judged_against_the_state_before_it() throws Exception {
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        commit("alice");
        forge.owners.put("michael", true);
        forge.keys.put("michael", List.of(key("michael")));
        write("owners.yml", owners("hank"));
        Files.delete(repo.resolve("people/alice.card"));
        final String both = commit("michael");
        final RegistryState state = read(null);
        assertThat(state.owners())
                .extracting(RegistryState.Owner::login)
                .as("the founding owners file is accepted")
                .containsExactly("michael");
        assertThat(state.people())
                .extracting(RegistryState.Person::login)
                .as("but its owner was none yet for the card removed beside it")
                .containsExactly("alice");
        assertThat(state.warnings())
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .startsWith(both + " people/alice.card: removed by " + pin("michael"));
    }

    /** A file outside the rule is never read, so a malformed one cannot change anything. */
    @Test
    void another_path_is_never_read() throws Exception {
        founded("hank");
        final RegistryState before = read(null);
        write("other.yml", "{{{ not yaml");
        write("people/README.md", "not a card");
        write("people/Alice.card", "not a card");
        write("hosts/walk6m/notes", "free text");
        write("hosts/Walk/card", "not a card");
        commit(null);
        final RegistryState state = read(null);
        assertThat(state.warnings()).as("nothing judged, nothing refused").isEmpty();
        assertThat(state.blobs()).isEqualTo(before.blobs());
        assertThat(state.people()).isEmpty();
        assertThat(state.hosts()).isEmpty();
    }

    /** One refused commit must not stop the walk or stay in force once its path is fixed. */
    @Test
    void a_refused_commit_keeps_the_last_good_state_and_the_walk_goes_on() throws Exception {
        founded("hank");
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        final String unsigned = commit(null);
        write("hosts/walk6m/access.yml", access("demo"));
        commit("hank");
        final RegistryState refused = read(null);
        assertThat(refused.people()).as("an unsigned card is refused").isEmpty();
        assertThat(refused.hosts()).as("the walk went on past it").hasSize(1);
        assertThat(refused.lastInForce())
                .extracting(RegistryState.Refusal::commit, RegistryState.Refusal::why)
                .containsExactly(unsigned, "commit has no readable SSH signature");
        write("people/alice.card", card("alice", "alice", null, "Alice A."));
        commit("alice");
        final RegistryState fixed = read(null);
        assertThat(person(fixed, "alice").name()).isEqualTo("Alice A.");
        assertThat(fixed.lastInForce()).as("its path was accepted later").isNull();
        assertThat(fixed.warnings()).as("the read still reports it").hasSize(1);
    }

    /** A rewritten history must not move the trust onto commits the record never saw. */
    @Test
    void a_fork_of_the_trusted_commit_is_refused_naming_both() throws Exception {
        founded("hank");
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        final String trustedCommit = commit("alice");
        final RegistryState trusted = read(null);
        git("reset", "-q", "--hard", "HEAD~1");
        write("people/alice.card", card("alice", "alice", null, "Other"));
        final String fork = commit("alice");
        assertThatThrownBy(() -> read(trusted))
                .isInstanceOf(CardException.class)
                .hasMessageContaining(fork)
                .hasMessageContaining(trustedCommit);
    }

    /** A shallow history hides the commits that would have to be judged. */
    @Test
    void a_shallow_checkout_is_refused() throws Exception {
        founded("hank");
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        commit("alice");
        final Path shallow = dir.resolve("shallow");
        runner.runOrFail(
                Command.of(
                                "git",
                                "clone",
                                "-q",
                                "--depth",
                                "1",
                                "file://" + repo,
                                shallow.toString())
                        .withEnvironment(
                                Map.of(
                                        "GIT_CONFIG_GLOBAL",
                                        "/dev/null",
                                        "GIT_CONFIG_NOSYSTEM",
                                        "1")));
        assertThatThrownBy(() -> new RegistryHistory(runner).read(shallow, null, forge, "acme"))
                .isInstanceOf(CardException.class)
                .hasMessageContaining("shallow");
    }

    /** A checkout reached through a symlink could be swapped between the check and the clone. */
    @Test
    void a_checkout_behind_a_symlink_is_refused() throws Exception {
        founded("hank");
        final Path link = Files.createSymbolicLink(dir.resolve("link"), repo);
        assertThatThrownBy(() -> new RegistryHistory(runner).read(link, null, forge, "acme"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not a symlink");
    }

    /** A checkout's path reaches the terminal in a refusal; it may hold what moves the cursor. */
    @Test
    void a_checkout_path_is_named_without_terminal_controls() throws Exception {
        final Path hostile = dir.resolve("registry\u009b2J\u202eevil");
        assertThatThrownBy(() -> new RegistryHistory(runner).read(hostile, null, forge, "acme"))
                .isInstanceOf(IOException.class)
                .message()
                .contains("registry?2J?evil")
                .doesNotContain("\u009b")
                .doesNotContain("\u202e");
    }

    /** The owners file is anybody's to write; its text never reaches a terminal or a log. */
    @Test
    void an_owners_diagnostic_names_line_and_column_without_an_excerpt() throws Exception {
        forge.owners.put("michael", true);
        forge.keys.put("michael", List.of(key("michael")));
        write("owners.yml", owners("hank") + "secret-excerpt: \"\\e[31m\"\n");
        final String head = commit("michael");
        final RegistryState state = read(null);
        assertThat(state.warnings())
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .isEqualTo(
                        head
                                + " owners.yml: owners.yml, line 8, column 1: unknown or repeated field")
                .doesNotContain("secret-excerpt");
    }

    /**
     * A writer filing a card for a login before its owner does must not lock the owner out: a
     * refused card is answered, but the next card for the login is judged as a first one.
     */
    @Test
    void a_refused_first_card_is_no_card_and_the_logins_own_card_is_judged_afresh()
            throws Exception {
        founded("hank");
        forge.keys.put("alice", List.of(key("alice")));
        write("people/alice.card", card("alice", "bob", null, "Alice"));
        final String squat = commit("bob");
        final RegistryState squatted = read(null);
        assertThat(person(squatted, "alice").state())
                .as("answered as refused")
                .isEqualTo(RegistryState.REFUSED);
        assertThat(person(squatted, "alice").why()).contains("the forge lists other keys");
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        commit("alice");
        final RegistryState state = read(null);
        assertThat(person(state, "alice").fingerprint())
                .as("the login's own card replaces the squatter's")
                .isEqualTo(pin("alice"));
        assertThat(person(state, "alice").state()).isEqualTo(RegistryState.TRUSTED);
        assertThat(person(state, "alice").why()).isEmpty();
        assertThat(state.warnings())
                .as("only the squatter's card is warned about")
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .startsWith(squat + " people/alice.card: the forge lists other keys for alice");
    }

    /**
     * A card the forge refuses must not make the registry look accepted, nor hide an earlier
     * refusal of its path, nor pass without a word.
     */
    @Test
    void a_card_the_forge_refuses_accepts_nothing_keeps_the_refusals_and_is_warned_about()
            throws Exception {
        forge.keys.put("alice", List.of(key("alice")));
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        final String unsigned = commit(null);
        write("people/alice.card", card("alice", "bob", null, "Alice"));
        final String forged = commit("bob");
        final RegistryState state = read(null);
        assertThat(person(state, "alice").state()).isEqualTo(RegistryState.REFUSED);
        assertThat(state.revision()).as("no commit was accepted").isNull();
        assertThat(state.lastInForce())
                .as("the earlier refusal stays in force")
                .extracting(RegistryState.Refusal::commit)
                .isEqualTo(unsigned);
        assertThat(state.warnings())
                .last(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .startsWith(forged + " people/alice.card: the forge lists other keys for alice");
        assertThat(RegistryState.fromJson(state.toJson()))
                .as("such a record is still read back")
                .isEqualTo(state);
    }

    /**
     * A writer who files a card for a login while the forge cannot vouch for it - offline, or with
     * GitHub's rate limit spent by many cards in one commit - must not hold the login against its
     * owner, whose card carries a key the forge lists.
     */
    @Test
    void a_first_seen_card_gives_way_to_the_logins_own_card_with_a_key_the_forge_lists()
            throws Exception {
        founded("hank");
        forge.keys.put("alice", List.of(key("alice")));
        forge.partial.add("alice");
        write("people/alice.card", card("alice", "bob", null, "Mallory"));
        commit("bob");
        final RegistryState squatted = read(null);
        assertThat(person(squatted, "alice").state())
                .as("half a list leaves the squatter's card first-seen")
                .isEqualTo(RegistryState.FIRST_SEEN);
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        commit("alice");
        final RegistryState state = read(null);
        assertThat(person(state, "alice"))
                .extracting(
                        RegistryState.Person::fingerprint,
                        RegistryState.Person::state,
                        RegistryState.Person::name)
                .as("the login's own card replaces it, judged as a first card")
                .containsExactly(pin("alice"), RegistryState.TRUSTED, "Alice");
        assertThat(state.revoked()).as("nobody revoked a key").isEmpty();
        assertThat(state.warnings()).isEmpty();
    }

    /** An owner's replacement vouches for the login, not the key, which the forge must list. */
    @Test
    void an_owners_replacement_of_a_card_is_judged_against_the_forge_afresh() throws Exception {
        founded("hank");
        forge.keys.put("alice", List.of(key("alice")));
        write("people/alice.card", card("alice", "bob", null, "Alice"));
        commit("bob");
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        commit("michael");
        final RegistryState replaced = read(null);
        assertThat(person(replaced, "alice").state())
                .as("an owner puts the real card in place of a refused one; it is not refused")
                .isEqualTo(RegistryState.TRUSTED);
        assertThat(person(replaced, "alice").why()).isEmpty();
        write("people/alice.card", card("alice", "alice-new", null, "Alice"));
        commit("michael");
        final RegistryState unlisted = read(null);
        assertThat(person(unlisted, "alice").state())
                .as("a trusted state does not carry over to a key the forge does not list")
                .isEqualTo(RegistryState.REFUSED);
        assertThat(unlisted.revoked()).containsExactly(pin("alice"));
    }

    /**
     * A stranger who files a refused card for a login and removes it again must not make the
     * registry look accepted, nor clear an earlier refusal of the path.
     */
    @Test
    void removing_a_card_nobody_accepted_accepts_nothing_and_keeps_the_refusals() throws Exception {
        forge.keys.put("alice", List.of(key("alice")));
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        final String unsigned = commit(null);
        write("people/alice.card", card("alice", "bob", null, "Alice"));
        commit("bob");
        Files.delete(repo.resolve("people/alice.card"));
        commit("bob");
        final RegistryState state = read(null);
        assertThat(state.revision()).as("no commit was accepted").isNull();
        assertThat(state.people()).as("the refused answer is gone with its file").isEmpty();
        assertThat(state.lastInForce())
                .extracting(RegistryState.Refusal::commit)
                .isEqualTo(unsigned);
        assertThat(state.blobs()).doesNotContainKey("people/alice.card");
    }

    /**
     * A person who files their card before the forge lists its key has it refused; once the forge
     * lists it, the card must become theirs without filing it again.
     */
    @Test
    void a_refused_card_is_no_accepted_blob_and_becomes_trusted_once_the_forge_lists_its_key()
            throws Exception {
        founded("hank");
        forge.keys.put("alice", List.of(key("bob")));
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        commit("alice");
        final RegistryState refused = read(null);
        assertThat(person(refused, "alice").state()).isEqualTo(RegistryState.REFUSED);
        assertThat(refused.blobs())
                .as("a refused card is not accepted")
                .doesNotContainKey("people/alice.card");
        final RegistryState record = RegistryState.fromJson(refused.toJson());

        forge.keys.put("alice", List.of(key("bob"), key("alice")));
        final RegistryState listed = read(record);
        assertThat(person(listed, "alice").state()).isEqualTo(RegistryState.TRUSTED);
        assertThat(listed.blobs())
                .containsEntry("people/alice.card", person(refused, "alice").blob());
        assertThat(listed.warnings())
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .isEqualTo(
                        refused.commit()
                                + " people/alice.card: the forge lists the card's key now; it is"
                                + " trusted");
    }

    /**
     * Asserts a fresh read and a read from the record of an earlier one answer the same: revision,
     * people, blobs and whether the answer is firm.
     */
    private void agree(RegistryState incremental, RegistryState fresh) {
        assertThat(incremental)
                .as("the read from a record answers as a fresh read")
                .extracting(
                        RegistryState::revision,
                        RegistryState::people,
                        RegistryState::blobs,
                        RegistryState::firm)
                .containsExactly(fresh.revision(), fresh.people(), fresh.blobs(), fresh.firm());
    }

    /** Commits bob's first card and then its removal, signed by bob's own key. */
    private String bobAddedAndRemoved() throws IOException {
        write("people/bob.card", card("bob", "bob", null, "Bob"));
        commit("bob");
        Files.delete(repo.resolve("people/bob.card"));
        return commit("bob");
    }

    /**
     * Commits alice's first card, first-seen while the forge cannot answer once, and a change of
     * key the forge then refuses, or her first card refused at once.
     */
    private String aliceRefused(boolean atChangeOfKey) throws IOException {
        forge.keys.put("alice", List.of(key("carol")));
        if (atChangeOfKey) {
            forge.silentOnce.add("alice");
        }
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        commit("alice");
        write("people/alice.card", card("alice", "alice-new", "alice", "Alice"));
        return commit("alice-new");
    }

    /**
     * A removal of a first-seen card is accepted as far as that card is; whether a later card is
     * refused at once or at its change of key must not take that acceptance back differently.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void a_removal_of_a_first_seen_card_keeps_the_registry_accepted_however_a_later_card_is_refused(
            boolean atChangeOfKey) throws Exception {
        bobAddedAndRemoved();
        final String last = aliceRefused(atChangeOfKey);
        final RegistryState fresh = read(null);
        assertThat(person(fresh, "alice").state()).isEqualTo(RegistryState.REFUSED);
        assertThat(fresh.revision())
                .as("the removal is accepted, as bob's card was")
                .isEqualTo(last);
        assertThat(fresh.removed()).extracting(RegistryState.Removed::login).containsExactly("bob");
    }

    /**
     * A record kept while the forge could not judge a removed first-seen card must answer, once the
     * forge can, as a fresh read does: the removal stands when the forge lists the card's key, and
     * goes when it lists others.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void a_removed_first_seen_card_is_settled_as_a_fresh_read_judges_it(boolean listed)
            throws Exception {
        final String removal = bobAddedAndRemoved();
        final RegistryState offline = read(null);
        assertThat(offline.revision()).isEqualTo(removal);
        assertThat(offline.firm()).isFalse();
        agree(read(RegistryState.fromJson(offline.toJson())), read(null));
        // Accepted only because of the removal: a record dropping it would be accepted for nothing.
        final String dropped =
                offline.toJson().replaceFirst("\"removed\":\\[.*\\]\\}$", "\"removed\":[]}");
        assertThat(dropped).as("the damage applied").isNotEqualTo(offline.toJson());
        assertThatThrownBy(() -> RegistryState.fromJson(dropped))
                .isInstanceOf(CardException.class)
                .hasMessageContaining("accepted");

        forge.keys.put("bob", List.of(key(listed ? "bob" : "carol")));
        final String last = aliceRefused(true);
        final RegistryState incremental = read(RegistryState.fromJson(offline.toJson()));
        forge.silentOnce.add("alice");
        final RegistryState fresh = read(null);
        agree(incremental, fresh);
        assertThat(fresh.revision()).isEqualTo(listed ? last : null);
    }

    /**
     * A removal resting on a first-seen card is judged at the end of every read, fresh or from a
     * record: a fresh read whose later commit has the forge answer for the login must answer as a
     * read from its own record, and as one from a record kept while the forge was silent.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void a_removal_is_judged_alike_by_a_fresh_read_and_a_read_from_a_record(boolean listed)
            throws Exception {
        // The card filed again holds a key the forge does not list, so only the removal decides.
        forge.keys.put("bob", List.of(key(listed ? "bob" : "carol")));
        final String again = listed ? "carol" : "bob";
        bobAddedAndRemoved();
        // Kept while the forge could not be asked about bob at all.
        final List<String> listing = forge.keys.remove("bob");
        final RegistryState offline = read(null);
        assertThat(offline.removed())
                .extracting(RegistryState.Removed::login)
                .containsExactly("bob");
        forge.keys.put("bob", listing);
        write("people/bob.card", card("bob", again, null, "Bob"));
        final String last = commit(again);

        forge.silentOnce.add("bob");
        final RegistryState fresh = read(null);
        assertThat(person(fresh, "bob").state()).isEqualTo(RegistryState.REFUSED);
        assertThat(fresh.revision()).isEqualTo(listed ? last : null);
        assertThat(fresh.firm()).isEqualTo(listed);
        agree(read(RegistryState.fromJson(fresh.toJson())), fresh);
        agree(read(RegistryState.fromJson(offline.toJson())), fresh);
    }

    /**
     * A change of key from a first-seen card must be judged as settling judges its chain, the new
     * key first; copied from the old card instead, a fresh read and a read from its own record
     * would answer the same registry differently.
     */
    @Test
    void a_change_of_key_from_a_first_seen_card_is_judged_by_its_chain_as_settling_judges_it()
            throws Exception {
        // A list given only in part, holding the new key and not the old.
        forge.keys.put("alice", List.of(key("alice-new")));
        forge.partial.add("alice");
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        commit("alice");
        write("people/alice.card", card("alice", "alice-new", "alice", "Alice"));
        commit("alice-new");
        final RegistryState fresh = read(null);
        final RegistryState again = read(RegistryState.fromJson(fresh.toJson()));
        assertThat(person(fresh, "alice").state())
                .as("a fresh read answers as the read from its own record")
                .isEqualTo(person(again, "alice").state())
                .isEqualTo(RegistryState.TRUSTED);
    }

    /**
     * A first-seen card the forge refuses at its change of key no longer keeps the registry
     * accepted, as when settling refuses it.
     */
    @Test
    void a_first_seen_card_refused_at_its_change_of_key_keeps_nothing_accepted() throws Exception {
        forge.keys.put("alice", List.of(key("bob")));
        forge.silentOnce.add("alice");
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        commit("alice");
        write("people/alice.card", card("alice", "alice-new", "alice", "Alice"));
        commit("alice-new");
        final RegistryState fresh = read(null);
        assertThat(person(fresh, "alice").state()).isEqualTo(RegistryState.REFUSED);
        assertThat(fresh.blobs()).doesNotContainKey("people/alice.card");
        assertThat(fresh.revision()).as("no commit is accepted by a refused card alone").isNull();
    }

    /**
     * A change of key read while the forge could not be asked must be settled by the chain it was
     * accepted along: a forge that lists the old key vouches for the person, as a fresh read would.
     */
    @Test
    void a_first_seen_card_is_settled_by_any_key_of_its_accepted_chain() throws Exception {
        founded("hank");
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        commit("alice");
        write("people/alice.card", card("alice", "alice-new", "alice", "Alice"));
        commit("alice-new");
        write("people/carol.card", card("carol", "carol", null, "Carol"));
        commit("carol");
        final RegistryState offline = read(null);
        assertThat(person(offline, "alice").predecessors()).containsExactly(pin("alice"));
        final RegistryState record = RegistryState.fromJson(offline.toJson());

        forge.keys.put("alice", List.of(key("alice")));
        forge.keys.put("carol", List.of(key("bob")));
        final RegistryState settled = read(record);
        assertThat(person(settled, "alice").state())
                .as("the predecessor the forge lists vouches for the chain")
                .isEqualTo(RegistryState.TRUSTED);
        assertThat(person(settled, "carol").state()).isEqualTo(RegistryState.REFUSED);
        assertThat(settled.blobs()).doesNotContainKey("people/carol.card");
        assertThat(settled.warnings())
                .as("settling to refused is warned about")
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .startsWith(offline.commit() + " people/carol.card: the forge lists other keys");
    }

    /** Makes one more key, as the fixture's own are made. */
    private void newKey(String name) throws Exception {
        final Path file = dir.resolve(name);
        runner.runOrFail(
                Command.of("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", file.toString()));
        keyFiles.put(name, file);
        keys.put(
                name,
                new SigningKey(
                        Base64.getDecoder()
                                .decode(OpenSshPrivateKey.seedBase64(Files.readString(file))),
                        "test"));
    }

    /**
     * A revoked key is a lost or stolen one: it must not become trusted on another login's card,
     * neither when the forge later lists it nor through an owner's replacement.
     */
    @Test
    void a_revoked_key_is_never_trusted_on_any_card() throws Exception {
        founded("hank");
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        commit("alice");
        write("people/bob.card", card("bob", "alice", null, "Bob"));
        commit("alice");
        write("people/carol.card", card("carol", "carol", null, "Carol"));
        commit("carol");
        write("people/alice.card", card("alice", "alice-new", null, "Alice"));
        final String revocation = commit("michael");
        final RegistryState offline = read(null);
        assertThat(offline.revoked()).containsExactly(pin("alice"));
        assertThat(person(offline, "bob"))
                .extracting(RegistryState.Person::state, RegistryState.Person::why)
                .as("refused the moment its key is revoked")
                .containsExactly(RegistryState.REFUSED, "revoked key");
        assertThat(offline.warnings()).contains(revocation + " people/bob.card: revoked key");
        final RegistryState record = RegistryState.fromJson(offline.toJson());

        forge.keys.put("bob", List.of(key("alice")));
        forge.keys.put("carol", List.of(key("alice")));
        write("people/carol.card", card("carol", "alice", null, "Carol"));
        final String replaced = commit("michael");
        final RegistryState state = read(record);
        assertThat(person(state, "bob"))
                .extracting(RegistryState.Person::state, RegistryState.Person::why)
                .as("settled: the forge lists it, but it is revoked")
                .containsExactly(RegistryState.REFUSED, "revoked key");
        assertThat(person(state, "carol"))
                .extracting(RegistryState.Person::state, RegistryState.Person::why)
                .as("an owner's replacement with a revoked key")
                .containsExactly(RegistryState.REFUSED, "revoked key");
        assertThat(state.warnings()).contains(replaced + " people/carol.card: revoked key");
    }

    /**
     * A writer who hands a card back and forth between two keys, or along many, must not make every
     * later read ask the forge once per change and spend its rate limit.
     */
    @Test
    void a_cards_chain_holds_each_key_once_and_at_most_sixteen() throws Exception {
        founded("hank");
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        commit("alice");
        String from = "alice";
        for (int change = 0; change < 20; change++) {
            final String to = from.equals("alice") ? "alice-new" : "alice";
            write("people/alice.card", card("alice", to, from, "Alice"));
            commit(to);
            from = to;
        }
        final RegistryState swapped = read(null);
        assertThat(person(swapped, "alice").predecessors())
                .as("each key once, never the card's own")
                .containsExactly(pin("alice-new"));
        forge.asked.clear();
        read(RegistryState.fromJson(swapped.toJson()));
        assertThat(forge.asked).containsExactly("keys alice", "keys alice");

        git("reset", "-q", "--hard", "HEAD~20");
        from = "alice";
        String last = "";
        for (int step = 1; step <= RegistryHistory.CHAIN; step++) {
            final String to = "k" + step;
            newKey(to);
            write("people/alice.card", card("alice", to, from, "Alice"));
            last = commit(to);
            from = to;
        }
        final RegistryState state = read(null);
        assertThat(person(state, "alice").fingerprint())
                .as("the chain stops at sixteen keys")
                .isEqualTo(PersonCard.fingerprint(PersonCard.keyLine(keys.get("k15"))));
        assertThat(person(state, "alice").predecessors()).hasSize(RegistryHistory.CHAIN - 1);
        assertThat(state.lastInForce())
                .isEqualTo(
                        new RegistryState.Refusal(
                                last, "people/alice.card", "too many keys handed on"));
    }

    /**
     * A settled card must leave the answer as a walk would: its path's old refusal gone once it is
     * trusted, and no commit counted accepted once its only accepted card is refused.
     */
    @Test
    void settling_keeps_the_refusals_and_the_acceptance_as_a_walk_would() throws Exception {
        forge.keys.put("alice", List.of(key("bob")));
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        commit(null);
        write("people/alice.card", card("alice", "alice", null, "Alice A."));
        commit("alice");
        final RegistryState refused = read(null);
        assertThat(refused.lastInForce()).isNotNull();
        forge.keys.put("alice", List.of(key("alice")));
        final RegistryState trusted = read(RegistryState.fromJson(refused.toJson()));
        assertThat(trusted.lastInForce()).as("the path is accepted now").isNull();

        git("reset", "-q", "--hard", "HEAD~1");
        write("people/alice.card", card("alice", "alice", null, "Alice A."));
        commit("alice");
        forge.keys.remove("alice");
        final RegistryState offline = read(null);
        assertThat(offline.revision()).isNotNull();
        forge.keys.put("alice", List.of(key("bob")));
        final RegistryState settled = read(RegistryState.fromJson(offline.toJson()));
        assertThat(person(settled, "alice").state()).isEqualTo(RegistryState.REFUSED);
        assertThat(settled.revision()).as("nothing accepted remains").isNull();
    }

    /**
     * A first-seen squat and the login's own card, read while the forge cannot be asked: refusing
     * the own card would move the trust record past it for good, so the read stops before it.
     */
    @Test
    void the_logins_own_card_the_forge_cannot_judge_over_a_squat_stops_the_read() throws Exception {
        founded("hank");
        write("people/alice.card", card("alice", "bob", null, "Mallory"));
        final String squat = commit("bob");
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        final String own = commit("alice");
        final RegistryState stopped = read(null);
        assertThat(stopped.commit()).as("the state ends before the own card").isEqualTo(squat);
        assertThat(stopped.lastInForce()).as("undecided is no refusal").isNull();
        assertThat(stopped.warnings())
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .startsWith(own + " people/alice.card: undecided");

        forge.keys.put("alice", List.of(key("alice")));
        final RegistryState again = read(RegistryState.fromJson(stopped.toJson()));
        assertThat(person(again, "alice"))
                .extracting(RegistryState.Person::fingerprint, RegistryState.Person::state)
                .containsExactly(pin("alice"), RegistryState.TRUSTED);
        assertThat(again.commit()).isEqualTo(own);
    }

    /** A card the forge could not settle once must not stay first-seen for good. */
    @Test
    void first_seen_cards_of_the_trust_record_are_asked_again_and_a_plain_answer_replaces_them()
            throws Exception {
        founded("hank");
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        commit("alice");
        write("people/bob.card", card("bob", "bob", null, "Bob"));
        commit("bob");
        write("people/carol.card", card("carol", "carol", null, "Carol"));
        commit("carol");
        final RegistryState record = RegistryState.fromJson(read(null).toJson());
        assertThat(record.people())
                .extracting(RegistryState.Person::state)
                .containsOnly(RegistryState.FIRST_SEEN);
        forge.keys.put("alice", List.of(key("alice")));
        forge.keys.put("bob", List.of(key("carol")));
        forge.keys.put("carol", List.of(key("bob")));
        forge.partial.add("carol");
        final RegistryState next = read(record);
        assertThat(person(next, "alice").state()).isEqualTo(RegistryState.TRUSTED);
        assertThat(person(next, "bob").state()).isEqualTo(RegistryState.REFUSED);
        assertThat(person(next, "bob").why()).contains("the forge lists other keys for bob");
        assertThat(person(next, "carol").state())
                .as("a list in part settles nothing")
                .isEqualTo(RegistryState.FIRST_SEEN);
    }

    /** A rate limit spent by others leaves a key list in part; a key in it is still vouched for. */
    @Test
    void a_card_against_a_key_list_in_part_is_trusted_when_listed_and_first_seen_otherwise()
            throws Exception {
        founded("hank");
        forge.keys.put("alice", List.of(key("alice")));
        forge.keys.put("bob", List.of(key("carol")));
        forge.partial.addAll(List.of("alice", "bob"));
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        commit("alice");
        write("people/bob.card", card("bob", "bob", null, "Bob"));
        commit("bob");
        final RegistryState state = read(null);
        assertThat(person(state, "alice").state()).isEqualTo(RegistryState.TRUSTED);
        assertThat(person(state, "bob").state())
                .as("not refused on half a list")
                .isEqualTo(RegistryState.FIRST_SEEN);
    }

    /** A thief holding a revoked key must not hand another card on to a key of their own. */
    @Test
    void a_change_of_key_handed_on_by_a_revoked_key_is_refused() throws Exception {
        founded("hank");
        // The forge lists the thief's key too: only the revoked predecessor refuses the card.
        forge.keys.put("bob", List.of(key("alice"), key("carol")));
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        write("people/bob.card", card("bob", "alice", null, "Bob"));
        commit("alice");
        write("people/alice.card", card("alice", "alice-new", null, "Alice"));
        commit("michael");
        write("people/bob.card", card("bob", "carol", "alice", "Bob"));
        final String handed = commit("carol");
        final RegistryState state = read(null);
        assertThat(state.revoked()).containsExactly(pin("alice"));
        assertThat(person(state, "bob"))
                .extracting(RegistryState.Person::state, RegistryState.Person::why)
                .as("the revoked key hands nothing on")
                .containsExactly(RegistryState.REFUSED, "revoked key");
        assertThat(state.blobs()).doesNotContainKey("people/bob.card");
        assertThat(state.warnings()).contains(handed + " people/bob.card: revoked key");
        assertThat(person(state, "bob"))
                .extracting(RegistryState.Person::predecessors, RegistryState.Person::predecessor)
                .as("named, but never a key bob held")
                .containsExactly(List.of(), pin("alice"));
        // The forge listing the thief's key must not settle the card at a later read.
        final RegistryState again = read(RegistryState.fromJson(state.toJson()));
        assertThat(person(again, "bob"))
                .extracting(RegistryState.Person::state, RegistryState.Person::why)
                .as("refused at every later read")
                .containsExactly(RegistryState.REFUSED, "revoked key");
        assertThat(again.blobs()).doesNotContainKey("people/bob.card");
    }

    /**
     * A first card naming a predecessor that is revoked later must lose its trust, as a card of the
     * revoked key itself does; kept, the thief who holds that key still vouches for it.
     */
    @Test
    void a_first_card_naming_a_key_is_trusted_only_until_that_key_is_revoked() throws Exception {
        founded("hank");
        forge.keys.put("alice", List.of(key("alice")));
        forge.keys.put("bob", List.of(key("carol")));
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        commit("alice");
        write("people/bob.card", card("bob", "carol", "alice", "Bob"));
        commit("carol");
        final RegistryState before = read(null);
        assertThat(person(before, "bob").state()).isEqualTo(RegistryState.TRUSTED);

        write("people/alice.card", card("alice", "alice-new", null, "Alice"));
        final String revocation = commit("michael");
        final RegistryState state = read(null);
        assertThat(person(state, "bob"))
                .extracting(RegistryState.Person::state, RegistryState.Person::why)
                .containsExactly(RegistryState.REFUSED, "revoked key");
        assertThat(state.blobs()).doesNotContainKey("people/bob.card");
        assertThat(state.warnings()).contains(revocation + " people/bob.card: revoked key");
        assertThat(read(RegistryState.fromJson(before.toJson())))
                .as("a read from the record before ends as the read from scratch")
                .extracting(RegistryState::people, RegistryState::blobs)
                .containsExactly(state.people(), state.blobs());

        // A record keeping it trusted with its predecessor revoked is no record a walk leaves;
        // alice's own card is left out, so that only bob's could be what refuses it.
        final Map<String, String> blobs = new HashMap<>(before.blobs());
        blobs.remove("people/alice.card");
        final Map<String, RegistryState.Change> changes = new HashMap<>(before.changes());
        changes.remove("people/alice.card");
        final RegistryState forged =
                new RegistryState(
                        before.commit(),
                        before.accepted(),
                        before.firm(),
                        before.owners(),
                        before.hostAdmins(),
                        List.of(person(before, "bob")),
                        before.hosts(),
                        List.of(pin("alice")),
                        blobs,
                        changes,
                        List.of(),
                        before.refusals(),
                        List.of());
        assertThatThrownBy(() -> RegistryState.fromJson(forged.toJson()))
                .isInstanceOf(CardException.class)
                .hasMessageContaining("revoked");
    }

    /**
     * A record must hold a person's card only as the walk would take it: signed by its own key and
     * placed by that key or an owner. A writer's commit the walk refused must not come back in
     * through a record naming it.
     */
    @ParameterizedTest
    @ValueSource(strings = {"card-not-signed-by-its-key", "placed-by-another-writer"})
    void a_trust_record_holding_a_card_the_walk_refused_is_discarded(String damage)
            throws Exception {
        founded("hank");
        forge.keys.put("alice", List.of(key("alice")));
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        commit("alice");
        final String genuine = card("alice", "alice", null, "Alicia");
        // Signed by alice's key, but over another name than the card holds.
        final String unsigned = genuine.replace("name: \"Alicia\"", "name: \"Mallory\"");
        assertThat(unsigned).as("the damage applied").isNotEqualTo(genuine);
        final boolean placed = damage.equals("placed-by-another-writer");
        write("people/alice.card", placed ? genuine : unsigned);
        final String forgedAt = commit(placed ? "bob" : "alice");
        final RegistryState walked = read(null);
        assertThat(walked.refusals())
                .as("the walk refuses it")
                .anyMatch(refusal -> refusal.commit().equals(forgedAt));
        final RegistryState.Change change =
                new RegistryState.Change(forgedAt, pin(placed ? "bob" : "alice"));
        final String blob = git("rev-parse", forgedAt + ":people/alice.card");
        final RegistryState.Person alice = person(walked, "alice");
        final Map<String, String> blobs = new HashMap<>(walked.blobs());
        blobs.put("people/alice.card", blob);
        final Map<String, RegistryState.Change> changes = new HashMap<>(walked.changes());
        changes.put("people/alice.card", change);
        final RegistryState record =
                new RegistryState(
                        walked.commit(),
                        walked.accepted(),
                        walked.firm(),
                        walked.owners(),
                        walked.hostAdmins(),
                        List.of(
                                new RegistryState.Person(
                                        "alice",
                                        placed ? "Alicia" : "Mallory",
                                        "",
                                        alice.key(),
                                        alice.fingerprint(),
                                        RegistryState.TRUSTED,
                                        "",
                                        List.of(),
                                        "",
                                        blob,
                                        change)),
                        walked.hosts(),
                        walked.revoked(),
                        blobs,
                        changes,
                        List.of(),
                        List.of(),
                        List.of());
        RegistryState.fromJson(record.toJson());
        readAfresh(read(record), "does not match the registry's history");
    }

    /**
     * An owner's backup key places no person's card in the walk, so a record naming a card it
     * placed must not bring that card in either.
     */
    @Test
    void a_trust_record_holding_a_card_an_owners_backup_key_placed_is_discarded() throws Exception {
        forge.owners.put("michael", true);
        forge.keys.put("michael", List.of(key("michael")));
        forge.keys.put("alice", List.of(key("alice")));
        write("owners.yml", backedOwners("michael", "hank"));
        commit("michael");
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        commit("alice");
        write("people/alice.card", card("alice", "alice", null, "Alicia"));
        final String placedAt = commit("carol");
        final RegistryState walked = read(null);
        assertThat(walked.refusals())
                .as("the walk refuses a card michael's backup key placed")
                .anyMatch(refusal -> refusal.commit().equals(placedAt));
        final RegistryState.Change change = new RegistryState.Change(placedAt, pin("carol"));
        final String blob = git("rev-parse", placedAt + ":people/alice.card");
        final RegistryState.Person alice = person(walked, "alice");
        final Map<String, String> blobs = new HashMap<>(walked.blobs());
        blobs.put("people/alice.card", blob);
        final Map<String, RegistryState.Change> changes = new HashMap<>(walked.changes());
        changes.put("people/alice.card", change);
        final RegistryState record =
                new RegistryState(
                        walked.commit(),
                        walked.accepted(),
                        walked.firm(),
                        walked.owners(),
                        walked.hostAdmins(),
                        List.of(
                                new RegistryState.Person(
                                        "alice",
                                        "Alicia",
                                        "",
                                        alice.key(),
                                        alice.fingerprint(),
                                        RegistryState.TRUSTED,
                                        "",
                                        List.of(),
                                        "",
                                        blob,
                                        change)),
                        walked.hosts(),
                        walked.revoked(),
                        blobs,
                        changes,
                        List.of(),
                        List.of(),
                        List.of());
        RegistryState.fromJson(record.toJson());
        readAfresh(read(record), "does not match the registry's history");
    }

    /**
     * A host admin's backup key places no host card nor access file in the walk, so a record naming
     * one it placed must not bring that host's card or project admins in either.
     */
    @ParameterizedTest
    @ValueSource(strings = {"card", "access.yml"})
    void a_trust_record_holding_a_host_file_a_host_admins_backup_key_placed_is_discarded(
            String name) throws Exception {
        forge.owners.put("michael", true);
        forge.keys.put("michael", List.of(key("michael")));
        write("owners.yml", backedOwners("michael", "hank"));
        commit("michael");
        final String path = "hosts/walk6m/" + name;
        final String text = name.equals("card") ? hostCard("bob") : access("demo");
        write(path, text);
        final String placedAt = commit("bob");
        final RegistryState walked = read(null);
        assertThat(walked.hosts()).as("the walk refuses what hank's backup key placed").isEmpty();
        final Map<String, String> blobs = new HashMap<>(walked.blobs());
        blobs.put(path, git("rev-parse", placedAt + ":" + path));
        final Map<String, RegistryState.Change> changes = new HashMap<>(walked.changes());
        changes.put(path, new RegistryState.Change(placedAt, pin("bob")));
        final RegistryState.Host host =
                name.equals("card")
                        ? new RegistryState.Host(
                                "walk6m",
                                RegistryState.Card.of(HostCard.read(text, path), pin("bob")),
                                List.of(),
                                List.of())
                        : new RegistryState.Host(
                                "walk6m",
                                null,
                                List.of(new RegistryState.Admin("alice", pin("alice"))),
                                List.of(
                                        new RegistryState.Project(
                                                "demo",
                                                "alice",
                                                "ssh://git@example.org/demo.git")));
        final RegistryState record =
                new RegistryState(
                        walked.commit(),
                        walked.accepted(),
                        walked.firm(),
                        walked.owners(),
                        walked.hostAdmins(),
                        walked.people(),
                        List.of(host),
                        walked.revoked(),
                        blobs,
                        changes,
                        List.of(),
                        List.of(),
                        List.of());
        RegistryState.fromJson(record.toJson());
        readAfresh(read(record), "does not match the registry's history");
    }

    /**
     * A record must hold a host's card only as the walk would take it: naming its own host and
     * signed by the admin who committed it. Otherwise one host's card answers for another.
     */
    @ParameterizedTest
    @ValueSource(strings = {"names-another-host", "signed-by-another-key"})
    void a_trust_record_holding_a_host_card_the_walk_refused_is_discarded(String damage)
            throws Exception {
        founded("hank");
        final String text =
                HostCard.write(
                        damage.equals("names-another-host") ? "staging" : "walk6m",
                        "walk6m.example.org",
                        2201,
                        key("hank"),
                        3000,
                        2222,
                        8448,
                        keys.get(damage.equals("names-another-host") ? "hank" : "bob"));
        write("hosts/walk6m/card", text);
        final String forgedAt = commit("hank");
        final RegistryState walked = read(null);
        assertThat(walked.hosts()).as("the walk refuses it").isEmpty();
        final String path = "hosts/walk6m/card";
        final Map<String, String> blobs = new HashMap<>(walked.blobs());
        blobs.put(path, git("rev-parse", forgedAt + ":" + path));
        final Map<String, RegistryState.Change> changes = new HashMap<>(walked.changes());
        changes.put(path, new RegistryState.Change(forgedAt, pin("hank")));
        final RegistryState record =
                new RegistryState(
                        walked.commit(),
                        walked.accepted(),
                        walked.firm(),
                        walked.owners(),
                        walked.hostAdmins(),
                        walked.people(),
                        List.of(
                                new RegistryState.Host(
                                        "walk6m",
                                        RegistryState.Card.of(
                                                HostCard.read(text, path), pin("hank")),
                                        List.of(),
                                        List.of())),
                        walked.revoked(),
                        blobs,
                        changes,
                        List.of(),
                        List.of(),
                        List.of());
        RegistryState.fromJson(record.toJson());
        readAfresh(read(record), "does not match the registry's history");
    }

    /**
     * A state handed over in memory must pass every check a record read from its file does: one
     * without its changes, or without a commit, would skip the checks of who signed what.
     */
    @ParameterizedTest
    @ValueSource(strings = {"no-changes", "no-commit"})
    void a_state_handed_over_in_memory_is_checked_as_a_record_is(String damage) throws Exception {
        founded("hank");
        forge.keys.put("alice", List.of(key("alice")));
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        commit("alice");
        final RegistryState good = read(null);
        final boolean noChanges = damage.equals("no-changes");
        final RegistryState bad =
                new RegistryState(
                        noChanges ? good.commit() : null,
                        good.accepted(),
                        good.firm(),
                        good.owners(),
                        good.hostAdmins(),
                        good.people(),
                        good.hosts(),
                        good.revoked(),
                        good.blobs(),
                        noChanges ? Map.of() : good.changes(),
                        List.of(),
                        good.refusals(),
                        List.of());
        readAfresh(read(bad), noChanges ? "changes is malformed" : "commit is malformed");
    }

    /**
     * Revoking a stolen key must take it from every card that holds it or was handed on from it,
     * under any login; kept trusted, the thief still speaks for those people.
     */
    @Test
    void revoking_a_key_refuses_every_card_holding_it_or_handed_on_from_it() throws Exception {
        founded("hank");
        forge.keys.put("bob", List.of(key("alice")));
        forge.keys.put("carol", List.of(key("alice")));
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        write("people/bob.card", card("bob", "alice", null, "Bob"));
        write("people/carol.card", card("carol", "alice", null, "Carol"));
        commit("alice");
        write("people/carol.card", card("carol", "carol", "alice", "Carol"));
        commit("carol");
        final RegistryState before = read(null);
        assertThat(person(before, "bob").state()).isEqualTo(RegistryState.TRUSTED);
        assertThat(person(before, "carol").state()).isEqualTo(RegistryState.TRUSTED);

        write("people/alice.card", card("alice", "alice-new", null, "Alice"));
        final String revocation = commit("michael");
        final RegistryState state = read(null);
        for (String login : List.of("bob", "carol")) {
            assertThat(person(state, login))
                    .extracting(RegistryState.Person::state, RegistryState.Person::why)
                    .as(login)
                    .containsExactly(RegistryState.REFUSED, "revoked key");
            assertThat(state.blobs()).doesNotContainKey("people/" + login + ".card");
            assertThat(state.warnings())
                    .contains(revocation + " people/" + login + ".card: revoked key");
        }
        assertThat(read(RegistryState.fromJson(before.toJson())))
                .as("a read from the record before ends as the read from scratch")
                .extracting(RegistryState::people, RegistryState::blobs)
                .containsExactly(state.people(), state.blobs());
    }

    /**
     * A first card naming a predecessor was never handed on: the login never had that key accepted,
     * so the forge listing it must not trust the card at a later read either.
     */
    @Test
    void a_first_card_naming_a_predecessor_is_judged_by_its_own_key_alone() throws Exception {
        forge.keys.put("alice", List.of(key("alice")));
        write("people/alice.card", card("alice", "alice-new", "alice", "Alice"));
        commit("alice-new");
        final RegistryState first = read(null);
        assertThat(person(first, "alice"))
                .extracting(RegistryState.Person::state, RegistryState.Person::predecessors)
                .containsExactly(RegistryState.REFUSED, List.of());
        final RegistryState second = read(RegistryState.fromJson(first.toJson()));
        final RegistryState fresh = read(null);
        assertThat(second)
                .as("read 2 answers as a fresh read")
                .extracting(RegistryState::people, RegistryState::blobs)
                .containsExactly(fresh.people(), fresh.blobs());
        assertThat(person(second, "alice").state()).isEqualTo(RegistryState.REFUSED);
    }

    /**
     * A record that names an owner or host admin its owners file does not would hand them the
     * registry; what a file says is read from the file, never from the record.
     */
    @Test
    void a_trust_record_naming_owners_its_owners_file_does_not_is_discarded() throws Exception {
        founded("hank");
        final String good = read(null).toJson();
        final String bad =
                good.replace("{\"login\":\"michael\"", "{\"login\":\"mallory\"")
                        .replace("[{\"login\":\"hank\"", "[{\"login\":\"mallory\"");
        assertThat(bad).as("the damage applied").isNotEqualTo(good);
        final RegistryState record = RegistryState.fromJson(bad);
        assertThat(record.hostAdmins().get("walk6m").getFirst().login()).isEqualTo("mallory");
        readAfresh(read(record), "does not match the registry's history");
    }

    /**
     * A key revoked after its card was trusted must not stay trusted: not in a record that says so,
     * nor through a read that settles such a state, nor along a walk.
     */
    @Test
    void a_card_of_a_key_revoked_after_it_was_trusted_is_trusted_no_longer() throws Exception {
        founded("hank");
        forge.keys.put("alice", List.of(key("alice"), key("alice-new")));
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        commit("alice");
        final RegistryState atFirst = read(null);
        write("people/alice.card", card("alice", "alice-new", null, "Alice"));
        final String revocation = commit("michael");
        final RegistryState forged =
                new RegistryState(
                        revocation,
                        atFirst.accepted(),
                        atFirst.firm(),
                        atFirst.owners(),
                        atFirst.hostAdmins(),
                        atFirst.people(),
                        atFirst.hosts(),
                        List.of(pin("alice")),
                        atFirst.blobs(),
                        atFirst.changes(),
                        List.of(),
                        atFirst.refusals(),
                        List.of());
        assertThatThrownBy(() -> RegistryState.fromJson(forged.toJson()))
                .isInstanceOf(CardException.class)
                .hasMessageContaining("revoked");
        // Handed over in memory, it is discarded all the same.
        readAfresh(read(forged), "revoked is malformed");

        final RegistryState walked = read(RegistryState.fromJson(atFirst.toJson()));
        assertThat(walked)
                .as("walked from the record, as a fresh read")
                .extracting(RegistryState::people, RegistryState::revoked)
                .containsExactly(read(null).people(), List.of(pin("alice")));
        assertThat(person(walked, "alice").fingerprint()).isEqualTo(pin("alice-new"));
    }

    /**
     * A change must name the commit that changed its path; one naming a later commit by the same
     * key that left the file as it was would let that commit answer for the change.
     */
    @Test
    void a_trust_record_whose_change_did_not_touch_its_path_is_discarded() throws Exception {
        founded("hank");
        forge.keys.put("alice", List.of(key("alice")));
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        final String filed = commit("alice");
        write("people/carol.card", card("carol", "carol", null, "Carol"));
        final String other = commit("alice");
        final String good = read(null).toJson();
        final String bad =
                good.replace(
                        "\"commit\":\"" + filed + "\",\"signer\"",
                        "\"commit\":\"" + other + "\",\"signer\"");
        assertThat(bad).as("the damage applied").isNotEqualTo(good);
        final RegistryState record = RegistryState.fromJson(bad);
        readAfresh(read(record), "does not match the registry's history");
    }

    /** Runs git as the read does, noting every command's arguments. */
    private CommandRunner noting(List<List<String>> seen) {
        return command -> {
            seen.add(command.arguments());
            return runner.run(command);
        };
    }

    /** A record's commit is handed to git; an option in its place must be refused before. */
    @Test
    void a_trust_record_whose_commit_is_an_option_is_discarded_before_git_sees_it()
            throws Exception {
        founded("hank");
        final RegistryState good = read(null);
        final RegistryState bad =
                new RegistryState(
                        "--batch-all-objects",
                        good.accepted(),
                        good.firm(),
                        good.owners(),
                        good.hostAdmins(),
                        good.people(),
                        good.hosts(),
                        good.revoked(),
                        good.blobs(),
                        good.changes(),
                        List.of(),
                        good.refusals(),
                        List.of());
        final List<List<String>> seen = new ArrayList<>();
        readAfresh(
                new RegistryHistory(noting(seen)).read(repo, bad, forge, "acme"),
                "commit is malformed");
        assertThat(seen).noneMatch(arguments -> arguments.contains("--batch-all-objects"));
    }

    /**
     * Every git the read runs on a revision ends its options first, so that no name it reads, from
     * a record or the history, is ever taken for an option.
     */
    @Test
    void every_revision_the_read_hands_to_git_comes_after_the_end_of_options() throws Exception {
        founded("hank");
        forge.keys.put("alice", List.of(key("bob")));
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        commit("alice");
        final RegistryState first = read(null);
        write("people/bob.card", card("bob", "bob", null, "Bob"));
        commit("bob");
        forge.keys.put("alice", List.of(key("alice")));
        final List<List<String>> seen = new ArrayList<>();
        new RegistryHistory(noting(seen))
                .read(repo, RegistryState.fromJson(first.toJson()), forge, "acme");
        final Set<String> taking =
                Set.of(
                        "cat-file",
                        "ls-tree",
                        "rev-list",
                        "diff-tree",
                        "verify-commit",
                        "merge-base");
        final List<List<String>> missing =
                seen.stream()
                        .filter(arguments -> arguments.stream().anyMatch(taking::contains))
                        .filter(arguments -> !arguments.contains("--end-of-options"))
                        .toList();
        assertThat(missing).isEmpty();
        assertThat(seen).anyMatch(arguments -> arguments.contains("verify-commit"));
    }

    /**
     * A trust record is the walk's word about commits it judged; one naming a commit after its own,
     * or a signer the commit does not bear, would steer what is answered as filed.
     */
    @ParameterizedTest
    @ValueSource(strings = {"later-commit", "other-signer"})
    void a_trust_record_the_history_does_not_bear_out_is_discarded(String damage) throws Exception {
        founded("hank");
        forge.keys.put("alice", List.of(key("alice")));
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        final String filed = commit("alice");
        final String good = read(null).toJson();
        // Signed by the same key, holding the same card: only its place in the history differs.
        write("people/carol.card", card("carol", "carol", null, "Carol"));
        final String later = commit("alice");
        final String bad =
                damage.equals("later-commit")
                        ? good.replace(
                                "\"commit\":\"" + filed + "\",\"signer\"",
                                "\"commit\":\"" + later + "\",\"signer\"")
                        : good.replace(
                                "\"commit\":\"" + filed + "\",\"signer\":\"" + pin("alice"),
                                "\"commit\":\"" + filed + "\",\"signer\":\"" + pin("bob"));
        assertThat(bad).as("the damage applied").isNotEqualTo(good);
        final RegistryState record = RegistryState.fromJson(bad);
        readAfresh(read(record), "does not match the registry's history");
    }

    /**
     * A card settled to trusted takes back only the refusals of its own commit and before; a later
     * commit's refusal of the path is still in force, as a walk from scratch keeps it.
     */
    @Test
    void settling_a_card_keeps_a_later_refusal_of_its_path() throws Exception {
        forge.keys.put("alice", List.of(key("bob")));
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        commit(null);
        write("people/alice.card", card("alice", "alice", null, "Alice A."));
        commit("alice");
        write("people/alice.card", card("alice", "alice", null, "Alice B."));
        final String later = commit("bob");
        final RegistryState refused = read(null);
        assertThat(refused.refusals()).hasSize(2);

        forge.keys.put("alice", List.of(key("alice")));
        final RegistryState settled = read(RegistryState.fromJson(refused.toJson()));
        assertThat(person(settled, "alice").state()).isEqualTo(RegistryState.TRUSTED);
        assertThat(settled.lastInForce())
                .extracting(RegistryState.Refusal::commit, RegistryState.Refusal::path)
                .containsExactly(later, "people/alice.card");
        assertThat(settled.refusals()).isEqualTo(read(null).refusals());
    }

    /**
     * A first-seen card settled to refused takes back only its own acceptance: a trusted card
     * accepted and later removed still made the registry accepted, as a walk from scratch says.
     */
    @Test
    void settling_a_first_seen_card_to_refused_keeps_a_firm_acceptance() throws Exception {
        forge.keys.put("bob", List.of(key("bob")));
        write("people/bob.card", card("bob", "bob", null, "Bob"));
        commit("bob");
        Files.delete(repo.resolve("people/bob.card"));
        commit("bob");
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        commit("alice");
        final RegistryState offline = read(null);
        assertThat(person(offline, "alice").state()).isEqualTo(RegistryState.FIRST_SEEN);
        assertThat(offline.blobs()).containsOnlyKeys("people/alice.card");

        forge.keys.put("alice", List.of(key("bob")));
        final RegistryState settled = read(RegistryState.fromJson(offline.toJson()));
        assertThat(person(settled, "alice").state()).isEqualTo(RegistryState.REFUSED);
        assertThat(settled.revision())
                .as("the trusted card was accepted")
                .isEqualTo(read(null).revision())
                .isNotNull();
    }

    /** A signature names its key; only Git's check proves it signed this commit's text. */
    @Test
    void a_commit_whose_signature_does_not_verify_over_its_text_is_refused() throws Exception {
        founded("hank");
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        commit("alice");
        final Path altered = dir.resolve("altered-commit");
        Files.writeString(
                altered,
                git("cat-file", "commit", "HEAD")
                                .replace("\n\nregistry change", "\n\nregistry change, altered")
                        + "\n");
        final String forged = git("hash-object", "-t", "commit", "-w", altered.toString());
        git("update-ref", "refs/heads/main", forged);
        final RegistryState state = read(null);
        assertThat(state.people()).isEmpty();
        assertThat(state.lastInForce())
                .isEqualTo(
                        new RegistryState.Refusal(
                                forged,
                                "people/alice.card",
                                "commit's SSH signature does not verify against " + pin("alice")));
    }

    /** A clone's own guess at HEAD may name a stale branch, hiding the commits after it. */
    @Test
    void the_read_reaches_exactly_the_commit_the_checkout_has_checked_out() throws Exception {
        founded("hank");
        git("branch", "master");
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        commit("alice");
        git("checkout", "-q", "--detach");
        write("people/bob.card", card("bob", "bob", null, "Bob"));
        final String detached = commit("bob");
        git("branch", "-f", "main", "master");
        final RegistryState state = read(null);
        assertThat(state.commit()).isEqualTo(detached);
        assertThat(state.people())
                .extracting(RegistryState.Person::login)
                .containsExactly("alice", "bob");
    }

    /** A later read starts where the record left off, so an old refusal is not repeated. */
    @Test
    void a_trust_record_round_trips_and_the_next_read_walks_only_newer_commits() throws Exception {
        founded("alice");
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        write("hosts/walk6m/card", hostCard("alice"));
        write("hosts/walk6m/access.yml", access("demo"));
        commit("alice");
        write("people/alice.card", card("alice", "alice-new", null, "Alice"));
        commit("michael");
        write("hosts/walk6m/access.yml", access("other"));
        commit("bob");
        final RegistryState first = read(null);
        assertThat(first.revoked()).as("the fixture holds a revocation").isNotEmpty();
        assertThat(first.lastInForce()).as("and a refusal").isNotNull();
        final RegistryState record = RegistryState.fromJson(first.toJson());
        assertThat(record).as("nothing is lost on the way through JSON").isEqualTo(first);
        forge.owners.put("michael", false);
        forge.asked.clear();
        write("people/carol.card", card("carol", "carol", null, "Carol"));
        final String next = commit("carol");
        final RegistryState second = read(record);
        assertThat(second.commit()).isEqualTo(next);
        assertThat(second.warnings()).as("only this read's commits are judged").isEmpty();
        assertThat(second.lastInForce())
                .as("the old refusal stays in force")
                .isEqualTo(first.lastInForce());
        assertThat(second.owners()).isEqualTo(first.owners());
        assertThat(second.revoked()).containsExactly(pin("alice"));
        assertThat(forge.asked)
                .as("the founding is not judged again; only the first-seen card is asked again")
                .containsExactly("keys alice", "keys carol");
    }

    /** A damaged record must be refused whole rather than trusted in part. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "truncated",
                "unknown-field",
                "missing-field",
                "fingerprint-of-another-key",
                "port-fraction",
                "unjudged-blob-path",
                "state",
                "address-option",
                "host-key-cert-authority",
                "project-name",
                "project-admin",
                "access-repository-option",
                "access-repository-ext",
                "control-in-name",
                "duplicate-person",
                "owners-without-blob",
                "person-without-blob",
                "blob-without-change",
                "change-of-another-path",
                "host-card-change-by-another-signer",
                "owners-not-firm",
                "owners-but-not-accepted",
                "hosts-without-owners",
                "removed-while-firm"
            })
    void a_malformed_trust_record_is_discarded_whole_and_the_registry_read_afresh(String damage)
            throws Exception {
        founded("hank");
        write("hosts/walk6m/card", hostCard("hank"));
        write("hosts/walk6m/access.yml", access("demo"));
        commit("hank");
        write("people/alice.card", card("alice", "alice", null, "Alice"));
        commit("alice");
        final String good = read(null).toJson();
        RegistryState.fromJson(good);
        final String bad =
                switch (damage) {
                    case "truncated" -> good.substring(0, good.length() - 1);
                    case "unknown-field" -> good.replaceFirst("\\{", "{\"extra\":1,");
                    case "missing-field" -> good.replace("\"revoked\":[],", "");
                    case "fingerprint-of-another-key" ->
                            good.replace(
                                    "\"fingerprint\":\"" + pin("alice"),
                                    "\"fingerprint\":\"" + pin("bob"));
                    case "port-fraction" ->
                            good.replace("\"ssh_port\":2201", "\"ssh_port\":2201.5");
                    case "unjudged-blob-path" ->
                            good.replace("\"people/alice.card\":", "\"other.yml\":");
                    // What the walk would refuse must not come back in through the record: a
                    // person's machine writes host cards into its ssh configuration, and access
                    // repositories reach git.
                    case "address-option" ->
                            good.replace(
                                    "\"address\":\"walk6m.example.org\"",
                                    "\"address\":\"walk6m\\nProxyCommand sh\"");
                    case "host-key-cert-authority" ->
                            good.replace(
                                    "\"ssh_host_key\":\"ssh-ed25519",
                                    "\"ssh_host_key\":\"cert-authority ssh-ed25519");
                    case "project-name" ->
                            good.replace("\"name\":\"demo\"", "\"name\":\"../escape\"");
                    case "project-admin" ->
                            good.replace("\"admin\":\"alice\"", "\"admin\":\"unlisted\"");
                    case "access-repository-option" ->
                            good.replace(
                                    "\"ssh://git@example.org/demo.git\"",
                                    "\"--upload-pack=touch /tmp/x\"");
                    case "access-repository-ext" ->
                            good.replace("\"ssh://git@example.org/demo.git\"", "\"ext::sh -c x\"");
                    case "control-in-name" ->
                            good.replace("\"name\":\"Alice\"", "\"name\":\"Al\\u001bice\"");
                    case "duplicate-person" ->
                            good.replaceFirst(
                                    "\"people\":\\[(\\{.*?\\}\\})\\]", "\"people\":[$1,$1]");
                    case "owners-without-blob" ->
                            good.replaceFirst("\"owners\\.yml\":\"[0-9a-f]+\",", "");
                    case "person-without-blob" ->
                            good.replaceFirst(",\"people/alice\\.card\":\"[0-9a-f]+\"", "");
                    // A file accepted from no commit the walk named would answer an id for none.
                    case "blob-without-change" ->
                            good.replaceFirst(",\"people/alice\\.card\":\\{[^{}]*\\}", "");
                    // A change for a path nothing was accepted at.
                    case "change-of-another-path" ->
                            good.replaceFirst(
                                    "\"people/alice\\.card\":(\\{\"commit\"[^{}]*\\})",
                                    "\"people/alice.card\":$1,\"people/bob.card\":$1");
                    case "host-card-change-by-another-signer" ->
                            good.replaceFirst(
                                    "(\"hosts/walk6m/card\":\\{\"commit\":\"[0-9a-f]+\",\"signer\":\")"
                                            + java.util.regex.Pattern.quote(pin("hank")),
                                    "$1"
                                            + java.util.regex.Matcher.quoteReplacement(
                                                    pin("michael")));
                    // Settling the last first-seen card would then leave owners on a registry that
                    // accepted nothing, a record no read takes back.
                    case "owners-not-firm" -> good.replace("\"firm\":true", "\"firm\":false");
                    // A record that says nothing was accepted, yet names owners or hosts, would
                    // give them to a registry no read ever founded.
                    case "owners-but-not-accepted" ->
                            good.replace("\"accepted\":true", "\"accepted\":false");
                    case "hosts-without-owners" ->
                            good.replaceFirst(
                                            "\"owners\":\\[.*?\\],\"host_admins\"",
                                            "\"owners\":[],\"host_admins\"")
                                    .replaceFirst(
                                            "\"host_admins\":\\{.*?\\]\\},\"people\"",
                                            "\"host_admins\":{},\"people\"")
                                    .replaceFirst("\"owners\\.yml\":\"[0-9a-f]+\",", "");
                    // Once firm, no forge answer about a removal is asked again.
                    case "removed-while-firm" ->
                            good.replace(
                                    "\"removed\":[]",
                                    "\"removed\":[{\"login\":\"bob\",\"fingerprint\":\""
                                            + pin("bob")
                                            + "\",\"predecessors\":[]}]");
                    default -> good.replace("\"state\":\"first-seen\"", "\"state\":\"owner\"");
                };
        assertThat(bad).as("the damage applied").isNotEqualTo(good);
        assertThatThrownBy(() -> RegistryState.fromJson(bad))
                .isInstanceOf(CardException.class)
                .hasMessageStartingWith("the registry trust record is not");
        // Discarded and walked afresh, never refusing the read nor trusting the damage.
        readAfresh(
                new RegistryHistory(runner).read(repo, bad, "the record", forge, "acme"),
                "the record: the registry trust record is not");
    }
}
