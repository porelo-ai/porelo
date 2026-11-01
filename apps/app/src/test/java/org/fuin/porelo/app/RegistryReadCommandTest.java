package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.vault.KernelKeyring;
import org.fuin.porelo.vault.RegistryState;
import org.fuin.porelo.vault.VaultEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Guards the registry reader's answer, its trust record and the forge its origin names. */
class RegistryReadCommandTest {
    @TempDir Path dir;
    private RegistryCheckout registry;
    private KernelKeyring keyring;

    @BeforeEach
    void checkout() throws Exception {
        registry = new RegistryCheckout(dir);
    }

    @AfterEach
    void tidyUp() throws Exception {
        registry.close();
        if (keyring != null) {
            keyring.forget();
        }
        PoreloContext.forgetAsked();
    }

    private static List<Object> keys(Object map) {
        return new ArrayList<>(((Map<?, ?>) map).keySet());
    }

    private static Map<?, ?> first(Object list) {
        return (Map<?, ?>) ((List<?>) list).getFirst();
    }

    /** A consumer reads the answer by its fields and their order; a missing one breaks it. */
    @Test
    void answers_the_contract_in_its_key_order() throws Exception {
        registry.founded();
        registry.forge.keys.put("alice", List.of(registry.key("alice")));
        registry.write("hosts/walk6m/card", registry.hostCard("walk6m", "hank"));
        registry.write("hosts/walk6m/access.yml", registry.access("demo"));
        registry.commit("hank");
        registry.write("people/alice.card", registry.card("alice", "alice"));
        registry.commit("alice");
        registry.write("hosts/walk6m/access.yml", registry.access("other"));
        final String refused = registry.commit("bob");

        assertThat(registry.registry("read", registry.repo.toString(), "--json")).isZero();
        final Map<?, ?> answer = registry.answer();
        assertThat(keys(answer))
                .containsExactly(
                        "revision",
                        "refused_commit",
                        "people",
                        "owners",
                        "host_admins",
                        "warnings",
                        "hosts");
        assertThat(answer.get("revision")).isEqualTo(refused);
        assertThat(keys(answer.get("refused_commit"))).containsExactly("commit", "path", "why");
        assertThat(((Map<?, ?>) answer.get("refused_commit")).get("commit")).isEqualTo(refused);
        assertThat(((Map<?, ?>) answer.get("refused_commit")).get("path"))
                .isEqualTo("hosts/walk6m/access.yml");
        final Map<?, ?> person = first(answer.get("people"));
        assertThat(keys(person))
                .containsExactly("login", "name", "email", "key", "fingerprint", "state", "why");
        assertThat(person.get("email")).as("absent, and still there").isEqualTo("");
        assertThat(person.get("why")).isEqualTo("");
        assertThat(person.get("state")).isEqualTo(RegistryState.TRUSTED);
        assertThat(person.get("fingerprint")).isEqualTo(registry.pin("alice"));
        assertThat(keys(first(answer.get("owners"))))
                .containsExactly("login", "fingerprint", "backup");
        assertThat(first(answer.get("owners")).get("backup")).isEqualTo(false);
        // Who signs a host's files, as owners.yml names them: not the access file's admins.
        assertThat(answer.get("host_admins"))
                .isEqualTo(
                        Map.of(
                                "walk6m",
                                List.of(
                                        Map.of(
                                                "login",
                                                "hank",
                                                "fingerprint",
                                                registry.pin("hank"),
                                                "backup",
                                                false))));
        assertThat(keys(first(((Map<?, ?>) answer.get("host_admins")).get("walk6m"))))
                .containsExactly("login", "fingerprint", "backup");
        assertThat((List<?>) answer.get("warnings")).anyMatch(w -> w.toString().contains(refused));
        final Map<?, ?> host = first(answer.get("hosts"));
        assertThat(keys(host)).containsExactly("host", "card", "admins", "projects");
        assertThat(keys(host.get("card")))
                .containsExactly(
                        "host", "address", "ssh_port", "ssh_host_key", "services", "signed_by");
        assertThat(((Map<?, ?>) host.get("card")).get("signed_by")).isEqualTo(registry.pin("hank"));
        assertThat(keys(first(host.get("admins")))).containsExactly("login", "fingerprint");
        assertThat(keys(first(host.get("projects"))))
                .containsExactly("name", "admin", "access_repository");
        assertThat(first(host.get("projects")).get("name"))
                .as("the last good one")
                .isEqualTo("demo");
    }

    /** A host with an access file and no card answers null, never a field left out. */
    @Test
    void a_host_without_a_card_answers_card_null_and_a_registry_with_nothing_accepted_no_revision()
            throws Exception {
        registry.write("owners.yml", "owners: []\n");
        registry.commit("bob");
        assertThat(registry.registry("read", registry.repo.toString(), "--json")).isZero();
        assertThat(registry.out.toString()).contains("\"revision\":null");

        registry.founded();
        registry.write("hosts/walk6m/access.yml", registry.access("demo"));
        registry.commit("hank");
        assertThat(registry.registry("read", registry.repo.toString(), "--json")).isZero();
        assertThat(registry.out.toString()).contains("\"card\":null");
        assertThat(registry.answer().get("refused_commit")).isNull();
    }

    /** The record is what the next read trusts; anybody else on the machine must not read it. */
    @Test
    void the_trust_record_is_written_owner_only_after_a_read_and_the_next_read_continues_it()
            throws Exception {
        registry.founded();
        assertThat(registry.record()).doesNotExist();
        assertThat(registry.registry("read", registry.repo.toString())).isZero();
        assertThat(registry.record()).isRegularFile();
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(registry.record())))
                .isEqualTo("rw-------");
        assertThat(
                        PosixFilePermissions.toString(
                                Files.getPosixFilePermissions(registry.record().getParent())))
                .isEqualTo("rwx------");
        assertThat(registry.record().getParent())
                .isEqualTo(registry.paths.xdg().state().resolve("registry"));
        assertThat(RegistryState.fromJson(Files.readString(registry.record())).commit())
                .isEqualTo(registry.head());

        // The forge said michael owns it once; the next read trusts the record, not the forge.
        registry.forge.owners.clear();
        registry.forge.asked.clear();
        registry.write("people/alice.card", registry.card("alice", "alice"));
        final String next = registry.commit("alice");
        assertThat(registry.registry("read", registry.repo.toString(), "--json")).isZero();
        assertThat(registry.forge.asked).containsExactly("keys alice");
        assertThat(registry.answer().get("revision")).isEqualTo(next);
        assertThat(first(registry.answer().get("owners")).get("login")).isEqualTo("michael");
    }

    /**
     * A founding the forge could not decide is judged again at the next read; a record kept for a
     * read that reached no commit would have nothing to continue from.
     */
    @Test
    void a_read_stopped_before_its_first_commit_keeps_no_trust_record() throws Exception {
        registry.write("owners.yml", registry.owners());
        final String root = registry.commit("michael");
        assertThat(registry.registry("read", registry.repo.toString(), "--json")).isZero();
        assertThat(registry.answer().get("revision")).isNull();
        assertThat(registry.answer().get("owners")).asList().isEmpty();
        assertThat((List<?>) registry.answer().get("warnings"))
                .singleElement()
                .asString()
                .startsWith(root + " owners.yml: undecided");
        assertThat(registry.record()).doesNotExist();
    }

    /** A read that was refused must not become what the next read trusts. */
    @Test
    void a_refused_read_leaves_the_trust_record_as_it_was() throws Exception {
        final String founded = registry.founded();
        registry.write("people/alice.card", registry.card("alice", "alice"));
        registry.commit("alice");
        assertThat(registry.registry("read", registry.repo.toString())).isZero();
        final String kept = Files.readString(registry.record());

        registry.git("reset", "-q", "--hard", founded);
        registry.write("people/bob.card", registry.card("bob", "bob"));
        registry.commit("bob");
        assertThat(registry.registry("read", registry.repo.toString(), "--json")).isEqualTo(65);
        assertThat(registry.out.toString()).isEmpty();
        assertThat(Files.readString(registry.record())).isEqualTo(kept);
    }

    /**
     * A damaged record is discarded and the registry walked afresh, said so naming the file and
     * never what it holds; the fresh state replaces it, so the next read continues from that.
     */
    @Test
    void a_trust_record_that_does_not_parse_is_discarded_and_the_registry_read_afresh()
            throws Exception {
        registry.founded();
        assertThat(registry.registry("read", registry.repo.toString(), "--json")).isZero();
        final Object fresh = registry.answer().get("owners");
        final String kept = Files.readString(registry.record());
        Files.writeString(registry.record(), "{\"commit\": \"\u001b[2J secret\"");
        assertThat(registry.registry("read", registry.repo.toString(), "--json")).isZero();
        assertThat(registry.answer().get("owners")).as("as a fresh read").isEqualTo(fresh);
        final List<?> warnings = (List<?>) registry.answer().get("warnings");
        assertThat(warnings.subList(1, warnings.size())).isEqualTo(registry.soleWarnings());
        assertThat(warnings.getFirst())
                .asString()
                .startsWith("the registry trust record was discarded")
                .contains(registry.record().toString())
                .doesNotContain("secret")
                .doesNotContain("\u001b");
        assertThat(registry.err.toString()).doesNotContain("secret").doesNotContain("\u001b");
        assertThat(RegistryState.fromJson(Files.readString(registry.record())))
                .as("replaced by the fresh state")
                .usingRecursiveComparison()
                .ignoringFields("warnings")
                .isEqualTo(RegistryState.fromJson(kept));
    }

    /**
     * A registry or host with one owner or admin and no backup key is warned about, on stderr too,
     * until a backup key is filed for them.
     */
    @Test
    void a_sole_owner_or_host_admin_without_a_backup_key_is_warned_about() throws Exception {
        registry.founded();
        assertThat(registry.registry("read", registry.repo.toString(), "--json")).isZero();
        assertThat(registry.answer().get("warnings")).isEqualTo(registry.soleWarnings());
        assertThat(registry.err.toString())
                .contains("porelo: warning: " + registry.soleWarnings().getFirst())
                .contains("porelo: warning: " + registry.soleWarnings().getLast());

        registry.write(
                "owners.yml",
                registry.owners()
                                .replace(
                                        "host_admins:",
                                        "  - login: michael\n    key: \""
                                                + registry.key("alice")
                                                + "\"\n    backup: true\nhost_admins:")
                        + "    - login: hank\n      key: \""
                        + registry.key("bob")
                        + "\"\n      backup: true\n");
        registry.commit("michael");
        assertThat(registry.registry("read", registry.repo.toString(), "--json")).isZero();
        assertThat(registry.answer().get("warnings")).isEqualTo(List.of());
    }

    /**
     * A record discarded by a read that then stopped before its first commit must go: left on disk,
     * the next read would discard it again, and a reader looking at the file would take it for
     * kept.
     */
    @Test
    void a_discarded_record_is_deleted_when_the_fresh_read_reaches_no_commit() throws Exception {
        registry.founded();
        assertThat(registry.registry("read", registry.repo.toString())).isZero();
        final Path where =
                registry.record()
                        .resolveSibling(
                                registry.record()
                                        .getFileName()
                                        .toString()
                                        .replace(".json", ".where.json"));
        assertThat(where).as("the origin is kept beside the record").exists();
        Files.writeString(registry.record(), "{\"commit\": \"damaged\"");
        // The forge cannot say any more whether the founder owns the organisation.
        registry.forge.owners.clear();
        assertThat(registry.registry("read", registry.repo.toString(), "--json")).isZero();
        assertThat(registry.answer().get("revision")).isNull();
        assertThat(registry.err.toString())
                .contains("porelo: warning: the registry trust record was discarded");
        assertThat(registry.record()).doesNotExist();
        assertThat(where).as("never left without its record").doesNotExist();
    }

    /** The origin names the forge asked; another form could make it a host git never fetches. */
    @ParameterizedTest
    @CsvSource({
        "https://forge.example.org/acme/registry.git, https://forge.example.org, acme",
        "https://forge.example.org/acme/registry, https://forge.example.org, acme",
        "https://forge.example.org:8443/acme/registry.git, https://forge.example.org:8443, acme",
        "git@github.com:Acme-Org/registry.git, https://github.com, Acme-Org",
        "ssh://git@forge.example.org:2222/acme/registry.git, https://forge.example.org, acme",
    })
    void the_origin_names_the_forge_and_the_organisation(
            String origin, String forge, String organisation) throws Exception {
        registry.git("remote", "set-url", "origin", origin);
        registry.founded();
        assertThat(registry.registry("read", registry.repo.toString(), "--json")).isZero();
        assertThat(registry.forge.opened).containsExactly(forge + " null");
        assertThat(registry.forge.asked).contains("owns " + organisation + " michael");
    }

    /** An origin the reader cannot place is refused, never guessed at. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://forge.example.org/acme/registry.git",
                "https://forge.example.org/acme",
                "https://forge.example.org/acme/sub/registry.git",
                "https://forge.example.org/../registry.git",
                "https://user@forge.example.org/acme/registry.git",
                "git@forge.example.org:acme/sub/registry.git",
                "ext::sh -c touch% /tmp/x",
                "/srv/git/registry.git",
                "https://forge.example.org/acme/registry.git\u001b[2J",
            })
    void an_origin_of_another_form_is_refused_naming_it(String origin) throws Exception {
        registry.git("remote", "set-url", "origin", origin);
        registry.founded();
        assertThat(registry.registry("read", registry.repo.toString())).isEqualTo(64);
        assertThat(registry.err.toString())
                .contains("is not https://HOST/ORG/REPO")
                .doesNotContain("\u001b");
        assertThat(registry.forge.opened).isEmpty();
        assertThat(registry.record()).doesNotExist();
    }

    /**
     * Git fetches from one URL, after its rewrites; a second URL, one an include adds, or a rewrite
     * would have the forge and its token be another host than the one fetched from.
     */
    @ParameterizedTest
    @ValueSource(strings = {"second-url", "included-url", "rewritten"})
    void an_origin_git_would_fetch_from_elsewhere_is_refused(String how) throws Exception {
        registry.founded();
        switch (how) {
            case "second-url" ->
                    registry.git(
                            "config",
                            "--add",
                            "remote.origin.url",
                            "https://other.example.org/acme/registry.git");
            case "included-url" -> {
                final Path extra = dir.resolve("extra.config");
                Files.writeString(
                        extra,
                        "[remote \"origin\"]\n\turl = https://other.example.org/acme/registry.git\n");
                registry.git("config", "include.path", extra.toString());
            }
            default ->
                    registry.git(
                            "config",
                            "url.https://other.example.org/.insteadOf",
                            "https://forge.example.org/");
        }
        assertThat(registry.registry("read", registry.repo.toString())).isEqualTo(64);
        assertThat(registry.err.toString()).contains("names its origin more than once");
        assertThat(registry.forge.opened).isEmpty();
        assertThat(registry.record()).doesNotExist();
    }

    /**
     * A port is another server than the one a forge's name stands for: a token held under the
     * forge's name, or declared for the host alone, must not go there.
     */
    @ParameterizedTest
    @CsvSource({
        "https://forge.example.org/acme/registry.git, , tok-forge",
        "https://forge.example.org:9999/acme/registry.git, , ",
        "https://forge.example.org:9999/acme/registry.git, https://forge.example.org, ",
        "https://forge.example.org:9999/acme/registry.git, https://forge.example.org:9999, tok-declared",
        "https://forge.example.org:9999/acme/registry.git, https://forge.example.org:9999/acme/, tok-declared",
        "ssh://git@forge.example.org:2222/acme/registry.git, , ",
        "ssh://git@forge.example.org:2222/acme/registry.git, ssh://forge.example.org:2222, tok-declared",
        "git@forge.example.org:acme/registry.git, , tok-forge",
    })
    void a_token_goes_to_an_origin_with_a_port_only_when_declared_for_that_port(
            String origin, String declared, String expected) throws Exception {
        final Path file = dir.resolve("credentials.yml");
        Files.writeString(
                file,
                declared == null
                        ? "credentials: []\n"
                        : "credentials:\n  - match: "
                                + declared
                                + "\n    kind: token\n    source: vault\n    vault: declared\n");
        final var held =
                Map.of(
                        "git.token.forge.example.org", new VaultEntry("tok-forge", "token"),
                        "declared", new VaultEntry("tok-declared", "token"));
        assertThat(
                        RegistryReadCommand.token(
                                held,
                                org.fuin.porelo.core.credential.CredentialRegistry.read(file),
                                RegistryOrigin.of(origin)))
                .isEqualTo(expected);
    }

    /**
     * A GitHub App's installation token lives an hour and is never put in the vault: declared for
     * the origin in a variable or a file, it asks ownership, and is never printed.
     */
    @ParameterizedTest
    @ValueSource(strings = {"env", "file"})
    void a_token_declared_in_a_variable_or_a_file_asks_ownership_and_is_never_printed(
            final String source) throws Exception {
        final PoreloContext context = registry.context();
        final Path token = dir.resolve("installation-token");
        Files.writeString(token, "ghs_installation4711\n");
        registry.variables.put("PORELO_GITHUB_APP_TOKEN", "ghs_installation4711");
        final Path declared = context.paths().vault().credentialRegistry();
        Files.createDirectories(declared.getParent());
        Files.writeString(
                declared,
                "credentials:\n  - match: https://forge.example.org/acme/\n    kind: token\n"
                        + "    source: "
                        + source
                        + "\n    "
                        + source
                        + ": "
                        + (source.equals("env") ? "PORELO_GITHUB_APP_TOKEN" : token)
                        + "\n");
        registry.founded();
        registry.write("people/alice.card", registry.card("alice", "alice"));
        registry.commit("bob");

        assertThat(registry.registry(context, "read", registry.repo.toString(), "--json")).isZero();
        assertThat(registry.forge.opened)
                .containsExactly("https://forge.example.org ghs_installation4711");
        assertThat(registry.out + "" + registry.err + Files.readString(registry.record()))
                .doesNotContain("installation4711");

        // It is declared for the forge's https address, where it is sent; a checkout cloned over
        // ssh asks the same forge with it, though no token may be declared for an ssh address.
        for (final String ssh :
                List.of(
                        "git@forge.example.org:acme/registry.git",
                        "ssh://git@forge.example.org:2222/acme/registry.git")) {
            registry.git("remote", "set-url", "origin", ssh);
            assertThat(registry.registry(context, "read", registry.repo.toString())).isZero();
            assertThat(registry.forge.opened.getLast())
                    .as(ssh)
                    .isEqualTo("https://forge.example.org ghs_installation4711");
        }
        registry.git("remote", "set-url", "origin", "https://forge.example.org/acme/registry.git");

        // Declared for the host alone, it is not sent to the same host on another port, though
        // the match covers that address as a prefix.
        Files.writeString(
                declared,
                Files.readString(declared)
                        .replace("https://forge.example.org/acme/", "https://forge.example.org"));
        registry.git(
                "remote", "set-url", "origin", "https://forge.example.org:9999/acme/registry.git");
        Files.delete(registry.record());
        registry.registry(context, "read", registry.repo.toString());
        assertThat(registry.forge.opened.getLast())
                .isEqualTo("https://forge.example.org:9999 null");
    }

    /** A token in the origin's URL is a secret; the refusal names the host and path alone. */
    @Test
    void an_origin_with_a_token_is_refused_without_it() throws Exception {
        registry.git(
                "remote",
                "set-url",
                "origin",
                "https://alice:tok3n@forge.example.org/acme/registry.git");
        registry.founded();
        assertThat(registry.registry("read", registry.repo.toString())).isEqualTo(64);
        assertThat(registry.err.toString())
                .contains("https://forge.example.org/acme/registry.git")
                .doesNotContain("tok3n")
                .doesNotContain("alice");
    }

    @Test
    void a_checkout_without_an_origin_is_wrong_use() throws Exception {
        registry.git("remote", "remove", "origin");
        registry.founded();
        assertThat(registry.registry("read", registry.repo.toString())).isEqualTo(64);
        assertThat(registry.err.toString()).contains("has no origin");
    }

    /** Neither a missing directory nor a plain one is read as an empty registry. */
    @Test
    void a_directory_that_is_not_a_git_repository_is_not_readable() throws Exception {
        assertThat(registry.registry("read", dir.resolve("missing").toString())).isEqualTo(66);
        final Path plain = Files.createDirectory(dir.resolve("plain"));
        assertThat(registry.registry("read", plain.toString())).isEqualTo(66);
        assertThat(registry.err.toString()).contains("not a Git repository");
    }

    /** A person reads the summary; a warning must reach them even when stdout goes to a file. */
    @Test
    void the_summary_counts_and_says_each_warning_on_stdout_and_stderr() throws Exception {
        registry.founded();
        registry.write("people/alice.card", registry.card("alice", "alice"));
        final String refused = registry.commit("bob");
        assertThat(registry.registry("read", registry.repo.toString())).isZero();
        assertThat(registry.out.toString())
                .contains("Registry " + refused)
                .contains("People: 0")
                .contains("Owners: 1")
                .contains("Hosts: 0")
                .contains("Refused: " + refused + " people/alice.card");
        assertThat(registry.out.toString().lines())
                .anyMatch(line -> line.startsWith("warning: " + refused + " people/alice.card"));
        assertThat(registry.err.toString()).contains("warning: " + refused + " people/alice.card");
    }

    /**
     * The forge token asks ownership and nothing more: it must never be printed, and a token kept
     * for another forge must never be sent to the one a checkout's origin names.
     */
    @Test
    void the_forge_token_the_vault_holds_for_that_forge_is_used_and_never_printed()
            throws Exception {
        assumeTrue(
                KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");
        final PoreloContext context = registry.context();
        final char[] passphrase = "correct horse battery staple".toCharArray();
        Files.createDirectories(context.vault().path().getParent());
        context.vault()
                .write(
                        Map.of(
                                "git.token.forge.example.org",
                                new VaultEntry("tok-forge-4711", "token"),
                                "git.token",
                                new VaultEntry("tok-elsewhere-0815", "token")),
                        passphrase);
        keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        keyring.store(passphrase);
        registry.founded();
        registry.write("people/alice.card", registry.card("alice", "alice"));
        registry.commit("bob");

        assertThat(registry.registry(context, "read", registry.repo.toString())).isZero();
        assertThat(registry.registry(context, "read", registry.repo.toString(), "--json")).isZero();
        assertThat(registry.forge.opened)
                .containsExactly(
                        "https://forge.example.org tok-forge-4711",
                        "https://forge.example.org tok-forge-4711");
        assertThat(registry.out + "" + registry.err + Files.readString(registry.record()))
                .doesNotContain("tok-forge")
                .doesNotContain("tok-elsewhere");

        // Another forge: only the plain token is held for it, and it is not sent.
        registry.git("remote", "set-url", "origin", "git@other.example.org:acme/registry.git");
        assertThat(registry.registry(context, "read", registry.repo.toString())).isZero();
        assertThat(registry.forge.opened.getLast()).isEqualTo("https://other.example.org null");

        // Shut, the vault is not asked for: the forge is opened without a token.
        keyring.forget();
        PoreloContext.forgetAsked();
        registry.git("remote", "set-url", "origin", RegistryCheckout.ORIGIN);
        Files.delete(registry.record());
        assertThat(registry.registry(context, "read", registry.repo.toString())).isZero();
        assertThat(registry.forge.opened.getLast()).isEqualTo("https://forge.example.org null");
    }
}
