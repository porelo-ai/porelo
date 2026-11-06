package org.fuin.porelo.ecosystem.stub;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fuin.porelo.ecosystem.api.Detection;
import org.fuin.porelo.ecosystem.api.Ecosystem;
import org.fuin.porelo.ecosystem.api.EcosystemMain;
import org.fuin.porelo.ecosystem.api.EcosystemRefused;
import org.fuin.porelo.ecosystem.api.File;
import org.fuin.porelo.ecosystem.api.Finding;
import org.fuin.porelo.ecosystem.api.Repository;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.JsonException;
import org.jspecify.annotations.Nullable;

/**
 * A build-tool plugin for the tests: it answers from a file instead of a build tool, read again on
 * every call, so a test changes what it says by writing the file.
 *
 * <p>The file is {@code answers.json} beside the executable, or what {@code
 * PORELO_ECOSYSTEM_STUB_ANSWERS} names, and holds the contract's answers as they go on the wire:
 *
 * <pre>
 * {"detect": {"findings": [{"part": "TOOLS", "key": "java.version", "value": "25", "file": "pom.xml", "line": 14,
 *                           "origin": "CHECKOUT", "unknown": false, "note": ""}],
 *             "unplaced": []},
 *  "resolve": {"findings": [{"part": "TOOLS", "key": "run.java", "value": "RUN …", "file": "pom.xml", "line": 14,
 *                            "origin": "CHECKOUT", "unknown": false, "note": ""}]},
 *  "configure": {"files": [{"path": ".stub/settings", "content": "…", "ownerOnly": false}]},
 *  "refuse": {"reason": "UNREADABLE", "detail": "…"}}
 * </pre>
 *
 * Without {@code detect} it recognizes nothing, without {@code resolve} it cannot say, and without
 * {@code configure} it writes no file. It opens no connection, {@code resolve} included. A {@code
 * refuse} refuses every call. A file it cannot read or understand is {@code Unreadable}.
 */
public final class StubEcosystem implements Ecosystem {

    /** The ecosystem this plugin serves, and the name its executable is installed under. */
    public static final String NAME = "stub-ecosystem";

    /**
     * The environment variable that names the answers, for a test that cannot write beside the
     * executable.
     */
    public static final String ANSWERS = "PORELO_ECOSYSTEM_STUB_ANSWERS";

    private final Path answers;

    /**
     * Constructor.
     *
     * @param answers The file to answer from.
     */
    public StubEcosystem(final Path answers) {
        this.answers = answers;
    }

    /**
     * Runs the stub as a plugin executable.
     *
     * @param arguments {@code describe}, or {@code serve <socket>}.
     */
    public static void main(final String[] arguments) {
        EcosystemMain.run(
                new StubEcosystem(
                        answers(
                                System.getenv(ANSWERS),
                                ProcessHandle.current().info().command().orElse(NAME))),
                arguments);
    }

    /**
     * Returns where the answers are.
     *
     * @param named What {@link #ANSWERS} says, or {@code null}.
     * @param command The running executable.
     * @return The file.
     */
    static Path answers(final @Nullable String named, final String command) {
        if (named != null && !named.isBlank()) {
            return Path.of(named);
        }
        final Path parent = Path.of(command).toAbsolutePath().getParent();
        return (parent == null ? Path.of(".") : parent).resolve("answers.json");
    }

    @Override
    public String ecosystem() {
        return NAME;
    }

    @Override
    public Detection detect(final Path directory, final Path home) {
        if (!(read().get("detect") instanceof Map<?, ?> detect)) {
            throw new EcosystemRefused(EcosystemRefused.Reason.NOT_RECOGNIZED, "");
        }
        try {
            return Detection.of(detect);
        } catch (IllegalArgumentException ex) {
            throw unreadable(ex.getMessage());
        }
    }

    @Override
    public Optional<List<Finding>> resolve(
            final Path directory, final Path home, final List<Finding> findings) {
        if (!(read().get("resolve") instanceof Map<?, ?> resolve)) {
            return Optional.empty();
        }
        final Object listed = resolve.get("findings");
        if (listed == null) {
            throw unreadable("resolve holds no findings");
        }
        try {
            return Optional.of(
                    Detection.of(Map.of("findings", listed, "unplaced", List.of())).findings());
        } catch (IllegalArgumentException ex) {
            throw unreadable(ex.getMessage());
        }
    }

    @Override
    public List<File> configure(final List<Repository> repositories) {
        if (!(read().get("configure") instanceof Map<?, ?> configure)) {
            return List.of();
        }
        try {
            return File.of(configure);
        } catch (IllegalArgumentException ex) {
            throw unreadable(ex.getMessage());
        }
    }

    private Map<?, ?> read() {
        final Object parsed;
        try {
            parsed = Json.parse(Files.readString(answers, StandardCharsets.UTF_8));
        } catch (IOException | JsonException ex) {
            throw unreadable("the stub has no answers at " + answers);
        }
        if (!(parsed instanceof Map<?, ?> read)) {
            throw unreadable(answers + " is not a JSON object");
        }
        if (read.get("refuse") instanceof Map<?, ?> refuse) {
            throw new EcosystemRefused(
                    EcosystemRefused.Reason.valueOf(String.valueOf(refuse.get("reason"))),
                    refuse.get("detail") == null ? "" : String.valueOf(refuse.get("detail")));
        }
        return read;
    }

    private EcosystemRefused unreadable(final @Nullable String detail) {
        return new EcosystemRefused(EcosystemRefused.Reason.UNREADABLE, answers + ": " + detail);
    }
}
