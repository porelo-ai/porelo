package org.fuin.porelo.core.project;

import java.io.StringReader;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The entries of {@code project.signers}: the keys whose signed commits change the project file,
 * and at most one backup key per key, kept offline by the person who holds that key.
 *
 * <p>A key is written as {@code ssh-keygen} prints it. A backup key is a mapping, {@code key}, with
 * {@code backup: true}, the fingerprint of the key it backs under {@code backs} and the date it was
 * filed under {@code since}. A backup key is no signer: a commit it signs may only replace the key
 * it backs by another one, and point itself at that one.
 */
public final class ProjectSigners {

    private static final Pattern KEY_TYPE =
            Pattern.compile("ssh-(ed25519|rsa)|ecdsa-sha2-nistp(256|384|521)|sk-[a-z0-9@.-]+");

    private static final Pattern BLOB = Pattern.compile("[A-Za-z0-9+/]+={0,2}");

    private static final Set<String> BACKUP_FIELDS = Set.of("key", "backup", "backs", "since");

    private static final Pattern ITEM = Pattern.compile("^( *)- ?(.*)$");

    private ProjectSigners() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * One entry of {@code project.signers}.
     *
     * @param key The key, its type and key.
     * @param backup Whether it is a backup key.
     * @param backs For a backup key, the fingerprint of the key it backs; otherwise "".
     * @param since For a backup key, the date it was filed, or ""; otherwise "".
     */
    public record Signer(String key, boolean backup, String backs, String since) {

        /**
         * Returns the key's fingerprint.
         *
         * @return {@code SHA256:...}.
         */
        public String fingerprint() {
            return ProjectSigners.fingerprint(key);
        }
    }

    /**
     * Reads the entries a project file names under {@code project.signers}.
     *
     * @param text The file's content.
     * @param origin Name used in error messages.
     * @return The entries, in the order written; {@code null} when the file names no list.
     * @throws ProjectException If the file does not parse or an entry is none of a signer.
     */
    public static @Nullable List<Signer> entries(final String text, final String origin) {
        final Object loaded = ProjectReader.load(new StringReader(text), origin);
        return loaded instanceof Map<?, ?> root && root.get("project") instanceof Map<?, ?> project
                ? read(project, origin)
                : null;
    }

    /**
     * Returns the keys that sign, without the backup keys.
     *
     * @param entries The entries.
     * @return The keys, in order.
     */
    public static List<String> keys(final List<Signer> entries) {
        return entries.stream().filter(entry -> !entry.backup()).map(Signer::key).toList();
    }

    /**
     * Returns the backup keys.
     *
     * @param entries The entries.
     * @return The backup entries, in order.
     */
    public static List<Signer> backups(final List<Signer> entries) {
        return entries.stream().filter(Signer::backup).toList();
    }

    /**
     * Returns the one key a project's signers name with no backup key behind it, when it is the
     * only key that signs: lost, nobody could sign the change that replaces it.
     *
     * @param entries The entries.
     * @return The key, or {@code null} when there are several, none, or it has a backup.
     */
    public static @Nullable String soleWithoutBackup(final List<Signer> entries) {
        final List<String> keys = keys(entries);
        if (keys.size() != 1) {
            return null;
        }
        final String sole = fingerprint(keys.getFirst());
        return backups(entries).stream().anyMatch(backup -> backup.backs().equals(sole))
                ? null
                : keys.getFirst();
    }

    static @Nullable List<Signer> read(final Map<?, ?> project, final String origin) {
        final Object value = project.get("signers");
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> listed)) {
            throw new ProjectException(origin + ": 'project.signers' is a list of public keys");
        }
        final List<Signer> entries = new ArrayList<>();
        final Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < listed.size(); i++) {
            final Object entry = listed.get(i);
            final Signer signer =
                    entry instanceof Map<?, ?> backup
                            ? backup(backup, origin, i + 1)
                            : new Signer(key(String.valueOf(entry), origin, i + 1), false, "", "");
            if (!seen.add(signer.key())) {
                if (signer.backup()
                        || entries.stream()
                                .anyMatch(
                                        each -> each.backup() && each.key().equals(signer.key()))) {
                    throw new ProjectException(
                            origin
                                    + ": 'project.signers' entry "
                                    + (i + 1)
                                    + " names a key already listed; a backup key is no other"
                                    + " entry's key");
                }
                continue;
            }
            entries.add(signer);
        }
        final Set<String> backed = new LinkedHashSet<>();
        for (final Signer backup : backups(entries)) {
            if (keys(entries).stream().noneMatch(key -> fingerprint(key).equals(backup.backs()))) {
                throw new ProjectException(
                        origin
                                + ": the backup key "
                                + backup.fingerprint()
                                + " in 'project.signers' backs "
                                + backup.backs()
                                + ", which is no key listed there");
            }
            if (!backed.add(backup.backs())) {
                throw new ProjectException(
                        origin
                                + ": 'project.signers' names a second backup key for "
                                + backup.backs()
                                + "; a key has one backup key at most");
            }
        }
        return List.copyOf(entries);
    }

    private static Signer backup(final Map<?, ?> entry, final String origin, final int number) {
        for (final Object field : entry.keySet()) {
            if (!BACKUP_FIELDS.contains(String.valueOf(field))) {
                throw new ProjectException(
                        origin
                                + ": 'project.signers' entry "
                                + number
                                + " has '"
                                + field
                                + "'; a backup key names key, backup, backs and since");
            }
        }
        if (!Boolean.TRUE.equals(entry.get("backup"))) {
            throw new ProjectException(
                    origin
                            + ": 'project.signers' entry "
                            + number
                            + " is a mapping without 'backup: true'; a signer is written as a key"
                            + " alone");
        }
        final Object backs = entry.get("backs");
        if (!(backs instanceof String fingerprint) || !fingerprint.startsWith("SHA256:")) {
            throw new ProjectException(
                    origin
                            + ": 'project.signers' entry "
                            + number
                            + " names under 'backs' no fingerprint of the key it backs");
        }
        final Object since = entry.get("since");
        final String date =
                since == null
                        ? ""
                        : since instanceof java.util.Date day
                                ? day.toInstant()
                                        .atZone(java.time.ZoneOffset.UTC)
                                        .toLocalDate()
                                        .toString()
                                : String.valueOf(since);
        return new Signer(
                key(String.valueOf(entry.get("key")), origin, number), true, fingerprint, date);
    }

    private static String key(final String text, final String origin, final int number) {
        final String[] fields = text.strip().split("\\s+");
        for (int f = 0; f + 1 < fields.length; f++) {
            if (KEY_TYPE.matcher(fields[f]).matches() && BLOB.matcher(fields[f + 1]).matches()) {
                return fields[f] + " " + fields[f + 1];
            }
        }
        throw new ProjectException(
                origin
                        + ": 'project.signers' entry "
                        + number
                        + " is not a public key; write it as ssh-keygen prints it, such as"
                        + " 'ssh-ed25519 AAAA...'");
    }

    /**
     * Returns a key's fingerprint, as {@code ssh-keygen -l} prints it.
     *
     * @param key The key, its type and key.
     * @return {@code SHA256:...}, or the key itself when it does not decode.
     */
    public static String fingerprint(final String key) {
        final String[] fields = key.strip().split("\\s+");
        try {
            final byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(Base64.getDecoder().decode(fields.length > 1 ? fields[1] : ""));
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (final NoSuchAlgorithmException | IllegalArgumentException ex) {
            return key;
        }
    }

    /**
     * Judges a change signed by a backup key: it may change nothing but {@code project.signers},
     * and there only replace the key it backs by one new key, and point itself at that one.
     *
     * @param before The project file before the change.
     * @param after The project file after it.
     * @param backupKey The key that signed it.
     * @return {@code null} when the change is that one, otherwise what else it changes.
     */
    public static @Nullable String backedOnly(
            final String before, final String after, final String backupKey) {
        final Object was = ProjectReader.load(new StringReader(before), "project.yml before");
        final Object is = ProjectReader.load(new StringReader(after), "project.yml after");
        if (!withoutSigners(was).equals(withoutSigners(is))) {
            return "changes project.yml beyond project.signers";
        }
        final List<Signer> old = entries(before, "project.yml before");
        final List<Signer> next = entries(after, "project.yml after");
        if (old == null || next == null) {
            return "removes project.signers";
        }
        final Signer backup =
                backups(old).stream()
                        .filter(entry -> entry.key().equals(backupKey))
                        .findFirst()
                        .orElse(null);
        if (backup == null) {
            return "is signed by " + fingerprint(backupKey) + ", no backup key it names";
        }
        final Set<String> kept = new LinkedHashSet<>(keys(old));
        kept.removeIf(key -> fingerprint(key).equals(backup.backs()));
        final Set<String> added = new LinkedHashSet<>(keys(next));
        added.removeAll(kept);
        if (!new LinkedHashSet<>(keys(next)).containsAll(kept)
                || added.size() != 1
                || keys(old).containsAll(added)) {
            return "changes project.signers beyond replacing "
                    + backup.backs()
                    + ", the key it backs, by one new key";
        }
        final String replacement = fingerprint(added.iterator().next());
        final Set<Signer> expected = new LinkedHashSet<>();
        for (final Signer each : backups(old)) {
            expected.add(
                    each.equals(backup)
                            ? new Signer(each.key(), true, replacement, each.since())
                            : each);
        }
        if (!expected.equals(new LinkedHashSet<>(backups(next)))) {
            return "changes the backup keys of project.signers beyond pointing "
                    + backup.fingerprint()
                    + " at "
                    + replacement;
        }
        return null;
    }

    private static Object withoutSigners(final @Nullable Object root) {
        if (!(root instanceof Map<?, ?> map)) {
            return String.valueOf(root);
        }
        final Map<Object, @Nullable Object> copy = new LinkedHashMap<>(map);
        if (copy.get("project") instanceof Map<?, ?> project) {
            final Map<Object, @Nullable Object> section = new LinkedHashMap<>(project);
            section.remove("signers");
            copy.put("project", section);
        }
        return copy;
    }

    /**
     * Adds a backup key to the file's {@code project.signers}, as text, after its last entry.
     *
     * @param text The project file.
     * @param key The backup key, its type and key.
     * @param backs The fingerprint of the key it backs.
     * @param since The date it is filed.
     * @return The file with the entry added.
     * @throws ProjectException If the file has no block list of signers to add it to.
     */
    public static String withBackup(
            final String text, final String key, final String backs, final String since) {
        final Block block = block(text);
        final String indent = " ".repeat(block.indent());
        final List<String> lines = new ArrayList<>(block.lines());
        lines.addAll(
                block.end(),
                List.of(
                        indent + "- key: \"" + key + "\"",
                        indent + "  backup: true",
                        indent + "  backs: \"" + backs + "\"",
                        indent + "  since: \"" + since + "\""));
        return String.join("\n", lines);
    }

    /**
     * Replaces a key of the file's {@code project.signers} by another, as text, and points the
     * backup key that backed it at the new one.
     *
     * @param text The project file.
     * @param old The key replaced.
     * @param next The key that replaces it.
     * @param backupKey The backup key that backs it.
     * @return The file with the key replaced.
     * @throws ProjectException If the key is not one entry's line alone, or the backup key's entry
     *     has no {@code backs} line.
     */
    public static String replacing(
            final String text, final String old, final String next, final String backupKey) {
        final Block block = block(text);
        final List<String> lines = new ArrayList<>(block.lines());
        final String indent = " ".repeat(block.indent());
        final String oldBlob = old.split(" ")[1];
        final String backupBlob = backupKey.split(" ")[1];
        int replaced = -1;
        int backs = -1;
        int item = -1;
        boolean backupItem = false;
        for (int at = block.start(); at < block.end(); at++) {
            final String line = lines.get(at);
            final Matcher matched = ITEM.matcher(line);
            if (matched.matches() && matched.group(1).length() == block.indent()) {
                item = at;
                backupItem = false;
                if (line.contains(oldBlob) && !matched.group(2).startsWith("key:")) {
                    if (replaced >= 0) {
                        throw new ProjectException("project.signers names " + old + " twice");
                    }
                    replaced = at;
                }
            } else if (item == replaced && replaced >= 0 && !skipped(line)) {
                throw new ProjectException(
                        "the entry of " + fingerprint(old) + " in project.signers is not one line");
            }
            if (item >= 0 && line.contains(backupBlob)) {
                backupItem = true;
            }
            if (backupItem && line.strip().replaceFirst("^- ", "").startsWith("backs:")) {
                backs = at;
            }
        }
        if (replaced < 0 || backs < 0) {
            throw new ProjectException(
                    "project.signers holds no line of "
                            + fingerprint(old)
                            + " alone, or no backs line of the backup key behind it");
        }
        lines.set(replaced, indent + "- \"" + next + "\"");
        final String line = lines.get(backs);
        lines.set(
                backs,
                line.substring(0, line.indexOf("backs:")) + "backs: \"" + fingerprint(next) + "\"");
        return String.join("\n", lines);
    }

    /**
     * Where the block list of signers is in the file's lines.
     *
     * @param lines The file's lines.
     * @param start The first entry's line.
     * @param end The line after the last entry.
     * @param indent The entries' indentation.
     */
    private record Block(List<String> lines, int start, int end, int indent) {}

    private static Block block(final String text) {
        final List<String> lines = List.of(text.split("\n", -1));
        int at = 0;
        while (at < lines.size() && !lines.get(at).matches("project:\\s*(#.*)?")) {
            at++;
        }
        int signers = -1;
        for (at++; at < lines.size() && signers < 0; at++) {
            final String line = lines.get(at);
            if (!line.isBlank() && !line.startsWith(" ") && !line.startsWith("#")) {
                break;
            }
            if (line.matches(" +signers:\\s*(#.*)?")) {
                signers = at;
            }
        }
        if (signers < 0) {
            throw new ProjectException(
                    "project.yml has no block list under project.signers to write into");
        }
        final int keyIndent = lines.get(signers).indexOf('s');
        int start = signers + 1;
        while (start < lines.size() && skipped(lines.get(start))) {
            start++;
        }
        final Matcher first = start < lines.size() ? ITEM.matcher(lines.get(start)) : null;
        if (first == null || !first.matches() || first.group(1).length() < keyIndent) {
            throw new ProjectException(
                    "project.signers is not a block list of entries, one '- ' each");
        }
        final int indent = first.group(1).length();
        int end = start;
        for (int each = start; each < lines.size(); each++) {
            final String line = lines.get(each);
            if (skipped(line)) {
                continue;
            }
            final int depth = line.length() - line.stripLeading().length();
            if (depth < indent || (depth == indent && !line.stripLeading().startsWith("-"))) {
                break;
            }
            end = each + 1;
        }
        return new Block(lines, start, end, indent);
    }

    private static boolean skipped(final String line) {
        return line.isBlank() || line.stripLeading().startsWith("#");
    }
}
