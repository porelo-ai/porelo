package org.fuin.porelo.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.organization.IdentitySource;
import org.fuin.porelo.vault.HostCard;
import org.fuin.porelo.vault.OpenSshPrivateKey;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.SigningKey;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;

/**
 * A person's registry checkout for the registry verbs' tests: a real Git repository with an origin,
 * throwaway Ed25519 keys, SSH-signed commits, a forge that answers what a test sets, and a real
 * ssh-agent when a test starts one.
 */
final class RegistryCheckout implements AutoCloseable {

    static final String ORIGIN = "https://forge.example.org/acme/registry.git";

    final Path dir;
    final Path repo;
    final ProcessCommandRunner runner = new ProcessCommandRunner();
    final Map<String, Path> keyFiles = new HashMap<>();
    final Map<String, SigningKey> keys = new HashMap<>();
    final Forge forge = new Forge();
    final StringWriter out = new StringWriter();
    final StringWriter err = new StringWriter();
    final PoreloPaths paths;
    private @Nullable Process agent;

    /** What {@code registry file} runs between its read and its write. */
    Runnable afterRead = () -> {};

    /** What {@code registry file} runs just before it moves HEAD. */
    Runnable beforeUpdate = () -> {};

    private @Nullable Path socket;

    /**
     * A forge that answers what a test sets, "could not be asked" otherwise, and notes the asks.
     */
    static final class Forge implements IdentitySource {
        final Map<String, List<String>> keys = new HashMap<>();
        final Map<String, Boolean> owners = new HashMap<>();
        final List<String> asked = new ArrayList<>();

        /** What each opening of the forge was given: its address and token. */
        final List<String> opened = new ArrayList<>();

        @Override
        public Optional<Keys> keys(String login) {
            asked.add("keys " + login);
            return Optional.ofNullable(keys.get(login)).map(listed -> new Keys(listed, true));
        }

        @Override
        public Optional<Boolean> owns(String organisation, String login) {
            asked.add("owns " + organisation + " " + login);
            return Optional.ofNullable(owners.get(login));
        }
    }

    RegistryCheckout(Path dir) throws IOException {
        this.dir = dir;
        repo = Files.createDirectory(dir.resolve("registry"));
        for (String login : List.of("michael", "alice", "alice-new", "bob", "hank")) {
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
        git("remote", "add", "origin", ORIGIN);
        paths =
                new PoreloPaths(
                        XdgPaths.of(
                                name ->
                                        switch (name) {
                                            case "XDG_CONFIG_HOME" ->
                                                    dir.resolve("config").toString();
                                            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                            case "XDG_STATE_HOME" ->
                                                    dir.resolve("state").toString();
                                            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                            default -> null;
                                        },
                                dir.resolve("home")),
                        dir.resolve("bin"));
    }

    String git(String... args) {
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

    void write(String path, String text) throws IOException {
        final Path file = repo.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    /** Commits everything, signed by this login's key file. */
    String commit(String signer) {
        git("add", "-A");
        git(
                "-c",
                "user.signingkey=" + keyFiles.get(signer),
                "commit",
                "-q",
                "--allow-empty",
                "-S",
                "-m",
                "registry change");
        return head();
    }

    /** Commits nothing, with this message, signed by this login's key file. */
    String commit(String signer, String message) {
        git(
                "-c",
                "user.signingkey=" + keyFiles.get(signer),
                "commit",
                "-q",
                "--allow-empty",
                "-S",
                "-m",
                message);
        return head();
    }

    String head() {
        return git("rev-parse", "HEAD");
    }

    String key(String login) {
        return PersonCard.keyLine(keys.get(login));
    }

    String pin(String login) {
        return PersonCard.fingerprint(key(login));
    }

    /** michael owns the registry, which the forge confirms; hank administers wharf6. */
    String founded() throws IOException {
        forge.owners.put("michael", true);
        forge.keys.put("michael", List.of(key("michael")));
        write("owners.yml", owners());
        return commit("michael");
    }

    /**
     * What every read of the owners file below warns of: neither michael nor hank has a backup key.
     */
    List<String> soleWarnings() {
        return List.of(
                "owners.yml names michael as the registry's only owner, without a backup key; make"
                        + " one with porelo identity backup-key --registry",
                "owners.yml names hank as the only admin of wharf6, without a backup key; make one"
                        + " with porelo identity backup-key --host wharf6");
    }

    /** The owners file naming michael as owner and hank as wharf6's admin. */
    String owners() {
        return "owners:\n  - login: michael\n    key: \""
                + key("michael")
                + "\"\nhost_admins:\n  wharf6:\n    - login: hank\n      key: \""
                + key("hank")
                + "\"\n";
    }

    String card(String login, String own) {
        return card(login, own, null);
    }

    /** A card of this login with this key, handed on from the predecessor's key when given. */
    String card(String login, String own, @Nullable String predecessor) {
        return PersonCard.write(
                login,
                "Alice",
                "",
                keys.get(own),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2030, 1, 1),
                predecessor == null ? null : keys.get(predecessor));
    }

    String hostCard(String host, String signer) {
        return HostCard.write(
                host, host + ".example.org", 2201, key("hank"), 3000, 2222, 8448, keys.get(signer));
    }

    String access(String project) {
        return "admins:\n  alice: \""
                + pin("alice")
                + "\"\nprojects:\n  - name: "
                + project
                + "\n    admin: alice\n    access_repository: ssh://git@example.org/"
                + project
                + ".git\n";
    }

    /** Starts a real ssh-agent holding these logins' keys. */
    void agent(String... logins) throws Exception {
        final Path where = dir.resolve("agent.sock");
        // An agent stopped before leaves its socket behind.
        Files.deleteIfExists(where);
        agent =
                new ProcessBuilder("ssh-agent", "-D", "-a", where.toString())
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
        for (int tries = 0; tries < 100 && !Files.exists(where); tries++) {
            Thread.sleep(50);
        }
        socket = where;
        for (String login : logins) {
            runner.runOrFail(
                    Command.of("ssh-add", "-q", keyFiles.get(login).toString())
                            .withEnvironment(Map.of("SSH_AUTH_SOCK", where.toString())));
        }
    }

    /** Variables a test sets for the command, beside the agent. */
    final Map<String, String> variables = new java.util.HashMap<>();

    /**
     * What the command reads from the environment: the agent the test started and the variables.
     */
    UnaryOperator<String> environment() {
        final Path held = socket;
        return name ->
                name.equals("SSH_AUTH_SOCK") && held != null
                        ? held.toString()
                        : variables.get(name);
    }

    /** Whether the context's standard input is a terminal a person types into. */
    boolean terminal;

    PoreloContext context() {
        return new PoreloContext(
                runner,
                paths,
                args -> 0,
                environment(),
                false,
                VaultPrompt.NOBODY,
                new StandardInput(java.io.InputStream.nullInputStream(), terminal));
    }

    /** The machine, running what it would run on the person's terminal through this instead. */
    PoreloContext context(java.util.function.ToIntFunction<List<String>> terminal) {
        return new PoreloContext(runner, paths, terminal, environment());
    }

    /** Runs {@code porelo registry ...} with the test's forge in place of the real one. */
    int registry(String... args) {
        return registry(context(), args);
    }

    int registry(PoreloContext context, String... args) {
        out.getBuffer().setLength(0);
        err.getBuffer().setLength(0);
        final CommandLine cli = PoreloCli.commandLine(context);
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        final RegistryReadCommand.Forges forges =
                (address, token) -> {
                    forge.opened.add(address + " " + token);
                    return forge;
                };
        final var verbs = cli.getSubcommands().get("registry").getSubcommands();
        ((RegistryReadCommand) verbs.get("read").getCommand()).forges = forges;
        ((RegistryJoinableCommand) verbs.get("joinable").getCommand()).forges = forges;
        final RegistryFileCommand file = (RegistryFileCommand) verbs.get("file").getCommand();
        file.forges = forges;
        file.afterRead = afterRead;
        file.beforeUpdate = beforeUpdate;
        final List<String> all = new ArrayList<>(List.of("registry"));
        all.addAll(List.of(args));
        return cli.execute(all.toArray(String[]::new));
    }

    Map<?, ?> answer() {
        return (Map<?, ?>) Json.parse(out.toString());
    }

    Path record() {
        return RegistryReadCommand.trustRecord(context(), ORIGIN);
    }

    @Override
    public void close() throws InterruptedException {
        if (agent != null) {
            agent.destroy();
            agent.waitFor();
        }
    }
}
