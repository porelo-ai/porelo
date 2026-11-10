package org.fuin.porelo.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/** Publishes the private arrival ledgers for the project's current readers. */
final class ProjectArrivals {
    private final ProjectFiles files;
    private final ProjectPublication publication;
    private final Path directory;

    ProjectArrivals(ProjectFiles files, ProjectRuntime runtime) {
        this(files, runtime, java.time.Clock.systemUTC());
    }

    ProjectArrivals(ProjectFiles files, ProjectRuntime runtime, java.time.Clock clock) {
        this.files = files;
        publication = new ProjectPublication(files, runtime, clock);
        directory = files.etc.resolve("organization/arrivals");
    }

    void publish(String project, String repository, Path ledger) throws IOException {
        if (files.exists(directory)) {
            files.directory(directory, false, false);
            final int mode =
                    (int) Files.getAttribute(directory, "unix:mode", LinkOption.NOFOLLOW_LINKS);
            if ((mode & 0001) == 0)
                throw new HostRefusal(
                        73,
                        "arrivals publication remains closed; repair it with porelo-host org follow");
        }
        final Path destination = destination(project, repository);
        remove(project, repository);
        final var readers = publication.readers(project);
        traversable(ProjectFiles.parent(destination));
        publication.publish(destination, ledger, readers);
    }

    /** Revoke before resolving accounts, so any failure leaves former readers without access. */
    void revoke() throws IOException {
        if (files.exists(directory)) {
            files.directory(directory, false, false);
            Files.setAttribute(directory, "unix:mode", 0700, LinkOption.NOFOLLOW_LINKS);
            for (Path project : files.list(directory)) {
                files.directory(project, false, false);
                for (Path path : files.list(project)) {
                    files.trusted(path, false);
                    files.delete(path);
                }
                files.delete(project);
            }
            Files.setAttribute(directory, "unix:mode", 0711, LinkOption.NOFOLLOW_LINKS);
        }
    }

    void prepare(List<String> projects) throws IOException {
        revoke();
        for (String project : projects) {
            final Path privateDirectory = files.at("/var/lib/porelo-host/arrivals/" + project);
            if (!files.exists(privateDirectory)) continue;
            files.directory(privateDirectory, true, false);
            for (Path ledger : files.list(privateDirectory)) {
                final String name = ledger.getFileName().toString();
                if (!name.matches("[a-z0-9._-]{1,100}\\.jsonl"))
                    throw new HostRefusal(73, "the arrivals directory holds an unknown entry");
                files.trusted(ledger, true);
                try (var lock = HostConfigurationLock.acquire(ledger, true)) {
                    if (Files.size(lock.descriptorPath()) > 0)
                        publish(project, name.substring(0, name.length() - 6), ledger);
                }
            }
        }
    }

    void remove(String project, String repository) throws IOException {
        final Path path = destination(project, repository);
        if (files.exists(path)) {
            files.trusted(path, false);
            files.delete(path);
        }
    }

    static void removeProject(ProjectFiles files, String project) throws IOException {
        final Path directory = files.etc.resolve("organization/arrivals/" + project);
        if (!files.exists(directory)) return;
        files.directory(directory, false, false);
        for (Path path : files.list(directory)) {
            files.trusted(path, false);
            files.delete(path);
        }
        files.delete(directory);
    }

    private Path destination(String project, String repository) {
        if (!project.matches(HostRecords.PROJECT) || !repository.matches("[a-z0-9._-]{1,100}"))
            throw new HostRefusal(65, "arrivals name no project and repository");
        return directory.resolve(project).resolve(repository + ".jsonl");
    }

    private void traversable(Path path) throws IOException {
        if (!path.startsWith(files.etc)) {
            files.directory(path, false, false);
            return;
        }
        traversable(ProjectFiles.parent(path));
        files.mkdir(path, 0711);
        Files.setAttribute(path, "unix:mode", 0711, LinkOption.NOFOLLOW_LINKS);
    }
}
