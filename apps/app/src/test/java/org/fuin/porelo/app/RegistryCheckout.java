package org.fuin.porelo.app;

import java.io.IOException;
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
import org.fuin.porelo.vault.OpenSshPrivateKey;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.SigningKey;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * A person's machine beside a real Git repository, for the organization's tests: a real Git
 * repository with an origin, throwaway Ed25519 keys, SSH-signed commits, a forge that answers what
 * a test sets, and a real ssh-agent when a test starts one.
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

    Map<?, ?> answer() {
        return (Map<?, ?>) Json.parse(out.toString());
    }

    @Override
    public void close() throws InterruptedException {
        if (agent != null) {
            agent.destroy();
            agent.waitFor();
        }
    }
}
