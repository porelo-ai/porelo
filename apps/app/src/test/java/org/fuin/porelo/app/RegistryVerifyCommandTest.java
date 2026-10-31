package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.vault.HostCard;
import org.fuin.porelo.vault.OpenSshPrivateKey;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.RegistryVerifier;
import org.fuin.porelo.vault.SigningKey;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

/** Guards against unauthorized host state, restored access, and untrusted account keys. */
class RegistryVerifyCommandTest {
    @TempDir Path dir;
    private Path repo;
    private Path adminFile;
    private SigningKey admin;
    private SigningKey person;
    private final ProcessCommandRunner runner = new ProcessCommandRunner();
    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();
    private String first;

    @BeforeEach
    void registry() throws Exception {
        repo = Files.createDirectory(dir.resolve("registry"));
        adminFile = keygen("admin");
        admin = key(adminFile);
        person = key(keygen("alice"));
        git("init", "-q", "-b", "main");
        git("config", "user.name", "Admin");
        git("config", "user.email", "admin@example.org");
        git("config", "gpg.format", "ssh");
        write("hosts/walk-9/card", host(admin));
        write("hosts/walk-9/access.yml", access());
        write("people/alice.card", card(person, null));
        first = commit(adminFile);
    }

    private Path keygen(String name) {
        final Path path = dir.resolve(name);
        runner.runOrFail(
                Command.of("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", path.toString()));
        return path;
    }

    private SigningKey key(Path file) throws Exception {
        return new SigningKey(
                Base64.getDecoder().decode(OpenSshPrivateKey.seedBase64(Files.readString(file))),
                "test");
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

    private void write(String path, String text) throws Exception {
        final Path file = repo.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private String commit(Path signer) {
        git("add", ".");
        git("-c", "user.signingkey=" + signer, "commit", "-q", "-S", "-m", "registry change");
        return git("rev-parse", "HEAD");
    }

    private static String pin(SigningKey key) {
        return PersonCard.fingerprint(PersonCard.keyLine(key));
    }

    private String host(SigningKey signer) {
        return HostCard.write(
                "walk-9",
                "walk-9.example.org",
                2201,
                PersonCard.keyLine(admin),
                3000,
                2222,
                8448,
                signer);
    }

    private String card(SigningKey own, SigningKey predecessor) {
        return PersonCard.write(
                "alice",
                "Alice",
                "",
                own,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2030, 1, 1),
                predecessor);
    }

    private String access() {
        return "admins:\n  alice: \""
                + pin(person)
                + "\"\nprojects:\n  - name: demo\n    admin: alice\n    access_repository: ssh://git@example.org/demo-access.git\n";
    }

    private Path bundle(String refs) {
        final Path bundle = dir.resolve("registry-" + System.nanoTime() + ".bundle");
        git("--no-replace-objects", "bundle", "create", bundle.toString(), refs);
        return bundle;
    }

    private int verify(Path source, String... extra) {
        out.getBuffer().setLength(0);
        err.getBuffer().setLength(0);
        final PoreloPaths paths =
                new PoreloPaths(
                        XdgPaths.of(name -> null, dir.resolve("no-home")), dir.resolve("bin"));
        final CommandLine cli = PoreloCli.commandLine(new PoreloContext(runner, paths, args -> 0));
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        final List<String> args =
                new ArrayList<>(
                        List.of(
                                "registry",
                                "verify",
                                "--repository",
                                (source.equals(repo) ? bundle("--all") : source).toString(),
                                "--host",
                                "walk-9",
                                "--pinned",
                                pin(admin),
                                "--json"));
        for (int at = 0; at < extra.length; at++) {
            final int existing = args.indexOf(extra[at]);
            if (existing >= 0 && at + 1 < extra.length && !extra[at].equals("--json")) {
                args.set(existing + 1, extra[++at]);
            } else {
                args.add(extra[at]);
            }
        }
        return cli.execute(args.toArray(String[]::new));
    }

    private Map<?, ?> answer() {
        return (Map<?, ?>) Json.parse(out.toString());
    }

    private Map<?, ?> personAnswer() {
        return (Map<?, ?>) ((List<?>) answer().get("persons")).getFirst();
    }

    /** An old signed side branch must not roll back a revocation or move the applied pin. */
    @Test
    void refuses_a_merge_whose_first_parent_does_not_continue_the_applied_line() throws Exception {
        write("hosts/walk-9/access.yml", "admins: {}\nprojects: []\n");
        commit(adminFile);
        git("checkout", "-q", "-b", "side");
        write("hosts/walk-9/access.yml", access());
        write("hosts/walk-9/card", host(key(keygen("side-admin"))));
        final String side = commit(adminFile);
        git("checkout", "-q", "main");
        write("hosts/walk-9/access.yml", access());
        commit(adminFile);
        write("hosts/walk-9/access.yml", "admins: {}\nprojects: []\n");
        final String applied = commit(adminFile);
        final String merge =
                git(
                        "commit-tree",
                        side + "^{tree}",
                        "-p",
                        side,
                        "-p",
                        applied,
                        "-m",
                        "reuse old signed grant");
        git("update-ref", "refs/heads/main", merge);
        assertThat(verify(bundle("--all"), "--since", applied))
                .as("ancestry through a second parent cannot restore access or move the pin")
                .isEqualTo(65);
        assertThat(err.toString())
                .as("both ends of the refused continuation")
                .contains(merge, applied);
    }

    /** Bundle-controlled refs must not choose the host's trusted applied state. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "deadbee",
                "refs/tags/applied",
                "main",
                "HEAD~1",
                "ABCDEF0123456789ABCDEF0123456789ABCDEF0123",
                "abc"
            })
    void since_accepts_only_a_full_lowercase_object_name(String since) throws Exception {
        write("hosts/walk-9/access.yml", access() + "\n");
        git("add", ".");
        git("commit", "-q", "--no-gpg-sign", "-m", "untrusted baseline");
        git("tag", "deadbee");
        git("tag", "applied");
        assertThat(verify(bundle("--all"), "--since", since))
                .as("the applied commit comes from trusted state, never a ref in the bundle")
                .isEqualTo(64);
    }

    /** A full object name still has to identify a commit, never a tag or blob. */
    @Test
    void since_refuses_objects_that_are_not_commits() {
        git("-c", "tag.gpgsign=false", "tag", "-a", "applied", "-m", "annotated tag");
        for (String object :
                List.of(
                        git("rev-parse", "refs/tags/applied"),
                        git("rev-parse", "HEAD:hosts/walk-9/card"))) {
            assertThat(verify(bundle("--all"), "--since", object))
                    .as("a tag or blob cannot stand in for the applied commit")
                    .isEqualTo(65);
            assertThat(err.toString())
                    .as("the refused object and head are identified")
                    .contains(object, first);
        }
    }

    /** Local repository config and helpers must never be reached through a disguised source. */
    @ParameterizedTest
    @ValueSource(strings = {"gitdir", "directory", "symlink", "header-prefix", "header-cr"})
    void refuses_nonbundles_before_running_any_git(String kind) throws Exception {
        final Path good = bundle("--all");
        final Path source =
                switch (kind) {
                    case "gitdir" ->
                            Files.writeString(
                                    dir.resolve("pointer"),
                                    "gitdir: " + repo.resolve(".git") + "\n");
                    case "directory" -> repo;
                    case "symlink" -> Files.createSymbolicLink(dir.resolve("link.bundle"), good);
                    case "header-prefix" ->
                            Files.writeString(
                                    dir.resolve("prefix.bundle"), "# v2 git bundle extra\n");
                    default -> Files.writeString(dir.resolve("cr.bundle"), "# v2 git bundle\r\n");
                };
        final List<Command> commands = new ArrayList<>();
        final RegistryVerifier verifier =
                new RegistryVerifier(
                        command -> {
                            commands.add(command);
                            return runner.run(command);
                        });
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> verifier.verify(source, "walk-9", pin(admin), ""))
                .as("a source must be a regular bundle before Git sees it")
                .isInstanceOf(java.io.IOException.class);
        assertThat(commands).as("no Git process can read the pointed-to repository").isEmpty();
        assertThat(verify(good, "--repository", source.toString()))
                .as("nonbundles are unreadable input")
                .isEqualTo(66);
    }

    /** Reopening a checked pathname must not let a local repository replace the brought bundle. */
    @Test
    void replacing_the_source_after_validation_cannot_change_the_verified_bundle()
            throws Exception {
        write("hosts/walk-9/access.yml", "admins: {}\nprojects: []\n");
        final String revoked = commit(adminFile);
        final Path source = bundle("--all");
        git("update-ref", "refs/heads/main", first);
        final var answer =
                new RegistryVerifier(
                                command -> {
                                    if (command.arguments().contains("clone")) {
                                        try {
                                            Files.writeString(
                                                    source,
                                                    "gitdir: " + repo.resolve(".git") + "\n");
                                        } catch (java.io.IOException ex) {
                                            throw new java.io.UncheckedIOException(ex);
                                        }
                                    }
                                    return runner.run(command);
                                })
                        .verify(source, "walk-9", pin(admin), "");
        assertThat(answer.get("commit"))
                .as("Git reads the checked bundle, never its replaced source path")
                .isEqualTo(revoked);
        assertThat(answer.get("admins"))
                .as("the replacement cannot roll back a brought revocation")
                .isEqualTo(Map.of());
    }

    /** An empty path must not silently select the process's current repository. */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void an_empty_repository_option_is_invalid(String source) {
        assertThat(verify(bundle("--all"), "--repository", source))
                .as("the caller must select an actual bundle")
                .isEqualTo(64);
    }

    /** Project entries are consumed as paths and Git addresses by the host. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "../x",
                "Demo",
                "duplicate-case",
                "duplicate-exact",
                "--upload-pack=evil",
                "ext::sh -c id",
                "helper::address",
                "file:///tmp/repo",
                "http://example.org/repo",
                "ssh://example.org/a b"
            })
    void refuses_project_paths_options_and_remote_helpers(String invalid) throws Exception {
        final String text =
                switch (invalid) {
                    case "../x", "Demo" -> access().replace("name: demo", "name: " + invalid);
                    case "duplicate-case" ->
                            access()
                                    + "  - name: Demo\n    admin: alice\n    access_repository: https://example.org/other.git\n";
                    case "duplicate-exact" ->
                            access()
                                    + "  - name: demo\n    admin: alice\n    access_repository: https://example.org/other.git\n";
                    default ->
                            access().replace(
                                            "ssh://git@example.org/demo-access.git",
                                            "'" + invalid + "'");
                };
        write("hosts/walk-9/access.yml", text);
        final String commit = commit(adminFile);
        assertThat(verify(bundle("--all")))
                .as("unsafe project data cannot reach account setup")
                .isEqualTo(65);
        assertThat(err.toString())
                .as("the rejected signed file is actionable")
                .contains(commit, "hosts/walk-9/access.yml");
    }

    /** Git's default branch choice must not apply an older signed registry. */
    @Test
    void answers_main_even_when_the_bundle_also_has_a_stale_master() throws Exception {
        git("branch", "master", first);
        write("hosts/walk-9/access.yml", "admins: {}\nprojects: []\n");
        final String main = commit(adminFile);
        git("checkout", "-q", "master");
        assertThat(verify(bundle("--branches"))).as(err.toString()).isZero();
        assertThat(answer().get("commit"))
                .as("only main selects the applied state")
                .isEqualTo(main);
    }

    @Test
    void refuses_a_bundle_with_master_alone() {
        git("branch", "-m", "master");
        assertThat(verify(bundle("--branches")))
                .as("a default branch cannot substitute for main")
                .isEqualTo(65);
        assertThat(err.toString())
                .as("the required branch is named")
                .contains("has no main branch");
    }

    /** A hostile filename must not execute terminal controls or conceal the refused path. */
    @ParameterizedTest
    @ValueSource(strings = {"\u009b", "\u202e", "\u0080", "\u009f", "\u2028", "\u2029"})
    void escapes_unicode_controls_in_refused_paths(String control) throws Exception {
        write("hosts/walk-9/before" + control + "after", "host change");
        git("add", ".");
        git("commit", "-q", "--no-gpg-sign", "-m", "hostile path");
        assertThat(verify(bundle("--all"))).as("the unsigned host change is refused").isEqualTo(65);
        assertThat(err.toString())
                .as("untrusted path text is safe for a terminal")
                .contains("before?after")
                .doesNotContain(control);
    }

    /**
     * Format and separator characters in an answered address must not spoof the consumer's display.
     */
    @ParameterizedTest
    @ValueSource(strings = {"\u202e", "\u200b"})
    void escapes_unicode_controls_in_json_values(String control) throws Exception {
        write(
                "hosts/walk-9/access.yml",
                access().replace(
                                "ssh://git@example.org/demo-access.git",
                                "\"ssh://git@example.org/before\\u%04xafter.git\""
                                        .formatted(control.codePointAt(0))));
        commit(adminFile);
        assertThat(verify(bundle("--all"))).as(err.toString()).isZero();
        final Map<?, ?> project = (Map<?, ?>) ((List<?>) answer().get("projects")).getFirst();
        assertThat(project.get("access_repository"))
                .as("JSON fields use the same safe text as diagnostics")
                .isEqualTo("ssh://git@example.org/before?after.git");
        assertThat(out.toString())
                .as("the output contains no hidden formatting")
                .doesNotContain(control);
    }

    /** A forged person's card must not supply a key or expiry to account management. */
    @Test
    void a_forged_person_card_answers_no_key_or_expiry() throws Exception {
        write("people/alice.card", card(person, null).replace("Alice", "Forged"));
        commit(adminFile);
        assertThat(verify(bundle("--all"))).as(err.toString()).isZero();
        assertThat(personAnswer())
                .as("unverified account fields are withheld")
                .isEqualTo(Map.of("login", "alice", "ssh", "", "expires", "", "revoked", true));
    }

    /** History simplification must not hide a legitimate key-change link on a merged branch. */
    @Test
    void finds_person_key_changes_on_a_treesame_merged_branch() throws Exception {
        final SigningKey middle = key(keygen("middle"));
        final SigningKey latest = key(keygen("latest"));
        git("checkout", "-q", "-b", "recertify");
        write("people/alice.card", card(middle, person));
        commit(adminFile);
        final String latestCard = card(latest, middle);
        write("people/alice.card", latestCard);
        final String side = commit(adminFile);
        git("checkout", "-q", "main");
        write("people/alice.card", latestCard);
        final String main = commit(adminFile);
        final String merge =
                git(
                        "commit-tree",
                        main + "^{tree}",
                        "-p",
                        main,
                        "-p",
                        side,
                        "-m",
                        "merge recertification history");
        git("update-ref", "refs/heads/main", merge);
        assertThat(verify(bundle("--all"))).as(err.toString()).isZero();
        assertThat(personAnswer().get("revoked"))
                .as("the full history includes the intermediate card despite a treesame merge")
                .isEqualTo(false);
    }

    /** Replacement refs must not substitute an older signed tree for a later revocation. */
    @Test
    void a_bundle_replacement_ref_cannot_swap_the_signed_registry_tree() throws Exception {
        write("hosts/walk-9/access.yml", "admins: {}\nprojects: []\n");
        final String revoked = commit(adminFile);
        git("update-ref", "refs/replace/" + revoked, first);
        final Path source = bundle("--all");
        assertThat(git("bundle", "list-heads", source.toString()))
                .as("the hostile replacement really is carried in the bundle")
                .contains("refs/replace/" + revoked);
        final List<String> installedReplacements = new ArrayList<>();
        final CommandRunner inspecting =
                command -> {
                    final var result = runner.run(command);
                    if (result.successful() && command.arguments().contains("clone")) {
                        installedReplacements.add(
                                runner.runOrFail(
                                                Command.of(
                                                                "git",
                                                                "--git-dir="
                                                                        + command.workingDirectory()
                                                                                .resolve(
                                                                                        "registry.git"),
                                                                "for-each-ref",
                                                                "--format=%(refname)",
                                                                "refs/replace/")
                                                        .withChosenEnvironment(
                                                                Map.of(
                                                                        "PATH",
                                                                        System.getenv("PATH"),
                                                                        "GIT_CONFIG_GLOBAL",
                                                                        "/dev/null",
                                                                        "GIT_CONFIG_NOSYSTEM",
                                                                        "1")))
                                        .standardOutput());
                    }
                    return result;
                };
        final var state = new RegistryVerifier(inspecting).verify(source, "walk-9", pin(admin), "");
        assertThat(installedReplacements)
                .as("bundle replacements are not installed in the bare clone")
                .containsExactly("");
        assertThat(state.get("commit")).as("the later commit remains selected").isEqualTo(revoked);
        assertThat(state.get("admins"))
                .as("a signed old grant cannot replace a revocation")
                .isEqualTo(Map.of());
    }

    /** Untrusted submodule metadata and hook-shaped files must never run during verification. */
    @Test
    void does_not_check_out_submodules_or_run_hooks_carried_as_registry_files() throws Exception {
        final Path marker = dir.resolve("executed");
        write(
                ".gitmodules",
                "[submodule \"payload\"]\n  path = payload\n  url = ext::sh -c 'touch "
                        + marker
                        + "'\n");
        git("update-index", "--add", "--cacheinfo", "160000," + first + ",payload");
        write("hooks/reference-transaction", "#!/bin/sh\ntouch '" + marker + "'\n");
        repo.resolve("hooks/reference-transaction").toFile().setExecutable(true);
        commit(adminFile);
        assertThat(verify(bundle("--all"))).as(err.toString()).isZero();
        assertThat(marker).as("no content from the bundle is executed").doesNotExist();
    }

    /** Repeated recertification must not make synchronization read unbounded card history. */
    @Test
    void more_than_the_person_chain_cap_is_revoked_without_reading_all_changes() throws Exception {
        for (int at = 1; at <= 65; at++) {
            write(
                    "people/alice.card",
                    PersonCard.write(
                            "alice",
                            "Alice " + at,
                            "",
                            person,
                            LocalDate.of(2026, 1, 1),
                            LocalDate.of(2030, 1, 1),
                            null));
            git("add", ".");
            git("commit", "-q", "--no-gpg-sign", "-m", "recertification " + at);
        }
        final List<Command> reads = new ArrayList<>();
        final List<Long> historySizes = new ArrayList<>();
        final var answer =
                new RegistryVerifier(
                                command -> {
                                    if (command.arguments().contains("ls-tree")
                                            && command.arguments().contains("people/alice.card")) {
                                        reads.add(command);
                                    }
                                    final var result = runner.run(command);
                                    if (command.arguments().contains("rev-list")
                                            && command.arguments().contains("people/alice.card")) {
                                        historySizes.add(result.standardOutput().lines().count());
                                    }
                                    return result;
                                })
                        .verify(bundle("--all"), "walk-9", pin(admin), "");
        final Map<?, ?> person = (Map<?, ?>) ((List<?>) answer.get("persons")).getFirst();
        assertThat(historySizes)
                .as("the history query itself is bounded")
                .singleElement()
                .satisfies(
                        size ->
                                assertThat(size)
                                        .as("at most the cap and one sentinel")
                                        .isLessThanOrEqualTo(65L));
        assertThat(reads)
                .as("the current card and at most 64 history changes are read")
                .hasSizeLessThanOrEqualTo(65);
        assertThat(person)
                .as("over-limit history cannot grant an account")
                .isEqualTo(Map.of("login", "alice", "ssh", "", "expires", "", "revoked", true));
    }

    /** A bad host-card signature must identify the commit and path on every validation path. */
    @ParameterizedTest
    @ValueSource(strings = {"rotation", "applied"})
    void names_commit_and_path_for_host_card_signature_failures(String stage) throws Exception {
        write("hosts/walk-9/card", host(admin).replace("2201", "2202"));
        final String broken = commit(adminFile);
        final String[] options =
                stage.equals("applied") ? new String[] {"--since", broken} : new String[] {};
        assertThat(verify(bundle("--all"), options))
                .as("a changed card body has no valid signature")
                .isEqualTo(65);
        assertThat(err.toString())
                .as("the invalid card can be located")
                .contains(broken, "hosts/walk-9/card");
    }

    @Test
    void answers_the_pinned_hosts_bundle_and_only_the_admins_it_names() throws Exception {
        write("owners.yml", "not: [valid");
        write("people/unlisted.card", "not a card");
        write("hosts/elsewhere/access.yml", "not: [valid");
        git("add", ".");
        git("commit", "-q", "--no-gpg-sign", "-m", "unrelated unsigned paths");
        final String head = git("rev-parse", "HEAD");
        assertThat(verify(bundle("--all"))).as(err.toString()).isZero();
        assertThat(new ArrayList<>(answer().keySet()))
                .as("the sync contract's exact fields")
                .isEqualTo(List.of("commit", "host_card", "admins", "projects", "persons"));
        assertThat(answer().get("commit")).as("the checked head").isEqualTo(head);
        assertThat(answer().get("host_card"))
                .as("every verified host field crosses the boundary")
                .isEqualTo(
                        Map.of(
                                "host",
                                "walk-9",
                                "address",
                                "walk-9.example.org",
                                "ssh_port",
                                2201.0,
                                "ssh_host_key",
                                PersonCard.keyLine(admin),
                                "services",
                                Map.of(
                                        "forge",
                                        Map.of("web_port", 3000.0, "git_port", 2222.0),
                                        "matrix",
                                        Map.of("port", 8448.0)),
                                "signed_by",
                                pin(admin)));
        assertThat(answer().get("admins"))
                .as("only the host's pinned admins")
                .isEqualTo(Map.of("alice", pin(person)));
        assertThat(answer().get("projects"))
                .as("the access repository is carried, never fetched")
                .isEqualTo(
                        List.of(
                                Map.of(
                                        "name",
                                        "demo",
                                        "admin",
                                        "alice",
                                        "access_repository",
                                        "ssh://git@example.org/demo-access.git")));
        assertThat(personAnswer())
                .as("the pinned person's account key")
                .isEqualTo(
                        Map.of(
                                "login",
                                "alice",
                                "ssh",
                                PersonCard.keyLine(person),
                                "expires",
                                "2030-01-01",
                                "revoked",
                                false));
        assertThat(dir.resolve("no-home"))
                .as("verification creates no vault or home")
                .doesNotExist();
    }

    @Test
    void accepts_a_bundle_of_main() {
        assertThat(verify(bundle("main"))).as(err.toString()).isZero();
    }

    @Test
    void answers_with_home_unset_no_vault_and_network_protocols_disabled() throws Exception {
        write(".gitmodules", "[submodule \"payload\"]\n  path = payload\n  url = ext::sh -c id\n");
        write("hooks/reference-transaction", "#!/bin/sh\nexit 99\n");
        final String head = commit(adminFile);
        git("update-ref", "refs/replace/" + head, first);
        final Path source = bundle("--all");
        assertThat(verify(source)).as(err.toString()).isZero();
        final String expected = out.toString();
        final var result =
                runner.run(
                        Command.of(
                                        Path.of(System.getProperty("java.home"), "bin", "java")
                                                .toString(),
                                        "-Duser.home=" + dir.resolve("no-home"),
                                        "-cp",
                                        System.getProperty("java.class.path"),
                                        PoreloCli.class.getName(),
                                        "registry",
                                        "verify",
                                        "--repository",
                                        source.toString(),
                                        "--host",
                                        "walk-9",
                                        "--pinned",
                                        pin(admin),
                                        "--json")
                                .withChosenEnvironment(
                                        Map.of(
                                                "PATH",
                                                System.getenv("PATH"),
                                                "GIT_ALLOW_PROTOCOL",
                                                "file",
                                                "GIT_CONFIG_GLOBAL",
                                                "/dev/null"))
                                .withEnvironment(
                                        Map.of(
                                                "GIT_DIR",
                                                        dir.resolve("not-a-repository").toString(),
                                                "GIT_EXEC_PATH",
                                                        dir.resolve("no-helpers").toString(),
                                                "GIT_OBJECT_DIRECTORY",
                                                        dir.resolve("not-objects").toString(),
                                                "GIT_CONFIG_PARAMETERS", "not a configuration",
                                                "GIT_CONFIG_COUNT", "1",
                                                "GIT_CONFIG_KEY_0", "protocol.file.allow",
                                                "GIT_CONFIG_VALUE_0", "never")));
        assertThat(result.exitCode()).as(result.standardError()).isZero();
        assertThat(Json.parse(result.standardOutput()))
                .as("the same verified state without HOME or a vault")
                .isEqualTo(Json.parse(expected));
        assertThat(dir.resolve("no-home")).as("no fallback home was created").doesNotExist();
    }

    @Test
    void refuses_a_host_access_change_signed_by_another_key_and_names_commit_and_path()
            throws Exception {
        write("hosts/walk-9/access.yml", access() + "\n");
        final String bad = commit(keygen("stranger"));
        assertThat(verify(bundle("--all"))).as("another signer cannot grant access").isEqualTo(65);
        assertThat(err.toString())
                .as("the operator can locate the refused change")
                .contains(bad, "hosts/walk-9/access.yml");
        assertThat(out.toString()).as("no partially trusted answer").isEmpty();
    }

    /** A signer using another key algorithm still needs an actionable refusal. */
    @Test
    void names_commit_and_path_for_a_host_change_signed_with_another_algorithm() throws Exception {
        final Path stranger = dir.resolve("stranger-ecdsa");
        runner.runOrFail(
                Command.of("ssh-keygen", "-q", "-t", "ecdsa", "-N", "", "-f", stranger.toString()));
        write("hosts/walk-9/access.yml", access() + "\n");
        final String bad = commit(stranger);
        assertThat(verify(repo)).as("another algorithm cannot bypass the host pin").isEqualTo(65);
        assertThat(err.toString())
                .as("the refused change is identified regardless of key type")
                .contains(bad, "hosts/walk-9/access.yml");
    }

    @Test
    void refuses_an_unsigned_host_change_even_when_a_later_commit_is_signed() throws Exception {
        write("hosts/walk-9/access.yml", access() + "\n");
        git("add", ".");
        git("commit", "-q", "--no-gpg-sign", "-m", "unsigned");
        final String bad = git("rev-parse", "HEAD");
        write("hosts/walk-9/note", "a signed later change");
        commit(adminFile);
        assertThat(verify(repo)).as("all host changes need signatures").isEqualTo(65);
        assertThat(err.toString())
                .as("the unsigned path is actionable")
                .contains(bad, "hosts/walk-9/access.yml");
    }

    @Test
    void moves_the_host_pin_only_after_an_old_signed_commit_introduces_the_new_card()
            throws Exception {
        final Path next = keygen("next");
        final SigningKey nextKey = key(next);
        write("hosts/walk-9/card", host(nextKey));
        commit(adminFile);
        write("hosts/walk-9/access.yml", access() + "\n");
        commit(next);
        assertThat(verify(bundle("--all"))).as(err.toString()).isZero();
        assertThat(((Map<?, ?>) answer().get("host_card")).get("signed_by"))
                .as("sync advances its saved pin")
                .isEqualTo(pin(nextKey));
    }

    @Test
    void a_new_key_cannot_authorize_its_own_host_rotation() throws Exception {
        final Path next = keygen("next");
        write("hosts/walk-9/card", host(key(next)));
        final String bad = commit(next);
        assertThat(verify(repo))
                .as("possession of a new key is not the old admin's consent")
                .isEqualTo(65);
        assertThat(err.toString())
                .as("the attempted takeover is named")
                .contains(bad, "hosts/walk-9/card");
    }

    @Test
    void a_persons_unpinned_key_is_revoked_without_refusing_the_registry() throws Exception {
        final SigningKey stranger = key(keygen("stranger"));
        write("people/alice.card", card(stranger, null));
        commit(adminFile);
        assertThat(verify(repo)).as(err.toString()).isZero();
        assertThat(personAnswer().get("revoked"))
                .as("a stranger's key never grants an account")
                .isEqualTo(true);
    }

    @Test
    void walks_a_persons_key_changes_through_earlier_cards_in_history() throws Exception {
        final SigningKey middle = key(keygen("middle"));
        final SigningKey latest = key(keygen("latest"));
        write("people/alice.card", card(middle, person));
        commit(adminFile);
        write("people/alice.card", card(latest, middle));
        commit(adminFile);
        assertThat(verify(bundle("--all"))).as(err.toString()).isZero();
        assertThat(personAnswer().get("revoked"))
                .as("a dual-signed chain reaches the pin")
                .isEqualTo(false);
        assertThat(personAnswer().get("ssh"))
                .as("the current key is answered")
                .isEqualTo(PersonCard.keyLine(latest));
    }

    @Test
    void a_missing_person_remains_in_the_answer_as_revoked() {
        git("rm", "people/alice.card");
        commit(adminFile);
        assertThat(verify(repo)).as(err.toString()).isZero();
        assertThat(personAnswer())
                .as("absence removes access rather than silently keeping it")
                .isEqualTo(Map.of("login", "alice", "ssh", "", "expires", "", "revoked", true));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "owners: {}",
                "scalar-anchor",
                "scalar-tag",
                "alias",
                "merge-scalar",
                "admins: {}\nprojects: []\nunknown: true",
                "admins: &a {}\nprojects: []",
                "admins: !!map {}\nprojects: []",
                "admins: {}\nadmins: {}\nprojects: []",
                "admins: {}\nprojects: [{name: demo, admin: '<<', access_repository: ssh://example.org/a}]",
                "admins: {alice: bad}\nprojects: []",
                "admins: {}\nprojects: [{name: demo, admin: alice, access_repository: ssh://example.org/a}]",
                "admins: {}\nprojects: []\n---\nadmins: {}"
            })
    void malformed_access_cannot_change_accounts(String malformed) throws Exception {
        final String input = malformed;
        malformed =
                switch (malformed) {
                    case "scalar-anchor" -> access().replace("name: demo", "name: &unused demo");
                    case "scalar-tag" -> access().replace("name: demo", "name: !!str demo");
                    case "alias" -> access().replace("name: demo", "name: *undefined");
                    case "merge-scalar" ->
                            access().replace("ssh://git@example.org/demo-access.git", "'<<'");
                    default -> malformed;
                };
        write("hosts/walk-9/access.yml", malformed);
        commit(adminFile);
        assertThat(verify(repo)).as("malformed signed access still fails closed").isEqualTo(65);
        assertThat(err.toString())
                .as("the refused input is named")
                .contains("hosts/walk-9/access.yml");
        if (List.of("alias", "scalar-anchor", "scalar-tag").contains(input)) {
            assertThat(err.toString())
                    .as("the prohibited YAML event is refused explicitly")
                    .contains("anchors, aliases and tags are not allowed");
        }
        if (input.equals("merge-scalar")) {
            assertThat(err.toString())
                    .as("a merge scalar cannot be reinterpreted downstream")
                    .contains("merge keys");
        }
    }

    @Test
    void since_accepts_an_ancestor_and_the_head() throws Exception {
        write("hosts/walk-9/access.yml", access() + "\n");
        final String head = commit(adminFile);
        assertThat(verify(bundle("--all"), "--since", first)).as(err.toString()).isZero();
        assertThat(verify(repo, "--since", head)).as(err.toString()).isZero();
    }

    @Test
    void since_refuses_a_fork_and_a_missing_commit_naming_both() throws Exception {
        git("checkout", "-q", "-b", "fork");
        write("unrelated", "fork");
        final String fork = commit(adminFile);
        git("checkout", "-q", "main");
        assertThat(verify(bundle("--all"), "--since", fork))
                .as("a fork cannot undo a revocation")
                .isEqualTo(65);
        assertThat(err.toString()).as("both ends of the rejected ancestry").contains(first, fork);
        final String missing = "a".repeat(40);
        assertThat(verify(repo, "--since", missing))
                .as("missing history cannot prove ancestry")
                .isEqualTo(65);
        assertThat(err.toString())
                .as("the absent commit and current head")
                .contains(first, missing);
    }

    /** A real signature copied onto changed commit bytes must never authorize the changed tree. */
    @Test
    void refuses_a_forged_commit_even_when_its_signature_names_the_pinned_key() {
        final String raw = git("cat-file", "commit", first) + "\nchanged after signing\n";
        final String forged =
                runner.runOrFail(
                                Command.of("git", "hash-object", "-t", "commit", "-w", "--stdin")
                                        .in(repo)
                                        .withInput(raw)
                                        .withEnvironment(Map.of("GIT_CONFIG_GLOBAL", "/dev/null")))
                        .trimmedOutput();
        git("update-ref", "refs/heads/main", forged);
        assertThat(verify(repo))
                .as("the key in an armor header is not evidence of a valid signature")
                .isEqualTo(65);
        assertThat(err.toString())
                .as("the forged commit and its host path are named")
                .contains(forged, "hosts/walk-9/");
    }

    /**
     * A signed merge, rather than its untrusted side branch, authorizes the resulting host tree.
     */
    @Test
    void judges_a_merge_against_its_first_parent() throws Exception {
        git("checkout", "-q", "-b", "incoming");
        write("hosts/walk-9/access.yml", access() + "\n");
        git("add", ".");
        git("commit", "-q", "--no-gpg-sign", "-m", "unsigned side branch");
        git("checkout", "-q", "main");
        git(
                "-c",
                "user.signingkey=" + adminFile,
                "merge",
                "--no-ff",
                "-S",
                "incoming",
                "-m",
                "approved merge");
        assertThat(verify(repo))
                .as("a signed first-parent change authorizes the merged tree")
                .isZero();
    }

    /** A merge must not bypass authorization just because diff-tree omits merges by default. */
    @Test
    void refuses_an_unsigned_merge_that_changes_host_files() throws Exception {
        git("checkout", "-q", "-b", "incoming");
        write("hosts/walk-9/access.yml", access() + "\n");
        commit(adminFile);
        git("checkout", "-q", "main");
        git("merge", "--no-ff", "--no-gpg-sign", "incoming", "-m", "unapproved merge");
        final String bad = git("rev-parse", "HEAD");
        assertThat(verify(repo))
                .as("a signed side branch cannot authorize an unsigned merge")
                .isEqualTo(65);
        assertThat(err.toString())
                .as("the merge and changed file are named")
                .contains(bad, "hosts/walk-9/access.yml");
    }

    /** Ancestry through a discarded second parent must not restore revoked access unsigned. */
    @Test
    void refuses_an_unsigned_merge_that_discards_a_revocation_on_its_second_parent()
            throws Exception {
        write("hosts/walk-9/access.yml", "admins: {}\nprojects: []\n");
        final String revoked = commit(adminFile);
        final String restored =
                git(
                        "commit-tree",
                        first + "^{tree}",
                        "-p",
                        first,
                        "-p",
                        revoked,
                        "-m",
                        "discard the applied revocation");
        git("update-ref", "refs/heads/main", restored);
        assertThat(verify(repo, "--since", revoked))
                .as("a second-parent ancestry link cannot authorize restoring access")
                .isEqualTo(65);
        assertThat(err.toString())
                .as("the rollback commit and the discarded host change are named")
                .contains(restored, revoked);
    }

    /** Matching both merge parents must not launder an unsigned rollback on a side branch. */
    @Test
    void refuses_a_revocation_rollback_laundered_through_a_side_branch() throws Exception {
        write("hosts/walk-9/access.yml", "admins: {}\nprojects: []\n");
        final String revoked = commit(adminFile);
        final String undo =
                git("commit-tree", first + "^{tree}", "-p", revoked, "-m", "unsigned rollback");
        final String merge =
                git(
                        "commit-tree",
                        first + "^{tree}",
                        "-p",
                        first,
                        "-p",
                        undo,
                        "-m",
                        "launder rollback");
        git("update-ref", "refs/heads/main", merge);
        assertThat(verify(repo, "--since", revoked))
                .as("matching immediate parents cannot replace the applied host snapshot")
                .isEqualTo(65);
        assertThat(err.toString())
                .as("the importing commit and changed host file are named")
                .contains(merge, revoked);
    }

    /** An old key cannot reclaim authority by putting an applied rotation on another parent. */
    @Test
    void refuses_an_old_key_rollback_of_a_rotation_on_a_second_parent() throws Exception {
        final SigningKey nextKey = key(keygen("next"));
        write("hosts/walk-9/card", host(nextKey));
        final String rotated = commit(adminFile);
        final String merge =
                git(
                        "-c",
                        "user.signingkey=" + adminFile,
                        "commit-tree",
                        first + "^{tree}",
                        "-S",
                        "-p",
                        first,
                        "-p",
                        rotated,
                        "-m",
                        "old key reclaims host");
        git("update-ref", "refs/heads/main", merge);
        assertThat(verify(repo, "--pinned", pin(nextKey), "--since", rotated))
                .as("the applied rotation fixes the key in force before continuation")
                .isEqualTo(65);
        assertThat(err.toString())
                .as("the attempted rollback and host card are named")
                .contains(merge, rotated);
    }

    /** Persisting the answered host pin must not prevent the next synchronization. */
    @Test
    void a_saved_rotated_pin_can_verify_the_previously_applied_head() throws Exception {
        final Path next = keygen("next");
        final SigningKey nextKey = key(next);
        write("hosts/walk-9/card", host(nextKey));
        final String rotated = commit(adminFile);
        assertThat(verify(repo)).as("the original pin accepts the authorized rotation").isZero();
        final String savedPin = (String) ((Map<?, ?>) answer().get("host_card")).get("signed_by");
        assertThat(verify(repo, "--pinned", savedPin, "--since", rotated))
                .as("the host can use the pin and commit the previous answer told it to save")
                .isZero();
        write("hosts/walk-9/access.yml", access() + "\n");
        final String head = commit(next);
        assertThat(verify(bundle("--all"), "--pinned", savedPin, "--since", rotated))
                .as("the saved pin authorizes later changes by the new key")
                .isZero();
        assertThat(answer().get("commit"))
                .as("the next applied commit is answered")
                .isEqualTo(head);
    }

    /** A first bring must not discover a supplied key later in untrusted history. */
    @Test
    void a_rotated_pin_without_since_refuses_the_first_old_key_commit() throws Exception {
        final SigningKey nextKey = key(keygen("next"));
        write("hosts/walk-9/card", host(nextKey));
        commit(adminFile);
        assertThat(verify(repo, "--pinned", pin(nextKey)))
                .as("without trusted ground the pin must authorize the first host change")
                .isEqualTo(65);
        assertThat(err.toString())
                .as("the first old-key commit and host path are named")
                .contains(first, "hosts/walk-9/");
    }

    /** A future key cannot stand in for the key in force at the applied snapshot. */
    @Test
    void a_rotated_pin_cannot_be_used_since_a_commit_before_its_rotation() throws Exception {
        final SigningKey nextKey = key(keygen("next"));
        write("hosts/walk-9/card", host(nextKey));
        commit(adminFile);
        assertThat(verify(repo, "--pinned", pin(nextKey), "--since", first))
                .as("the saved pin must belong to the supplied applied commit")
                .isEqualTo(65);
        assertThat(err.toString())
                .as("the mismatched applied snapshot and card are named")
                .contains(first, "hosts/walk-9/card");
    }

    /** Reading host state through a symlink could import content outside the signed tree. */
    @Test
    void refuses_a_host_file_that_is_a_symbolic_link() throws Exception {
        final Path access = repo.resolve("hosts/walk-9/access.yml");
        Files.delete(access);
        Files.createSymbolicLink(access, dir.resolve("outside.yml"));
        commit(adminFile);
        assertThat(verify(repo)).as("host state must be a regular blob").isEqualTo(65);
        assertThat(err.toString())
                .as("the refused symlink is named")
                .contains("hosts/walk-9/access.yml");
    }

    /** The bundle's branch must be known, rather than guessing from any object it contains. */
    @Test
    void refuses_a_bundle_without_a_head_or_main_branch() {
        git("tag", "only-tag");
        assertThat(verify(bundle("--tags")))
                .as("a tag-only bundle has no registry head")
                .isEqualTo(65);
        assertThat(err.toString())
                .as("the missing head is explained")
                .contains("has no main branch");
    }

    /** A shallow boundary can hide an unauthorized earlier edit from the first-parent audit. */
    @Test
    void refuses_a_bundle_with_incomplete_history_that_hides_an_unauthorized_host_change()
            throws Exception {
        write("hosts/walk-9/access.yml", access() + "\n");
        commit(keygen("stranger"));
        write("hosts/walk-9/note", "an unrelated host change signed by the real admin");
        final String head = commit(adminFile);
        Files.writeString(repo.resolve(".git/shallow"), head + "\n");
        assertThat(verify(repo))
                .as("a truncated bundle cannot be cloned as a complete registry")
                .isEqualTo(66);
        assertThat(err.toString())
                .as("the unreadable bundle is explained")
                .contains("cannot read registry");
    }

    /** Neither a failed verification nor a successful one may leave an untrusted clone behind. */
    @Test
    void removes_its_own_clone_after_success_and_refusal() throws Exception {
        final List<Path> scratch = new ArrayList<>();
        final RegistryVerifier verifier =
                new RegistryVerifier(
                        command -> {
                            if (command.arguments().contains("clone")) {
                                scratch.add(command.workingDirectory());
                            }
                            return runner.run(command);
                        });
        verifier.verify(bundle("--all"), "walk-9", pin(admin), "");
        write("hosts/walk-9/access.yml", "unknown: true\n");
        commit(adminFile);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> verifier.verify(bundle("--all"), "walk-9", pin(admin), ""))
                .as("malformed access fails after the clone is read")
                .isInstanceOf(org.fuin.porelo.vault.CardException.class);
        assertThat(scratch).as("both verification attempts made their own clone").hasSize(2);
        for (Path path : scratch) {
            assertThat(path)
                    .as("the owned scratch directory is removed on every exit")
                    .doesNotExist();
        }
    }

    @Test
    void unreadable_bundles_and_bad_options_have_distinct_exit_codes() throws Exception {
        assertThat(verify(dir.resolve("missing.bundle"))).as("unreadable source").isEqualTo(66);
        final Path bad = Files.writeString(dir.resolve("bad.bundle"), "not a bundle");
        assertThat(verify(bad)).as("unreadable bundle").isEqualTo(66);
        assertThat(verify(repo, "--pinned", "bad")).as("bad fingerprint option").isEqualTo(64);
        assertThat(verify(repo, "--host", "../walk-9"))
                .as("host cannot escape its path")
                .isEqualTo(64);
        assertThat(verify(repo, "--since", "--all"))
                .as("commit cannot inject options")
                .isEqualTo(64);
    }
}
