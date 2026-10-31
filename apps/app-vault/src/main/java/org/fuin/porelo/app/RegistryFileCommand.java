package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.HttpForgeDirectory;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.RegistryFiles;
import org.fuin.porelo.vault.RegistryState;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Files a card or a host's access file into a registry checkout as one commit, signed by Git
 * through the person's own agent with a key the registry's rule accepts for that path.
 *
 * <p>Plain Git, never a forge's contents API: the commit is the proof, and it is made where the key
 * is. Nothing is pushed; the person pushes with git, as with any other commit.
 *
 * <p>The commit is made by Git's plumbing, never through the checkout's index or work tree: the
 * file's bytes become a blob as they are, in a scratch index on top of {@code HEAD}, so no filter,
 * attribute or hook the checkout holds can change what is signed. Who may sign comes from the
 * registry as its read accepts it, never from the checkout's {@code owners.yml} as it stands.
 *
 * <p>A change may be named by the caller's {@code --id}, carried in the commit as a trailer beside
 * the digest of its path and bytes, so a caller that lost the answer asks again and is answered the
 * commit already made.
 */
@Command(
        name = "file",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description =
                "Commits a person card, a host card or a host's access file to a registry checkout,"
                        + " signed through your ssh-agent.")
public class RegistryFileCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    /** As the registry reader bounds a file. */
    private static final long LIMIT = 1024 * 1024;

    private static final String AGENT = "SSH_AUTH_SOCK";

    /** The id's shape, so it is one trailer value and one word in every message. */
    private static final String ID = "[A-Za-z0-9._-]{1,64}";

    /** A commit's full hash. */
    private static final String COMMIT = "[0-9a-f]{40}|[0-9a-f]{64}";

    /** How far back an id is looked for: a bound, so a long history costs no more than this. */
    private static final int SEARCHED = 1000;

    @Parameters(
            index = "0",
            paramLabel = "KIND",
            description = "card, host-card, host-access, host-card-remove or owners.")
    private String kind = "";

    @Parameters(index = "1", paramLabel = "CHECKOUT", description = "The registry checkout.")
    private Path checkout = Path.of("");

    @Option(
            names = "--card",
            paramLabel = "FILE",
            description = "The signed card, for card and host-card.")
    private @Nullable Path card;

    @Option(
            names = "--file",
            paramLabel = "FILE",
            description = "The access file, for host-access; the owners file, for owners.")
    private @Nullable Path file;

    @Option(
            names = "--host",
            paramLabel = "HOST",
            description = "The host's lower-case DNS label, for host-card and host-access.")
    private @Nullable String host;

    @Option(
            names = "--id",
            paramLabel = "ID",
            description =
                    "Names the change: asked again with the same change, it answers the same commit.")
    private @Nullable String id;

    @Option(
            names = "--base",
            paramLabel = "COMMIT",
            description =
                    "The registry commit the change was made against; refused when the checkout's"
                            + " HEAD is another.")
    private @Nullable String base;

    @Option(
            names = "--signing-key-file",
            paramLabel = "FILE",
            description =
                    "For owners: signs with this backup key's file, its passphrase asked by"
                            + " ssh-keygen on the terminal, instead of a key the ssh-agent holds.")
    private @Nullable Path signingKeyFile;

    @Option(names = "--json", description = "Answers the commit and path as JSON.")
    private boolean json;

    @Spec private CommandSpec spec;

    private @Nullable PoreloContext context;

    private CommandRunner runner = new ProcessCommandRunner();

    private java.util.function.UnaryOperator<String> environment = name -> null;

    /** Replaced by a test, which has no forge to ask. */
    RegistryReadCommand.Forges forges =
            (forge, token) ->
                    new HttpForgeDirectory(
                            forge,
                            token,
                            RegistryReadCommand.http(
                                    java.util.Objects.requireNonNull(
                                            context, "picocli's factory sets the context")));

    /** Run between the read and the write; a test moves the checkout's HEAD there. */
    Runnable afterRead = () -> {};

    /** Run just before HEAD is moved; a test moves the checkout's HEAD there. */
    Runnable beforeUpdate = () -> {};

    @Override
    public void setContext(PoreloContext context) {
        this.context = context;
        runner = context.runner();
        environment = context.environment();
    }

    @Override
    public Integer call() {
        final String commit;
        final RegistryFiles.Filing filing;
        try {
            final Path source = source();
            checkCheckout();
            final Map<String, String> identity = identity();
            // A removal files no bytes; its digest is the path's alone.
            final String text = source == null ? "" : read(source);
            final String origin = String.valueOf(source == null ? "" : source.getFileName());
            final RegistryState state =
                    RegistryReadCommand.read(
                                    java.util.Objects.requireNonNull(
                                            context, "picocli's factory sets the context"),
                                    runner,
                                    checkout,
                                    forges,
                                    spec.commandLine().getErr())
                            .state();
            afterRead.run();
            final String head = readHead(state);
            filing =
                    switch (kind) {
                        case "card" -> RegistryFiles.card(text, origin, state.revoked());
                        case "host-card" ->
                                RegistryFiles.hostCard(text, origin, requiredHost(), admins(state));
                        case "host-card-remove" ->
                                RegistryFiles.hostCardRemoval(requiredHost(), admins(state));
                        case "owners" ->
                                signingKeyFile == null
                                        ? RegistryFiles.owners(
                                                text, origin, state.owners(), state.revoked())
                                        : RegistryFiles.ownersByBackup(
                                                text, origin, backupKey(), state);
                        default ->
                                RegistryFiles.hostAccess(
                                        text, origin, requiredHost(), admins(state));
                    };
            final String digest = digest(filing.path(), text);
            final String made = id == null ? null : madeBefore(head, state, filing, text, digest);
            if (made != null) {
                return answer(made, filing.path());
            }
            if (base != null && !base.equals(head)) {
                throw new CardException(
                        "the registry checkout's HEAD is "
                                + head
                                + ", and the change was made against --base "
                                + base
                                + "; read it again and file anew");
            }
            final String socket = signingKeyFile == null ? agentSocket(filing) : "";
            final String key =
                    signingKeyFile == null
                            ? heldKey(held(socket), filing)
                            : unrevoked(filing.signers().getFirst());
            if (source == null) {
                // What the registry accepts is removed, whatever HEAD holds there.
                final String accepted = state.blobs().get(filing.path());
                if (accepted == null) {
                    throw new CardException(
                            "the registry accepts no "
                                    + filing.path()
                                    + " to remove"
                                    + (heldBlob(head, filing.path()).isEmpty()
                                            ? ""
                                            : "; HEAD holds one it refused, which this removes"
                                                    + " not"));
                }
                checkIndex(filing.path());
                checkPlace(filing.path());
                // A removal of what HEAD does not hold changes nothing a reader judges.
                final String restore = heldBlob(head, filing.path()).isEmpty() ? accepted : null;
                commit =
                        commit(
                                filing.path(),
                                null,
                                head,
                                key,
                                false,
                                socket,
                                identity,
                                digest,
                                restore);
                return answer(commit, filing.path());
            }
            final String blob = blob(text, false);
            final boolean again = blob.equals(heldBlob(head, filing.path()));
            if (again && blob.equals(state.blobs().get(filing.path()))) {
                throw new CardException(
                        filing.path() + " is filed already, as the registry accepts it");
            }
            if (again) {
                checkRemovable(state, filing.path(), head);
            }
            checkIndex(filing.path());
            checkPlace(filing.path());
            commit = commit(filing.path(), text, head, key, again, socket, identity, digest, null);
        } catch (IllegalArgumentException ex) {
            return refuse(64, ex.getMessage());
        } catch (IOException ex) {
            return refuse(66, ex.getMessage());
        } catch (CardException | CommandException ex) {
            return refuse(65, ex.getMessage());
        }
        return answer(commit, filing.path());
    }

    private int answer(final String commit, final String path) {
        final var out = spec.commandLine().getOut();
        if (json) {
            final Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("commit", commit);
            answer.put("path", path);
            out.println(Json.write(RegistryVerifyCommand.printable(answer)));
        } else {
            out.println("committed " + commit + " " + path);
        }
        out.flush();
        return 0;
    }

    /** The file the kind takes, refusing the options it does not; none for a removal. */
    private @Nullable Path source() {
        final boolean removal = kind.equals("host-card-remove");
        final boolean forHost = kind.equals("host-card") || kind.equals("host-access") || removal;
        final boolean byFile = kind.equals("host-access") || kind.equals("owners");
        if (!kind.equals("card") && !kind.equals("owners") && !forHost) {
            throw new IllegalArgumentException(
                    "the kind must be card, host-card, host-access, host-card-remove or owners, not '"
                            + RegistryVerifyCommand.printable(kind)
                            + "'");
        }
        if (forHost != (host != null)) {
            throw new IllegalArgumentException(
                    forHost ? kind + " needs --host" : kind + " takes no --host");
        }
        if (id != null && !id.matches(ID)) {
            throw new IllegalArgumentException(
                    "--id '" + RegistryVerifyCommand.printable(id) + "' does not match " + ID);
        }
        if (base != null && !base.matches(COMMIT)) {
            throw new IllegalArgumentException(
                    "--base '"
                            + RegistryVerifyCommand.printable(base)
                            + "' is not a commit's full hash");
        }
        if (removal) {
            if (card != null || file != null) {
                throw new IllegalArgumentException(kind + " takes no --card and no --file");
            }
            return null;
        }
        final Path given = byFile ? file : card;
        if (given == null || (byFile ? card : file) != null) {
            throw new IllegalArgumentException(
                    kind
                            + (byFile
                                    ? " takes --file FILE and no --card"
                                    : " takes --card FILE and no --file"));
        }
        return given;
    }

    private String requiredHost() {
        return java.util.Objects.requireNonNull(host, "source() refuses a host kind without one");
    }

    private void checkCheckout() throws IOException {
        if (!Files.isDirectory(checkout, LinkOption.NOFOLLOW_LINKS)
                || !git(Map.of(), "rev-parse", "--is-inside-work-tree")
                        .trimmedOutput()
                        .equals("true")) {
            throw new IOException(
                    "cannot write registry checkout '"
                            + RegistryVerifyCommand.printable(checkout.toString())
                            + "': expected the directory of a Git working tree, not a symlink");
        }
        // The path is given relative to the registry's root, and git reads it relative to here.
        if (!git(Map.of(), "rev-parse", "--show-prefix").trimmedOutput().isEmpty()) {
            throw new IllegalArgumentException(
                    "'"
                            + RegistryVerifyCommand.printable(checkout.toString())
                            + "' is inside a checkout; give its top directory");
        }
    }

    /** The file as text, bounded and UTF-8, as the reader will take it. */
    private static String read(final Path source) throws IOException {
        if (!Files.isRegularFile(source)) {
            throw new IOException(
                    "cannot read '"
                            + RegistryVerifyCommand.printable(source.toString())
                            + "': expected a regular file");
        }
        if (Files.size(source) > LIMIT) {
            throw new CardException(
                    RegistryVerifyCommand.printable(source.toString()) + ": file exceeds 1 MiB");
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(Files.readAllBytes(source)))
                    .toString();
        } catch (CharacterCodingException ex) {
            throw new CardException(
                    RegistryVerifyCommand.printable(source.toString())
                            + ": file is not UTF-8 text");
        }
    }

    /**
     * The author and committer, as the checkout's own configuration names them; nothing of the
     * account's, which the chosen environment leaves out.
     */
    private Map<String, String> identity() {
        final String name = git(Map.of(), "config", "--get", "user.name").trimmedOutput();
        final String email = git(Map.of(), "config", "--get", "user.email").trimmedOutput();
        if (name.isEmpty() || email.isEmpty()) {
            throw new IllegalArgumentException(
                    "the registry checkout names no user.name and user.email; set them with git"
                            + " config in the checkout");
        }
        return Map.of(
                "GIT_AUTHOR_NAME", name,
                "GIT_AUTHOR_EMAIL", email,
                "GIT_COMMITTER_NAME", name,
                "GIT_COMMITTER_EMAIL", email);
    }

    /**
     * The host's admins as the read accepted them, without a revoked key or a backup key, which
     * sign nothing the rule accepts here; none when the accepted owners name none.
     */
    private List<RegistryState.Owner> admins(final RegistryState state) {
        return state.hostAdmins().getOrDefault(requiredHost(), List.of()).stream()
                .filter(admin -> !admin.backup() && !state.revoked().contains(admin.fingerprint()))
                .toList();
    }

    /**
     * The commit the read judged, which the filing is written onto; refused when the checkout's
     * {@code HEAD} is another, as when it moved since or the read stopped before it.
     */
    private String readHead(final RegistryState state) {
        final String head =
                require(git(Map.of(), "rev-parse", "--verify", "HEAD^{commit}"), "git rev-parse")
                        .trimmedOutput();
        final String read = state.commit();
        if (read == null || !head.equals(read)) {
            throw new CardException(
                    "the registry checkout's HEAD is "
                            + head
                            + ", not the commit the read judged ("
                            + (read == null ? "none" : read)
                            + "); read it again and file then");
        }
        return read;
    }

    /** The blob the bytes are, as they are; written to the object store only when asked. */
    private String blob(final String text, final boolean write) {
        final List<String> arguments = new ArrayList<>(List.of("hash-object"));
        if (write) {
            arguments.add("-w");
        }
        arguments.addAll(List.of("--no-filters", "--stdin"));
        return require(
                        runner.run(
                                RegistryGit.git(
                                                checkout,
                                                Map.of(),
                                                arguments.toArray(String[]::new))
                                        .withInput(text)),
                        "git hash-object")
                .trimmedOutput();
    }

    /**
     * Refuses to remove a refused copy of a person's card when the registry accepted a card for
     * that login: removed, the accepted card would go with its key and its chain, and the card
     * filed after it would be judged as a first one. Nor is an accepted owners file ever removed.
     * The person reverts the refused commit instead.
     */
    private void checkRemovable(final RegistryState state, final String path, final String head) {
        if (!(kind.equals("card") || kind.equals("owners")) || !state.blobs().containsKey(path)) {
            return;
        }
        final String refused =
                require(
                                git(
                                        Map.of(),
                                        "rev-list",
                                        "-1",
                                        "--first-parent",
                                        "--end-of-options",
                                        object(head),
                                        "--",
                                        path),
                                "git rev-list")
                        .trimmedOutput();
        throw new CardException(
                "HEAD holds these bytes at "
                        + path
                        + " from "
                        + refused
                        + ", which the registry refused, and it accepts another "
                        + (kind.equals("card") ? "card" : "owners file")
                        + " there;"
                        + " revert "
                        + refused
                        + " with git, then file again");
    }

    /** What names the change's content: the SHA-256 of its path, a NUL and its bytes. */
    private static String digest(final String path, final String text) {
        try {
            final java.security.MessageDigest sha =
                    java.security.MessageDigest.getInstance("SHA-256");
            sha.update(path.getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 0);
            sha.update(text.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(sha.digest());
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    /** A trailer naming the change a commit makes. */
    private static final String CHANGE_ID = "Porelo-Change-Id";

    /** A trailer naming the digest of the change a commit makes. */
    private static final String CHANGE_DIGEST = "Porelo-Change-Digest";

    /**
     * A trailer on a commit made only so that the change after it is judged, naming that change's
     * id: it answers for nothing itself.
     */
    private static final String CHANGE_STEP = "Porelo-Change-Step";

    /**
     * The commit an earlier call made for this id, when it was this same change; otherwise the id
     * is refused, so that it is never committed twice.
     *
     * <p>Every commit among the last {@value #SEARCHED} first parents of the head that carries the
     * id and a digest once each is this id's. Each must carry this change's digest, and each must
     * be the change the registry's read accepted: the path's last accepted change, signed by the
     * key the read took as its signer, its first-parent diff that path alone, its tree holding the
     * bytes filed. A removal, which leaves no accepted file to name it, must be the last commit
     * that changed the path, removing it, signed by a key that may file it now, while the registry
     * accepts none there. One that is not is refused by name, never passed over. The one made on
     * {@code --base} answers (past the step it committed first, when it did); none made there
     * refuses the id too.
     *
     * <p>Nothing here starts a program the checkout's configuration names: commits are read as Git
     * stores them, as bytes, and their signatures checked here.
     */
    private @Nullable String madeBefore(
            final String head,
            final RegistryState state,
            final RegistryFiles.Filing filing,
            final String text,
            final String digest)
            throws IOException {
        final String wanted = java.util.Objects.requireNonNull(id, "asked only with --id");
        final List<String> line =
                require(
                                git(
                                        Map.of(),
                                        "rev-list",
                                        "--first-parent",
                                        "--max-count=" + SEARCHED,
                                        "--end-of-options",
                                        object(head)),
                                "git rev-list")
                        .standardOutput()
                        .lines()
                        .toList();
        final Map<String, RegistryCommit> commits = commits(line);
        final Map<String, RegistryCommit> named = new LinkedHashMap<>();
        commits.forEach(
                (name, commit) -> {
                    if (commit.carries(CHANGE_ID, wanted) && commit.once(CHANGE_DIGEST) != null) {
                        named.put(name, commit);
                    }
                });
        // Another change under the id is refused before anything else is asked of the commit.
        named.forEach(
                (name, commit) -> {
                    if (!digest.equals(commit.once(CHANGE_DIGEST))) {
                        throw new CardException(
                                "--id " + wanted + " names another change: " + name);
                    }
                });
        final Map<String, String> signers = new LinkedHashMap<>();
        for (Map.Entry<String, RegistryCommit> each : named.entrySet()) {
            final String signer =
                    accepted(each.getKey(), each.getValue(), state, filing, text, head);
            if (signer == null) {
                throw new CardException(
                        "--id "
                                + wanted
                                + " names "
                                + each.getKey()
                                + ", which the registry does not hold as this change; file with"
                                + " another id");
            }
            signers.put(each.getKey(), signer);
        }
        for (Map.Entry<String, RegistryCommit> each : named.entrySet()) {
            final String made =
                    made(commits, each.getValue(), filing.path(), signers.get(each.getKey()));
            if (base == null || base.equals(made)) {
                return each.getKey();
            }
            // Filed again elsewhere, the id would name two commits.
            throw new CardException(
                    "--id "
                            + wanted
                            + " was filed as "
                            + each.getKey()
                            + " on "
                            + (made.isEmpty() ? "no commit" : made)
                            + ", not on --base "
                            + base
                            + "; file with another id");
        }
        return null;
    }

    /** An object name Git printed or a commit holds, checked before it is handed to Git again. */
    private static String object(final String name) {
        if (!name.matches(COMMIT)) {
            throw new CardException("the registry checkout names a commit that is no object name");
        }
        return name;
    }

    /**
     * The commits as Git stores them, read as bytes in one go and cut by the sizes Git gives; each
     * is decoded only once its bytes are cut out.
     */
    private Map<String, RegistryCommit> commits(final List<String> names) throws IOException {
        final Map<String, RegistryCommit> commits = new LinkedHashMap<>();
        if (names.isEmpty()) {
            return commits;
        }
        final StringBuilder input = new StringBuilder();
        names.forEach(name -> input.append(object(name)).append('\n'));
        final byte[] out = bytes(input.toString(), "cat-file", "--batch");
        int at = 0;
        for (String name : names) {
            final byte[] header = (name + " commit ").getBytes(StandardCharsets.US_ASCII);
            int line = at;
            while (line < out.length && out[line] != '\n') {
                line++;
            }
            if (line >= out.length
                    || line - at <= header.length
                    || !java.util.Arrays.equals(
                            out, at, at + header.length, header, 0, header.length)) {
                throw new CardException("cannot read commit " + name + " of the registry checkout");
            }
            final String digits =
                    new String(
                            out,
                            at + header.length,
                            line - at - header.length,
                            StandardCharsets.US_ASCII);
            if (!digits.matches("[0-9]{1,9}")) {
                throw new CardException("cannot read commit " + name + " of the registry checkout");
            }
            final int size = Integer.parseInt(digits);
            final int end = line + 1 + size;
            if (end >= out.length || out[end] != '\n') {
                throw new CardException("cannot read commit " + name + " of the registry checkout");
            }
            commits.put(
                    name, RegistryCommit.parse(java.util.Arrays.copyOfRange(out, line + 1, end)));
            at = end + 1;
        }
        return commits;
    }

    /** The most a batch of commits may come to: each commit is short, and so are they all. */
    private static final long BATCH = 64L * 1024 * 1024;

    /**
     * Runs git on the checkout as {@link RegistryGit} does, with this input, and returns its output
     * as bytes, never decoded.
     */
    private byte[] bytes(final String input, final String... arguments) throws IOException {
        final org.fuin.porelo.core.process.Command command =
                RegistryGit.git(checkout, Map.of(), arguments);
        final Path scratch = Files.createTempDirectory("porelo-registry-batch-");
        try {
            final Path in =
                    Files.writeString(scratch.resolve("in"), input, StandardCharsets.US_ASCII);
            final Path out = scratch.resolve("out");
            final ProcessBuilder builder = new ProcessBuilder(command.arguments());
            builder.environment().clear();
            builder.environment().putAll(command.environment());
            builder.redirectInput(in.toFile());
            builder.redirectOutput(out.toFile());
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            final Process process = builder.start();
            try {
                if (!process.waitFor(10, java.util.concurrent.TimeUnit.MINUTES)) {
                    process.destroyForcibly();
                    throw new CardException(
                            "git " + arguments[0] + " timed out in the registry checkout");
                }
            } catch (InterruptedException ex) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new CardException("git " + arguments[0] + " was interrupted");
            }
            if (process.exitValue() != 0) {
                throw new CardException("git " + arguments[0] + " failed in the registry checkout");
            }
            if (Files.size(out) > BATCH) {
                throw new CardException("the registry checkout's commits exceed 64 MiB");
            }
            return Files.readAllBytes(out);
        } finally {
            deleteTree(scratch);
        }
    }

    /**
     * The fingerprint of the key that signed the commit, when it is the change the registry holds
     * for the path and holds the bytes filed there, or none for a removal; otherwise {@code null}.
     */
    private @Nullable String accepted(
            final String name,
            final RegistryCommit commit,
            final RegistryState state,
            final RegistryFiles.Filing filing,
            final String text,
            final String head) {
        final String path = filing.path();
        final String held = state.blobs().get(path);
        final String entry =
                require(
                                git(
                                        Map.of(),
                                        "ls-tree",
                                        "-z",
                                        "--end-of-options",
                                        object(name),
                                        "--",
                                        path),
                                "git ls-tree")
                        .standardOutput();
        final String signer;
        if (kind.equals("host-card-remove")) {
            // No accepted file names a removal: it must be the path's last change on the line, and
            // signed by a key that may remove it now.
            signer = commit.signer();
            if (held != null
                    || !entry.isEmpty()
                    || signer == null
                    || filing.signers().stream()
                            .map(PersonCard::fingerprint)
                            .noneMatch(signer::equals)
                    || !require(
                                    git(
                                            Map.of(),
                                            "rev-list",
                                            "--first-parent",
                                            "--end-of-options",
                                            object(head),
                                            "^" + name,
                                            "--",
                                            path),
                                    "git rev-list")
                            .standardOutput()
                            .isEmpty()) {
                return null;
            }
        } else {
            final RegistryState.Change change = state.changes().get(path);
            final String blob = blob(text, false);
            if (change == null
                    || !change.commit().equals(name)
                    || !blob.equals(held)
                    || !entry.equals("100644 blob " + blob + "\t" + path + "\0")
                    || !commit.signedBy(change.signer())) {
                return null;
            }
            signer = change.signer();
            // The owners file names who may sign each change of it, a backup key's alone included.
            if (kind.equals("owners")
                    && filing.signers().stream()
                            .map(PersonCard::fingerprint)
                            .noneMatch(signer::equals)) {
                return null;
            }
        }
        return touchesOnly(name, commit.parent(), path) ? signer : null;
    }

    /** Whether the commit's first-parent diff is the path alone. */
    private boolean touchesOnly(final String name, final String parent, final String path) {
        if (!parent.isEmpty() && !parent.matches(COMMIT)) {
            return false;
        }
        final List<String> diff =
                new ArrayList<>(
                        List.of(
                                "diff-tree",
                                "--no-commit-id",
                                "--no-renames",
                                "--name-only",
                                "-r",
                                "-z"));
        if (parent.isEmpty()) {
            diff.add("--root");
        }
        diff.add("--end-of-options");
        if (!parent.isEmpty()) {
            diff.add(parent);
        }
        diff.add(object(name));
        return require(git(Map.of(), diff.toArray(String[]::new)), "git diff-tree")
                .standardOutput()
                .equals(path + "\0");
    }

    /**
     * The commit the change was made on: its first parent, or that commit's own when the parent is
     * the step this change committed first, of the same path and signed by the change's own signer.
     * Any other commit is no step, and moves no base.
     */
    private String made(
            final Map<String, RegistryCommit> commits,
            final RegistryCommit commit,
            final String path,
            final @Nullable String signer)
            throws IOException {
        final String parent = commit.parent();
        if (parent.isEmpty() || !parent.matches(COMMIT) || signer == null) {
            return parent;
        }
        final RegistryCommit known = commits.get(parent);
        final RegistryCommit step = known == null ? commits(List.of(parent)).get(parent) : known;
        if (step != null
                && step.carries(
                        CHANGE_STEP, java.util.Objects.requireNonNull(id, "asked only with --id"))
                && !step.names(CHANGE_ID)
                && !step.names(CHANGE_DIGEST)
                && step.signedBy(signer)
                && touchesOnly(parent, step.parent(), path)) {
            return step.parent();
        }
        return parent;
    }

    private static void deleteTree(final Path directory) throws IOException {
        try (var files = Files.walk(directory)) {
            for (Path each : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(each);
            }
        }
    }

    /** The public half the backup key's file names; its secret part stays in the file. */
    private String backupKey() throws IOException {
        if (!kind.equals("owners")) {
            throw new IllegalArgumentException("--signing-key-file files owners.yml alone");
        }
        final Path given = java.util.Objects.requireNonNull(signingKeyFile);
        if (Files.isSymbolicLink(given) || !Files.isRegularFile(given)) {
            throw new IOException(given + " is not a key file");
        }
        try {
            return org.fuin.porelo.vault.OpenSshPrivateKey.publicKey(Files.readString(given));
        } catch (org.fuin.porelo.vault.VaultException ex) {
            throw new CardException(given + ": " + ex.getMessage());
        }
    }

    private String agentSocket(final RegistryFiles.Filing filing) {
        final String socket = environment.apply(AGENT);
        if (socket == null || socket.isEmpty()) {
            throw new CardException(
                    "no ssh-agent is reachable (SSH_AUTH_SOCK is not set); "
                            + filing.path()
                            + " is filed by "
                            + fingerprints(filing));
        }
        return socket;
    }

    /** The keys the agent holds, {@code ssh-ed25519 AAAA...} each. */
    private List<String> held(final String socket) {
        final CommandResult listed =
                runner.run(
                        org.fuin.porelo.core.process.Command.of("ssh-add", "-L")
                                .withEnvironment(Map.of(AGENT, socket))
                                .withChosenEnvironment(RegistryGit.ENVIRONMENT));
        final List<String> held = new ArrayList<>();
        if (listed.successful()) {
            for (String line : listed.standardOutput().lines().toList()) {
                final String[] words = line.strip().split(" ");
                if (words.length >= 2) {
                    held.add(words[0] + " " + words[1]);
                }
            }
        }
        return held;
    }

    /** The first key the rule accepts for the path that the agent holds and nothing revokes. */
    private String heldKey(final List<String> held, final RegistryFiles.Filing filing)
            throws IOException {
        final List<String> revoked = new ArrayList<>();
        for (String wanted : filing.signers()) {
            if (held.contains(wanted)) {
                if (!revokedHere(wanted)) {
                    return wanted;
                }
                revoked.add(PersonCard.fingerprint(wanted));
            }
        }
        throw new CardException(
                "the ssh-agent holds none of the keys that may file "
                        + filing.path()
                        + ": "
                        + fingerprints(filing)
                        + (revoked.isEmpty()
                                ? ""
                                : "; this machine's revocation list revokes "
                                        + String.join(", ", revoked)));
    }

    /** The key, refused when this machine's revocation list names it. */
    private String unrevoked(final String key) throws IOException {
        if (revokedHere(key)) {
            throw new CardException(
                    PersonCard.fingerprint(key) + " is revoked by this machine's revocation list");
        }
        return key;
    }

    /** Whether this machine's revocation list names the key; the filing left out a registry's. */
    private boolean revokedHere(final String key) throws IOException {
        return IdentityFacts.revoked(
                java.util.Objects.requireNonNull(context, "picocli's factory sets the context"),
                List.of(),
                key);
    }

    private static String fingerprints(final RegistryFiles.Filing filing) {
        return String.join(", ", filing.signers().stream().map(PersonCard::fingerprint).toList());
    }

    /** Refuses to sweep a change the person staged for something else into a signed commit. */
    private void checkIndex(final String path) {
        final CommandResult staged = git(Map.of(), "diff", "--cached", "--name-only", "-z");
        if (!staged.successful()) {
            throw new CardException(
                    "cannot read the index of the registry checkout: "
                            + RegistryVerifyCommand.printable(staged.standardError().strip()));
        }
        final long others =
                java.util.Arrays.stream(staged.standardOutput().split("\0"))
                        .filter(name -> !name.isEmpty() && !name.equals(path))
                        .count();
        if (others > 0) {
            throw new CardException(
                    "the registry checkout has "
                            + others
                            + " other staged change(s); commit or unstage them before filing "
                            + path);
        }
    }

    /**
     * Refuses a path the file could only be written to through a symlink or over something that is
     * no regular file, before anything is committed; nothing is written here.
     */
    private void checkPlace(final String path) {
        Path at = checkout;
        final String[] parts = path.split("/");
        for (int index = 0; index < parts.length - 1; index++) {
            at = at.resolve(parts[index]);
            if (Files.exists(at, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(at, LinkOption.NOFOLLOW_LINKS)) {
                throw new CardException(
                        "the registry checkout's "
                                + checkout.relativize(at)
                                + " is not a directory");
            }
        }
        final Path target = at.resolve(parts[parts.length - 1]);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new CardException("the registry checkout's " + path + " is not a regular file");
        }
    }

    /** Writes the file, never through a symlink the checkout holds. */
    private void place(final String path, final String text) throws IOException {
        Path at = checkout;
        final String[] parts = path.split("/");
        for (int index = 0; index < parts.length - 1; index++) {
            at = at.resolve(parts[index]);
            if (!Files.exists(at, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(at);
            } else if (!Files.isDirectory(at, LinkOption.NOFOLLOW_LINKS)) {
                throw new CardException(
                        "the registry checkout's "
                                + checkout.relativize(at)
                                + " is not a directory");
            }
        }
        final Path target = at.resolve(parts[parts.length - 1]);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new CardException("the registry checkout's " + path + " is not a regular file");
        }
        Files.writeString(target, text, StandardCharsets.UTF_8);
    }

    /**
     * Commits the file at that path alone onto the commit the read judged, signed by Git through
     * the agent, and moves {@code HEAD} on to it only from that commit.
     *
     * <p>When {@code HEAD} already holds these very bytes at the path in a commit the rule refused,
     * a commit would change nothing there and so be judged by nobody; the path's removal is
     * committed first, then the file. Likewise a removal of a path {@code HEAD} does not hold puts
     * the accepted blob back first. Such a first commit carries the id as a step, never the change
     * itself, so it never answers for it. The work tree gets the file only once {@code HEAD} moved.
     *
     * @param text The file's bytes, or {@code null} when the path's removal is all that is filed.
     * @param removal Whether the path's removal is committed first.
     * @param digest The change's digest, carried with {@code --id}.
     * @param restore For a removal, the accepted blob put back first, or {@code null}.
     */
    private String commit(
            final String path,
            final @Nullable String text,
            final String head,
            final String key,
            final boolean removal,
            final String socket,
            final Map<String, String> identity,
            final String digest,
            final @Nullable String restore)
            throws IOException {
        final String trailers =
                id == null
                        ? ""
                        : "\n\n" + CHANGE_ID + ": " + id + "\n" + CHANGE_DIGEST + ": " + digest;
        final String step = id == null ? "" : "\n\n" + CHANGE_STEP + ": " + id;
        final Path scratch = Files.createTempDirectory("porelo-registry-file-");
        try {
            // An empty work tree: neither the checkout's files nor its attributes are read, so no
            // filter runs, not even for the entries Git checks again when it writes an index.
            final String empty = Files.createDirectory(scratch.resolve("work")).toString();
            final Map<String, String> bare = Map.of("GIT_WORK_TREE", empty);
            final Map<String, String> index =
                    Map.of(
                            "GIT_WORK_TREE",
                            empty,
                            "GIT_INDEX_FILE",
                            scratch.resolve("index").toString());
            require(git(index, "read-tree", "--end-of-options", object(head)), "git read-tree");
            String parent = head;
            if (text == null) {
                if (restore != null) {
                    require(
                            git(
                                    index,
                                    "update-index",
                                    "--add",
                                    "--replace",
                                    "--cacheinfo",
                                    "100644," + restore + "," + path),
                            "git update-index");
                    parent =
                            signed(
                                    index,
                                    parent,
                                    "Restore " + path + " to remove it" + step,
                                    key,
                                    socket,
                                    identity);
                }
                require(
                        git(index, "update-index", "--force-remove", "--", path),
                        "git update-index");
                final String commit =
                        signed(index, parent, "Remove " + path + trailers, key, socket, identity);
                beforeUpdate.run();
                require(
                        git(
                                bare,
                                "update-ref",
                                "--end-of-options",
                                "HEAD",
                                object(commit),
                                object(head)),
                        "git update-ref");
                // checkPlace refused a symlink on the way, so this deletes the checkout's own file.
                Files.deleteIfExists(checkout.resolve(path));
                require(
                        git(bare, "update-index", "--force-remove", "--", path),
                        "git update-index");
                return commit;
            }
            final String blob = blob(text, true);
            if (removal) {
                require(
                        git(index, "update-index", "--force-remove", "--", path),
                        "git update-index");
                parent =
                        signed(
                                index,
                                parent,
                                "Remove " + path + " to file it again" + step,
                                key,
                                socket,
                                identity);
            }
            require(
                    git(
                            index,
                            "update-index",
                            "--add",
                            "--cacheinfo",
                            "100644," + blob + "," + path),
                    "git update-index");
            final String commit =
                    signed(index, parent, "File " + path + trailers, key, socket, identity);
            beforeUpdate.run();
            require(
                    git(
                            bare,
                            "update-ref",
                            "--end-of-options",
                            "HEAD",
                            object(commit),
                            object(head)),
                    "git update-ref");
            place(path, text);
            // The checkout's index follows HEAD for that path, as the work tree already does. Git
            // checks entries again as it writes the index, which would run their filters.
            final List<String> update = new ArrayList<>(noFilters());
            update.addAll(
                    List.of("update-index", "--add", "--cacheinfo", "100644," + blob + "," + path));
            require(git(bare, update.toArray(String[]::new)), "git update-index");
            return commit;
        } finally {
            deleteTree(scratch);
        }
    }

    /**
     * Options that leave every filter driver the checkout's configuration names without a command,
     * so none runs whatever its attributes say.
     */
    private List<String> noFilters() {
        final CommandResult named =
                git(Map.of(), "config", "--name-only", "--get-regexp", "^filter\\.");
        final java.util.Set<String> drivers = new java.util.TreeSet<>();
        for (String name : named.standardOutput().lines().toList()) {
            final int last = name.lastIndexOf('.');
            if (last > "filter.".length()) {
                drivers.add(name.substring("filter.".length(), last));
            }
        }
        final List<String> options = new ArrayList<>();
        for (String driver : drivers) {
            if (driver.contains("=")) {
                throw new CardException(
                        "the registry checkout names a filter driver git cannot be told to skip");
            }
            for (String field : List.of("clean=", "smudge=", "process=", "required=false")) {
                options.addAll(List.of("-c", "filter." + driver + "." + field));
            }
        }
        return options;
    }

    /** The blob a regular file at the path of that commit is, or "" when it holds none. */
    private String heldBlob(final String commit, final String path) {
        final String entry =
                require(
                                git(
                                        Map.of(),
                                        "ls-tree",
                                        "-z",
                                        "--end-of-options",
                                        object(commit),
                                        "--",
                                        path),
                                "git ls-tree")
                        .standardOutput();
        final String[] fields = entry.split("[ \\t\\x00]");
        return fields.length >= 3 && fields[1].equals("blob") ? fields[2] : "";
    }

    /** Writes the scratch index's tree and commits it on the parent, signed through the agent. */
    private String signed(
            final Map<String, String> index,
            final String parent,
            final String message,
            final String key,
            final String socket,
            final Map<String, String> identity) {
        final String tree = require(git(index, "write-tree"), "git write-tree").trimmedOutput();
        final Map<String, String> signing = new LinkedHashMap<>(index);
        signing.putAll(identity);
        if (signingKeyFile == null) {
            signing.put(AGENT, socket);
        }
        return require(
                        git(
                                signing,
                                "-c",
                                "gpg.format=ssh",
                                "-c",
                                "gpg.ssh.program=ssh-keygen",
                                "-c",
                                "user.signingkey="
                                        + (signingKeyFile == null
                                                ? "key::" + key
                                                : signingKeyFile.toAbsolutePath().toString()),
                                "commit-tree",
                                "-S",
                                "-p",
                                object(parent),
                                "-m",
                                message,
                                "--end-of-options",
                                object(tree)),
                        "git commit-tree")
                .trimmedOutput();
    }

    private static CommandResult require(final CommandResult result, final String what) {
        if (!result.successful()) {
            throw new CardException(
                    what
                            + " failed in the registry checkout: "
                            + RegistryVerifyCommand.printable(result.standardError().strip()));
        }
        return result;
    }

    private CommandResult git(final Map<String, String> variables, final String... arguments) {
        return runner.run(RegistryGit.git(checkout, variables, arguments));
    }

    private int refuse(int code, @Nullable String message) {
        final var err = spec.commandLine().getErr();
        err.println("porelo: " + RegistryVerifyCommand.printable(String.valueOf(message)));
        err.flush();
        return code;
    }
}
