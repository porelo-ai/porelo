package org.fuin.porelo.organization.gittree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.organization.IdentitySource;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.OrganizationWrites;
import org.fuin.porelo.organization.contract.WritesFixture;
import org.fuin.porelo.vault.OpenSshPrivateKey;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.SigningKey;

/** The contract's fixture on a real bare repository, every key made in the run from a file. */
final class GitTreeWritesFixture implements WritesFixture {

    private static final String ORGANISATION = "acme";

    private final Path dir;

    private final Path repo;

    private final ProcessCommandRunner runner = new ProcessCommandRunner();

    /** By public key, and read when a change signs: a key may be made after the writes were. */
    private final Map<String, Path> keyFiles = new HashMap<>();

    private final Map<String, SigningKey> keys = new HashMap<>();

    private final Forge forge = new Forge();

    private int reads;

    private static final class Forge implements IdentitySource {
        final Map<String, List<String>> keys = new HashMap<>();
        final Map<String, Boolean> owners = new HashMap<>();

        @Override
        public Optional<Keys> keys(final String login) {
            return Optional.ofNullable(keys.get(login)).map(listed -> new Keys(listed, true));
        }

        @Override
        public Optional<Boolean> owns(final String organisation, final String login) {
            return Optional.ofNullable(owners.get(login));
        }
    }

    GitTreeWritesFixture(final Path dir) throws IOException {
        this.dir = dir;
        this.repo = Files.createDirectory(dir.resolve("organization.git"));
        runner.runOrFail(
                Command.of("git", "init", "-q", "--bare", "-b", "main", repo.toString())
                        .withEnvironment(
                                Map.of(
                                        "GIT_CONFIG_GLOBAL",
                                        "/dev/null",
                                        "GIT_CONFIG_NOSYSTEM",
                                        "1")));
    }

    private SigningKey keyNamed(final String name) {
        return keys.computeIfAbsent(
                name,
                absent -> {
                    final Path file = dir.resolve(name);
                    runner.runOrFail(
                            Command.of(
                                    "ssh-keygen",
                                    "-q",
                                    "-t",
                                    "ed25519",
                                    "-N",
                                    "",
                                    "-f",
                                    file.toString()));
                    try {
                        final SigningKey made =
                                new SigningKey(
                                        Base64.getDecoder()
                                                .decode(
                                                        OpenSshPrivateKey.seedBase64(
                                                                Files.readString(file))),
                                        "test");
                        keyFiles.put(PersonCard.keyLine(made), file);
                        forge.keys.putIfAbsent(name, List.of(PersonCard.keyLine(made)));
                        return made;
                    } catch (final IOException e) {
                        throw new IllegalStateException("the key just made cannot be read", e);
                    }
                });
    }

    @Override
    public OrganizationWrites writes() {
        return new GitTreeWrites(
                runner,
                repo.toString(),
                ORGANISATION,
                FetchAccess.NONE,
                forge,
                new CommitSigning(Map.of(), keyFiles));
    }

    @Override
    public OrganizationQuestions read() throws IOException {
        return GitTreeStore.at(
                        runner,
                        repo.toString(),
                        ORGANISATION,
                        "forge.example",
                        FetchAccess.NONE,
                        dir.resolve("state-" + reads++))
                .read(forge);
    }

    @Override
    public String key(final String holder) {
        return PersonCard.keyLine(keyNamed(holder));
    }

    @Override
    public String card(final String login, final String holder) {
        return PersonCard.write(
                login,
                login,
                "",
                keyNamed(holder),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2030, 1, 1),
                null);
    }

    @Override
    public String hostCard(final String host, final String holder) {
        return org.fuin.porelo.vault.HostCard.write(
                host, "build.example.org", 22, key(holder), 3000, 2222, 8448, keyNamed(holder));
    }

    @Override
    public String handedOn(final String login, final String holder, final String previous) {
        return PersonCard.write(
                login,
                login,
                "",
                keyNamed(holder),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2030, 1, 1),
                keyNamed(previous));
    }

    @Override
    public void sourceLists(final String login, final String... holders) {
        forge.keys.put(login, Arrays.stream(holders).map(this::key).toList());
    }

    @Override
    public void sourceSaysOwner(final String login, final boolean owns) {
        forge.owners.put(login, owns);
    }
}
