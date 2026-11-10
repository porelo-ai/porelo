package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.fuin.porelo.core.project.file.RepositorySettings.Asked;
import org.fuin.porelo.wire.Directories;
import org.fuin.porelo.wire.Json;

/**
 * Which settings of a repository's own files this machine allowed: in the default project, what the
 * checkout's files ask; in a project written on this machine, the image its mirror's {@code
 * porelo.yml} gives, which nobody signs.
 *
 * <p><strong>A yes is bound to what was set.</strong> Every setting allowed is kept, so a later
 * change asks only for what is new, changed or removed since; a file that asks for less asks
 * nothing. Its image is a setting like the others: a new or changed line, base image or variable
 * asks again. The hash a yes names is of the whole state then asked for.
 *
 * <p>One file per repository, as a registry two processes write is.
 */
public final class RepositoryAllowance {

    /** A name a file may carry: no separator, no leading dot. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]{0,127}");

    /** The most a record may hold to be read: a person allows a page of settings, not a file. */
    private static final int MOST_BYTES = 1024 * 1024;

    private final Path directory;

    /**
     * Constructor.
     *
     * @param context This machine.
     */
    public RepositoryAllowance(final PoreloContext context) {
        this(context, DefaultProject.NAME);
    }

    /**
     * Constructor, for a project's repositories.
     *
     * @param context This machine.
     * @param project The project's name.
     * @throws IllegalArgumentException When the name is no directory's.
     */
    public RepositoryAllowance(final PoreloContext context, final String project) {
        if (!NAME.matcher(project).matches()) {
            throw new IllegalArgumentException("not a project's name: " + project);
        }
        this.directory = context.paths().xdg().state().resolve(project).resolve("allowed");
    }

    /**
     * Returns the hash a yes names: each field of each setting with its length, in the order asked.
     *
     * @param asked The settings.
     * @return Their SHA-256, in hex.
     */
    public static String hash(final List<Asked> asked) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("every Java has SHA-256", ex);
        }
        for (final Asked each : asked) {
            for (final String field :
                    List.of(each.key(), each.value(), each.was(), String.valueOf(each.removed()))) {
                final byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update((bytes.length + ":").getBytes(StandardCharsets.UTF_8));
                digest.update(bytes);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Returns what of the settings asked for this machine has not allowed for a repository: all of
     * them the first time, then what is new, changed or removed since the last yes. What the
     * project said before is not part of a yes: a project that changes asks nothing again.
     *
     * @param repository The repository's name in the default project.
     * @param asked What its files ask for now.
     * @return What is to be shown and allowed, in the order asked; empty when nothing is.
     */
    public List<Asked> notAllowed(final String repository, final List<Asked> asked) {
        final Set<List<Object>> allowed = new HashSet<>();
        for (final Map<?, ?> each : stored(repository)) {
            allowed.add(
                    List.of(
                            String.valueOf(each.get("key")),
                            String.valueOf(each.get("value")),
                            Boolean.TRUE.equals(each.get("removed"))));
        }
        return asked.stream()
                .filter(
                        each ->
                                !allowed.contains(
                                        List.of(each.key(), each.value(), each.removed())))
                .toList();
    }

    /**
     * Records that a person allowed what a repository's files ask for, replacing an earlier yes.
     *
     * @param repository The repository's name in the default project.
     * @param asked Everything its files ask for now: the state allowed.
     * @throws IllegalArgumentException When the name is no file's.
     * @throws IllegalStateException When the record cannot be written.
     */
    public void allow(final String repository, final List<Asked> asked) {
        if (!NAME.matcher(repository).matches()) {
            throw new IllegalArgumentException("not a repository's name: " + repository);
        }
        final List<Map<String, Object>> allowed =
                asked.stream()
                        .map(
                                each ->
                                        Map.<String, Object>of(
                                                "key",
                                                each.key(),
                                                "value",
                                                each.value(),
                                                "removed",
                                                each.removed()))
                        .toList();
        try {
            Directories.create(directory);
            final Path staged = Files.createTempFile(directory, "." + repository + ".", ".new");
            Files.writeString(
                    staged,
                    Json.write(Map.of("hash", hash(asked), "allowed", allowed)),
                    StandardCharsets.UTF_8);
            Files.move(
                    staged,
                    directory.resolve(repository + ".json"),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "the settings of " + repository + " cannot be allowed: " + ex.getMessage(), ex);
        }
    }

    /** What was allowed last; nothing when no record reads. */
    private List<Map<?, ?>> stored(final String repository) {
        if (!NAME.matcher(repository).matches()) {
            return List.of();
        }
        final Path file = directory.resolve(repository + ".json");
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(file) > MOST_BYTES) {
                return List.of();
            }
            if (Json.parse(Files.readString(file, StandardCharsets.UTF_8)) instanceof Map<?, ?> map
                    && map.get("allowed") instanceof List<?> list) {
                return list.stream()
                        .filter(Map.class::isInstance)
                        .<Map<?, ?>>map(Map.class::cast)
                        .toList();
            }
            return List.of();
        } catch (IOException | RuntimeException ex) {
            // A record that does not read allowed nothing: the start asks again for all of it.
            return List.of();
        }
    }
}
