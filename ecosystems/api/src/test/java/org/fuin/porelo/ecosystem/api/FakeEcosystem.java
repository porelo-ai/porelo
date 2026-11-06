package org.fuin.porelo.ecosystem.api;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A plugin the tests start as an executable: fixed answers, so a test sees what crossed the socket.
 *
 * <p>A directory named {@code other} is not recognized, one named {@code locked} cannot be read,
 * one named {@code gone} ends the plugin's process before it answers. Any other is answered with
 * one finding per part, naming the directory and the home it was given. {@code configure} answers
 * one file per repository, holding its name and URL, and one more holding the extension it was
 * given, as JSON, when it was given one. {@code resolve} answers a {@code run.<key>} for each
 * finding it is given, holding its key and value, and an {@code env.FAKE_DIRECTORY} naming the
 * directory; for a directory named {@code slow} it answers only after half a minute.
 */
public final class FakeEcosystem implements Ecosystem {

    /**
     * Runs the fake as a plugin executable.
     *
     * @param arguments As every plugin takes them.
     */
    public static void main(final String[] arguments) {
        EcosystemMain.run(new FakeEcosystem(), arguments);
    }

    @Override
    public String ecosystem() {
        return "fake";
    }

    @Override
    public Detection detect(final Path directory, final Path home) {
        final Path name = directory.getFileName();
        final String named = name == null ? "" : name.toString();
        if ("other".equals(named)) {
            throw new EcosystemRefused(EcosystemRefused.Reason.NOT_RECOGNIZED, "");
        }
        if ("locked".equals(named)) {
            throw new EcosystemRefused(EcosystemRefused.Reason.UNREADABLE, "pom.xml: permission");
        }
        if ("gone".equals(named)) {
            // As a plugin killed for not answering in time is left: gone, with no answer.
            Runtime.getRuntime().halt(1);
        }
        return new Detection(
                List.of(
                        new Finding(
                                Finding.Part.TOOLS,
                                "java.version",
                                "25",
                                "pom.xml",
                                14,
                                Finding.Origin.CHECKOUT,
                                false,
                                directory.toString()),
                        new Finding(
                                Finding.Part.RULES,
                                "credential",
                                "repository/corp",
                                ".m2/settings.xml",
                                3,
                                Finding.Origin.YOURS,
                                false,
                                home.toString())),
                List.of(new Unplaced("pom.xml", 40, "a profile activated by a property")));
    }

    @Override
    public Optional<List<Finding>> resolve(
            final Path directory, final Path home, final List<Finding> findings) {
        if ("slow".equals(String.valueOf(directory.getFileName()))) {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        final List<Finding> resolved = new ArrayList<>();
        for (final Finding each : findings) {
            resolved.add(
                    new Finding(
                            Finding.Part.TOOLS,
                            "run." + each.key(),
                            "RUN echo " + each.key() + " " + each.value(),
                            each.file(),
                            each.line(),
                            each.origin(),
                            false,
                            ""));
        }
        resolved.add(
                new Finding(
                        Finding.Part.TOOLS,
                        "env.FAKE_DIRECTORY",
                        directory.toString(),
                        "",
                        0,
                        Finding.Origin.MACHINE,
                        false,
                        ""));
        return Optional.of(resolved);
    }

    @Override
    public List<File> configure(final List<Repository> repositories) {
        return repositories.stream()
                .map(
                        repository ->
                                new File(
                                        ".fake/" + repository.name(),
                                        repository.url(),
                                        repository.name().startsWith("private")))
                .toList();
    }

    @Override
    public List<File> configure(final List<Repository> repositories, final Extension extension) {
        // What arrived, written back as a file, so a test sees the block cross the socket.
        final List<File> files = new java.util.ArrayList<>(configure(repositories));
        if (!extension.values().isEmpty()) {
            files.add(
                    new File(
                            ".fake/extension",
                            org.fuin.porelo.wire.Json.write(extension.values()),
                            false));
        }
        return files;
    }
}
