package org.fuin.porelo.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.fuin.porelo.organization.gittree.HttpForgeAdmin;

/** Distribute the pool's run key under the project helper's existing exclusive lock. */
final class ProjectPoolKey {
    private final ProjectFiles files;

    ProjectPoolKey(ProjectFiles files) {
        this.files = files;
    }

    List<String> send(HttpForgeAdmin forge) throws IOException {
        final Path key = files.at("/var/lib/porelo-host/pool/run-key");
        final String secret = readKey(files);
        final byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        final String digest;
        try {
            digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by Java", ex);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
        final Path sent = Path.of(key + ".sent");
        final String known = files.exists(sent) ? files.text(sent, true).strip() : "";
        final List<String> changed = new ArrayList<>();
        for (String project : projects()) {
            if (!digest.equals(known) || !forge.holdsPoolKey(project)) {
                forge.sendPoolKey(project, secret);
                changed.add(project);
            }
        }
        if (!digest.equals(known)) {
            files.publish(sent, ".run-key.sent.", digest + "\n");
        }
        return changed;
    }

    static String readKey(ProjectFiles files) throws IOException {
        // Python's UTF-8 text reader normalizes universal newlines before hashing and upload.
        return files.text(files.at("/var/lib/porelo-host/pool/run-key"), true)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }

    List<String> withdraw(HttpForgeAdmin forge) throws IOException {
        final List<String> changed = new ArrayList<>();
        for (String project : projects()) {
            if (forge.holdsPoolKey(project)) {
                forge.withdrawPoolKey(project);
                changed.add(project);
            }
        }
        return changed;
    }

    private List<String> projects() throws IOException {
        final Path directory = files.etc.resolve("projects");
        files.directory(directory, true, true);
        final List<String> projects = new ArrayList<>();
        if (files.exists(directory)) {
            for (Path record : files.list(directory)) {
                final String name = record.getFileName().toString();
                if (name.endsWith(".conf")) {
                    final String project = name.substring(0, name.length() - 5);
                    if (!project.matches(ProjectRecords.PROJECT)) {
                        throw new HostRefusal(65, "the project records hold an invalid name");
                    }
                    projects.add(project);
                }
            }
        }
        return projects;
    }
}
