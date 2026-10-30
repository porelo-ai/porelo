package org.fuin.porelo.core.project;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads a {@code project.yml} into a {@link Project}.
 *
 * <p>The YAML is loaded as plain maps with {@link SafeConstructor} and mapped by hand. Binding
 * straight onto the record would need reflection metadata in a native image and would let the file
 * name any class on the classpath; neither is worth the few lines it saves.
 *
 * <p>This reader never writes. Editing a project file is a separate, patch-only operation, so that
 * comments and formatting survive - see constraint C4.
 */
public final class ProjectReader {

    private ProjectReader() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Reads a project definition from a file.
     *
     * @param file Path to {@code project.yml}.
     * @return The project.
     * @throws ProjectException If the file cannot be read or does not describe a usable project.
     */
    public static Project read(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new ProjectException(
                    "No project file at "
                            + file
                            + ". Run 'porelo task run' in a terminal and it will offer to write one.");
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            // Absolute, because the message is read somewhere else than it was typed: "project.yml
            // has no 'project' section" leaves a reader guessing which project.yml, and a person
            // driving this over a socket or from a script has no working directory in front of them
            // at all.
            return read(reader, file.toAbsolutePath().toString());
        } catch (IOException ex) {
            throw new ProjectException("Cannot read " + file, ex);
        }
    }

    /**
     * Returns the keys each section of a project file may hold, as this Porelo knows them.
     *
     * @return Section to its keys; {@code ""} is the top level.
     */
    public static Map<String, java.util.List<String>> schema() {
        return ProjectSchema.sections();
    }

    /**
     * Returns every key a project file may hold, each described - what it means, its type, the
     * values it takes, its default and whether it is required - for an editor a person new to
     * Porelo can fill.
     *
     * @return The keys, in the order a first project file is written in.
     */
    public static java.util.List<ProjectKey> keys() {
        return ProjectSchema.keys();
    }

    /**
     * Returns the places where a project file holds what is not Porelo's to know: the project's own
     * names ({@code credentials}, {@code repositories}) and each transport's settings ({@code
     * mail.transports.<scheme>}).
     *
     * @return Their paths.
     */
    public static java.util.List<String> openSections() {
        return java.util.List.of("credentials", "repositories", "mail.transports.<scheme>");
    }

    /**
     * Returns the keys a project file holds that this Porelo does not know and that are no provable
     * mistake - a later Porelo's, most likely - to be warned about. What is certainly a mistake is
     * refused by {@link #read(Reader, String)}.
     *
     * @param file The project file.
     * @return Where each such key is, as {@code section.key}; empty when there is none or the file
     *     cannot be read.
     */
    public static java.util.List<String> unknownKeys(java.nio.file.Path file) {
        try (Reader reader =
                java.nio.file.Files.newBufferedReader(
                        file, java.nio.charset.StandardCharsets.UTF_8)) {
            return unknownKeys(reader, file.toString());
        } catch (java.io.IOException | RuntimeException ex) {
            return java.util.List.of();
        }
    }

    /**
     * Returns the keys a project file holds that this Porelo does not know and that are no provable
     * mistake.
     *
     * @param reader The file's content.
     * @param origin Name used in error messages.
     * @return Where each such key is.
     */
    public static java.util.List<String> unknownKeys(Reader reader, String origin) {
        final LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        final Object loaded = new Yaml(new SafeConstructor(options)).load(reader);
        return loaded instanceof Map<?, ?> root
                ? ProjectSchema.check(root, origin)
                : java.util.List.of();
    }

    /**
     * Reads a project definition from a reader.
     *
     * @param reader Source of the YAML.
     * @param origin Name used in error messages.
     * @return The project.
     * @throws ProjectException If the content does not describe a usable project.
     */
    public static Project read(Reader reader, String origin) {

        final LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);

        final Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(options)).load(reader);
        } catch (RuntimeException ex) {
            throw new ProjectException("Cannot parse " + origin + ": " + ex.getMessage(), ex);
        }
        if (!(loaded instanceof Map<?, ?> root)) {
            throw new ProjectException(origin + " is empty or is not a YAML mapping");
        }

        // Before anything is read from it: a setting in the wrong place, or misspelt, is no setting
        // at all, and the file looked applied (found on 2026-09-30, with a key one section too
        // low).
        ProjectSchema.check(root, origin);

        final Map<?, ?> project = section(root, "project", origin);
        final Map<?, ?> image = section(root, "image", origin);
        signers(project, origin);
        final Access access = access(root, origin);
        final Mail mail = mail(root, origin);
        if (access != null && !mail.transports().isEmpty()) {
            // Read from the public file once an access file is named, a server's name and room
            // would be shown to everyone the access file was meant to hide them from.
            throw new ProjectException(
                    origin
                            + ": 'mail.transports' is in the access file once 'access' names one;"
                            + " move "
                            + String.join(", ", mail.transports().keySet())
                            + " into "
                            + Access.FILE);
        }

        final Project definition =
                new Project(
                        required(project, "name", origin, "project"),
                        text(project.get("description")),
                        SecurityClass.parse(required(project, "security_class", origin, "project")),
                        image(required(image, "base_image", origin, "image")),
                        snippet(image, origin),
                        text(project.get("upstream")).isEmpty()
                                ? null
                                : address(text(project.get("upstream"))),
                        limits(root, origin),
                        egress(root, origin),
                        packageSources(image, origin),
                        mail,
                        repositories(root, origin),
                        credentials(root, origin),
                        builds(
                                root,
                                origin,
                                SecurityClass.parse(
                                        required(project, "security_class", origin, "project"))),
                        null,
                        parameters(root, origin),
                        projectNickname(project, origin),
                        branch(root.get("branch"), "branch", origin, null),
                        access);
        for (final Repository repository : definition.repositories()) {
            definition.egressFor(repository);
        }
        goesByOnce(definition, origin);
        return definition;
    }

    /**
     * Returns the keys a project file names under {@code project.signers}, each as its type and
     * key.
     *
     * <p>Read on its own, because a followed project's keys are read from a commit before anything
     * else in the file is taken: whether the commit may change them is decided by the keys in force
     * before it.
     *
     * @param text The file's content.
     * @param origin Name used in error messages.
     * @return The keys, in the order written; {@code null} when the file names none.
     * @throws ProjectException If the file does not parse or an entry is no public key.
     */
    public static java.util.@Nullable List<String> signers(final String text, final String origin) {
        final LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        final Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(options)).load(text);
        } catch (RuntimeException ex) {
            throw new ProjectException("Cannot parse " + origin + ": " + ex.getMessage(), ex);
        }
        return loaded instanceof Map<?, ?> root && root.get("project") instanceof Map<?, ?> project
                ? signers(project, origin)
                : null;
    }

    private static java.util.@Nullable List<String> signers(
            final Map<?, ?> project, final String origin) {
        final Object value = project.get("signers");
        if (value == null) {
            return null;
        }
        if (!(value instanceof java.util.List<?> entries)) {
            throw new ProjectException(origin + ": 'project.signers' is a list of public keys");
        }
        final java.util.List<String> keys = new java.util.ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            final String[] fields = text(entries.get(i)).strip().split("\\s+");
            String key = null;
            for (int f = 0; f + 1 < fields.length && key == null; f++) {
                if (fields[f].matches(
                                "ssh-(ed25519|rsa)|ecdsa-sha2-nistp(256|384|521)|sk-[a-z0-9@.-]+")
                        && fields[f + 1].matches("[A-Za-z0-9+/]+={0,2}")) {
                    key = fields[f] + " " + fields[f + 1];
                }
            }
            if (key == null) {
                throw new ProjectException(
                        origin
                                + ": 'project.signers' entry "
                                + (i + 1)
                                + " is not a public key; write it as ssh-keygen prints it, such as 'ssh-ed25519 AAAA...'");
            }
            if (!keys.contains(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    /**
     * Reads the optional {@code access} section: where the project keeps its access file.
     *
     * @param root The whole document.
     * @param origin Name used in error messages.
     * @return The place, or {@code null} when the section is absent.
     * @throws ProjectException Unless it names exactly one of {@code repository} and {@code file},
     *     and nothing else.
     */
    private static @Nullable Access access(Map<?, ?> root, String origin) {
        final Object value = root.get("access");
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> access)) {
            throw new ProjectException(
                    origin
                            + ": 'access' must be a mapping naming 'repository: <url>' or 'file: "
                            + Access.FILE
                            + "'");
        }
        for (final Object key : access.keySet()) {
            if (!"repository".equals(key) && !"file".equals(key)) {
                throw new ProjectException(
                        origin
                                + ": 'access."
                                + String.valueOf(key).replaceAll("\\p{Cntrl}", "?")
                                + "' is not known; 'access' names 'repository' or 'file'");
            }
        }
        final Object repository = access.get("repository");
        final Object file = access.get("file");
        if ((repository == null) == (file == null)) {
            throw new ProjectException(
                    origin
                            + ": 'access' names "
                            + (repository == null
                                    ? "neither 'repository' nor 'file'"
                                    : "both 'repository' and 'file'")
                            + "; it names exactly one place for "
                            + Access.FILE);
        }
        if (!((repository == null ? file : repository) instanceof String place)) {
            throw new ProjectException(
                    origin
                            + ": 'access."
                            + (repository == null ? "file" : "repository")
                            + "' is a string");
        }
        try {
            return repository == null
                    ? new Access.BesideProject(place)
                    : new Access.InRepository(place);
        } catch (final ProjectException ex) {
            throw new ProjectException(origin + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * Reads the optional {@code credentials} section: a vault entry's name to the destination it is
     * for.
     *
     * @param root The file's root mapping.
     * @param origin Where it came from, for messages.
     * @return The credentials, in the order written; empty when the section is absent.
     */
    /**
     * Reads the optional {@code builds} section, refusing it outside an {@code online} project.
     *
     * @param root The whole document.
     * @param origin Name used in error messages.
     * @param securityClass The project's class.
     * @return What it declared, or {@code null} when it declared nothing.
     */
    /** Reads {@code parameters}: the values a task of the project may be started with. */
    private static Parameters parameters(Map<?, ?> root, String origin) {
        final Object block = root.get("parameters");
        if (block == null) {
            return Parameters.none();
        }
        if (!(block instanceof Map<?, ?> declared)) {
            throw new ProjectException(
                    origin + ": 'parameters' must be a mapping of name to declaration");
        }
        final Map<String, Parameter> read = new java.util.LinkedHashMap<>();
        for (final Map.Entry<?, ?> entry : declared.entrySet()) {
            final String name = String.valueOf(entry.getKey());
            final Map<?, ?> one =
                    entry.getValue() == null
                            ? Map.of()
                            : entry.getValue() instanceof Map<?, ?> map ? map : null;
            if (one == null) {
                throw new ProjectException(
                        origin + ": 'parameters." + name + "' must be a mapping");
            }
            final Object values = one.get("values");
            if (values != null && !(values instanceof java.util.List<?>)) {
                throw new ProjectException(
                        origin + ": 'parameters." + name + ".values' must be a list");
            }
            final String env = one.get("env") == null ? null : String.valueOf(one.get("env"));
            try {
                read.put(
                        name,
                        new Parameter(
                                name,
                                text(one.get("description")),
                                values == null
                                        ? null
                                        : ((java.util.List<?>) values)
                                                .stream().map(String::valueOf).toList(),
                                one.get("pattern") == null
                                        ? null
                                        : String.valueOf(one.get("pattern")),
                                one.get("default") == null
                                        ? null
                                        : String.valueOf(one.get("default")),
                                Boolean.TRUE.equals(one.get("required")),
                                env == null
                                        ? Parameter.defaultEnv(name)
                                        : Parameter.NO_ENV.equals(env) ? null : env));
            } catch (ProjectException ex) {
                throw new ProjectException(origin + ": " + ex.getMessage(), ex);
            }
        }
        try {
            return new Parameters(read);
        } catch (ProjectException ex) {
            throw new ProjectException(origin + ": " + ex.getMessage(), ex);
        }
    }

    private static @Nullable Builds builds(
            Map<?, ?> root, String origin, SecurityClass securityClass) {
        final Object value = root.get("builds");
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> builds)) {
            throw new ProjectException(origin + ": 'builds' must be a mapping");
        }
        if (securityClass != SecurityClass.ONLINE) {
            throw new ProjectException(
                    origin
                            + ": 'builds' is for online projects only - in guarded the forge"
                            + " builds nothing until a person approves the work at the gate, and offline has no build");
        }
        try {
            return new Builds(
                    text(builds.get("forge")),
                    text(builds.get("credential")),
                    text(builds.get("api")),
                    text(builds.get("logs")).isEmpty() ? Builds.FAILURE : text(builds.get("logs")));
        } catch (ProjectException ex) {
            throw new ProjectException(origin + ": " + ex.getMessage());
        }
    }

    private static Map<String, String> credentials(Map<?, ?> root, String origin) {
        final Object value = root.get("credentials");
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> named)) {
            throw new ProjectException(
                    origin
                            + ": 'credentials' must be a mapping of a vault entry's name to its"
                            + " destination, for example 'search: brave-search'");
        }
        final Map<String, String> credentials = new java.util.LinkedHashMap<>();
        named.forEach(
                (entry, destination) ->
                        credentials.put(String.valueOf(entry), String.valueOf(destination)));
        return credentials;
    }

    /**
     * Reads the optional {@code repositories} section, which names the work repositories.
     *
     * <p>Absent means the project has only its own, which is what a project still being planned
     * looks like. A mapping rather than a list because the name is the key a person types at {@code
     * porelo task start}, and a list of mappings each carrying its own {@code name} would let two
     * entries claim the same one without YAML noticing.
     *
     * @param root The whole document.
     * @param origin Name used in error messages.
     * @return What the project declared, empty when it declared nothing.
     */
    private static java.util.List<Repository> repositories(Map<?, ?> root, String origin) {
        final Object value = root.get("repositories");
        if (value == null) {
            return java.util.List.of();
        }
        if (!(value instanceof Map<?, ?> declared)) {
            throw new ProjectException(
                    origin + ": 'repositories' must be a mapping of name to repository");
        }
        final java.util.List<Repository> read = new java.util.ArrayList<>();
        for (final Map.Entry<?, ?> entry : declared.entrySet()) {
            final String name = String.valueOf(entry.getKey());
            if (entry.getValue() == null) {
                // A name with nothing under it is a repository with no upstream, which is a
                // repository whose work stays here. Legitimate, so it is read rather than refused.
                read.add(new Repository(name, null));
                continue;
            }
            if (!(entry.getValue() instanceof Map<?, ?> repository)) {
                throw new ProjectException(
                        origin + ": 'repositories." + name + "' must be a mapping with 'upstream'");
            }
            maturity(repository, origin, name);
            read.add(
                    new Repository(
                            name,
                            text(repository.get("upstream")).isEmpty()
                                    ? null
                                    : text(repository.get("upstream")),
                            text(repository.get("description")),
                            egress(repository, origin, "repositories." + name + "."),
                            declaredLimits(repository, origin, name),
                            issues(repository, origin, name),
                            repositoryImage(repository, origin, name),
                            word(repository, "agent", origin, name),
                            word(repository, "nickname", origin, name),
                            branch(
                                    repository.get("branch"),
                                    "repositories." + name + ".branch",
                                    origin,
                                    null)));
        }
        // A prefix tells whose an issue is at a glance only while one repository owns it.
        final Map<String, String> owner = new java.util.LinkedHashMap<>();
        for (final Repository repository : read) {
            for (final String prefix : repository.issues()) {
                final String other = owner.putIfAbsent(prefix, repository.name());
                if (other != null) {
                    throw new ProjectException(
                            origin
                                    + ": the issue prefix '"
                                    + prefix
                                    + "' is named by both '"
                                    + other
                                    + "' and '"
                                    + repository.name()
                                    + "'; a prefix belongs to one repository");
                }
            }
        }
        return java.util.List.copyOf(read);
    }

    /**
     * Reads a repository's optional {@code agent} or {@code nickname}, which the repository checks.
     *
     * @param repository The repository's section.
     * @param key Which of the two.
     * @param origin Name used in error messages.
     * @param name The repository's name.
     * @return The value as written, or {@code null} when the key is absent.
     */
    private static @Nullable String word(
            Map<?, ?> repository, String key, String origin, String name) {
        final Object value = repository.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String written)) {
            throw new ProjectException(
                    origin + ": 'repositories." + name + "." + key + "' is one word, a string");
        }
        try {
            new Repository(
                    name,
                    null,
                    "",
                    Egress.none(),
                    Limits.Declared.none(),
                    java.util.List.of(),
                    null,
                    "agent".equals(key) ? written : null,
                    "nickname".equals(key) ? written : null);
            return written;
        } catch (final ProjectException ex) {
            throw new ProjectException(origin + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * Reads a {@code branch} block: how a task's branch at the forge is named, and which names the
     * forge accepts.
     *
     * @param value What the file holds there, or {@code null}.
     * @param where The block's path, for messages.
     * @param origin Name used in error messages.
     * @param absent What an absent block means.
     * @return The scheme, or {@code absent}.
     */
    private static @Nullable BranchScheme branch(
            @Nullable Object value, String where, String origin, @Nullable BranchScheme absent) {
        if (value == null) {
            return absent;
        }
        if (!(value instanceof Map<?, ?> block)) {
            throw new ProjectException(
                    origin + ": '" + where + "' is a mapping with 'template' and 'pattern'");
        }
        final Object template = block.get("template");
        final Object pattern = block.get("pattern");
        if (template != null && !(template instanceof String)
                || pattern != null && !(pattern instanceof String)) {
            throw new ProjectException(
                    origin + ": '" + where + ".template' and '.pattern' are strings");
        }
        if (template != null && pattern == null) {
            // The pattern is also what the gate keeps out of the mirror an agent fetches from.
            throw new ProjectException(
                    origin
                            + ": '"
                            + where
                            + "' names a template and no pattern; name the pattern of the"
                            + " branches the forge accepts too");
        }
        try {
            return new BranchScheme(
                    template == null ? BranchScheme.DEFAULT.template() : (String) template,
                    pattern == null ? BranchScheme.DEFAULT.pattern() : (String) pattern);
        } catch (final ProjectException ex) {
            throw new ProjectException(origin + ": '" + where + "': " + ex.getMessage(), ex);
        }
    }

    /**
     * Reads the optional {@code project.nickname}, whose shape the project checks.
     *
     * @param project The project section.
     * @param origin Name used in error messages.
     * @return The nickname, or {@code null} when the key is absent.
     */
    private static @Nullable String projectNickname(Map<?, ?> project, String origin) {
        final Object value = project.get("nickname");
        if (value == null) {
            return null;
        }
        if (!(value instanceof String written)) {
            throw new ProjectException(origin + ": 'project.nickname' is one word, a string");
        }
        return written;
    }

    /**
     * Refuses two repositories of one project that would go by the same name.
     *
     * <p>Compared without regard to case, since people do not tell {@code Agent Pi} from {@code
     * Agent PI} apart in a room; the project's own repository goes by the project's name.
     *
     * @param project The project as read.
     * @param origin Name used in error messages.
     * @throws ProjectException If two go by one name.
     */
    private static void goesByOnce(Project project, String origin) {
        final Map<String, Repository> taken = new java.util.LinkedHashMap<>();
        for (final Repository repository : project.allRepositories()) {
            final Repository other =
                    taken.putIfAbsent(
                            repository.goesBy().toLowerCase(java.util.Locale.ROOT), repository);
            if (other != null) {
                throw new ProjectException(
                        origin
                                + ": '"
                                + other.name()
                                + "' and '"
                                + repository.name()
                                + "' would both go by '"
                                + repository.goesBy()
                                + "'; a nickname may not repeat another repository's nickname or"
                                + " name, whatever the case");
            }
        }
    }

    /** Refuses a {@code maturity} that is not one of the four words; Porelo keeps nothing of it. */
    private static void maturity(Map<?, ?> repository, String origin, String name) {
        final Object value = repository.get("maturity");
        if (value != null && !ProjectSchema.MATURITIES.contains(String.valueOf(value))) {
            throw new ProjectException(
                    origin
                            + ": 'repositories."
                            + name
                            + ".maturity' is one of "
                            + String.join(", ", ProjectSchema.MATURITIES)
                            + ", not '"
                            + value
                            + "'");
        }
    }

    private static java.util.List<String> issues(Map<?, ?> repository, String origin, String name) {
        final Object value = repository.get("issues");
        if (value == null) {
            return java.util.List.of();
        }
        if (!(value instanceof java.util.List<?> listed)) {
            throw new ProjectException(
                    origin
                            + ": 'repositories."
                            + name
                            + ".issues' is a list of issue prefixes,"
                            + " such as [\"B\", \"P\"]");
        }
        try {
            return new Repository(
                            name,
                            null,
                            "",
                            Egress.none(),
                            Limits.Declared.none(),
                            listed.stream().map(String::valueOf).toList())
                    .issues();
        } catch (final ProjectException ex) {
            throw new ProjectException(origin + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * Reads the optional {@code image.package_sources} list.
     *
     * <p>Where apt fetches from while the image is built. A project that names none gets {@link
     * Project#DEFAULT_PACKAGE_SOURCES} at the point of use; {@code null} here means the project
     * named none, which is not the same as naming the default.
     *
     * @param image The image section.
     * @param origin Name used in error messages.
     * @return What the project declared, or the default.
     */
    private static java.util.@org.jspecify.annotations.Nullable List<String> packageSources(
            Map<?, ?> image, String origin) {
        final Object value = image.get("package_sources");
        if (value == null) {
            return null;
        }
        if (!(value instanceof java.util.List<?> list)) {
            throw new ProjectException(origin + ": 'image.package_sources' is a list of URLs");
        }
        return list.stream().map(ProjectReader::text).toList();
    }

    /**
     * Reads a peer's optional {@code per_day}.
     *
     * @param peer The peer's mapping.
     * @param name Its name, for error messages.
     * @param origin Name used in error messages.
     * @return What it declared, or the default.
     */
    private static int perDay(Map<?, ?> peer, String name, String origin) {
        final Object value = peer.get("per_day");
        if (value == null) {
            return Mail.Peer.DEFAULT_PER_DAY;
        }
        if (!(value instanceof Number number)) {
            throw new ProjectException(
                    origin + ": 'mail.peers." + name + ".per_day' is a number of messages a day");
        }
        return number.intValue();
    }

    /**
     * Reads the optional {@code mail} section, which names the peers a task may address.
     *
     * @param root The whole document.
     * @param origin Name used in error messages.
     * @return What the project declared, empty when it declared nothing.
     */
    private static Mail mail(Map<?, ?> root, String origin) {
        final Object value = root.get("mail");
        if (value == null) {
            return Mail.none();
        }
        if (!(value instanceof Map<?, ?> mail)) {
            throw new ProjectException(origin + ": 'mail' must be a mapping");
        }
        final Map<String, Object> transports = transports(mail, origin);
        final boolean reported = outgoingReported(mail, origin);
        final Mail.Rules rules = rules(mail, origin);
        final Object peers = mail.get("peers");
        if (peers == null) {
            return new Mail(java.util.List.of(), transports, reported, rules);
        }
        if (!(peers instanceof Map<?, ?> declared)) {
            throw new ProjectException(origin + ": 'mail.peers' must be a mapping of name to peer");
        }
        final java.util.List<Mail.Peer> read = new java.util.ArrayList<>();
        for (final Map.Entry<?, ?> entry : declared.entrySet()) {
            final String name = String.valueOf(entry.getKey());
            if (!(entry.getValue() instanceof Map<?, ?> peer)) {
                throw new ProjectException(
                        origin
                                + ": 'mail.peers."
                                + name
                                + "' must be a mapping with 'address' and 'trust'");
            }
            read.add(
                    new Mail.Peer(
                            name,
                            text(peer.get("address")),
                            // Vouched is the narrower promise - it skips the check on the way in -
                            // so an omitted trust level is the wider one rather than the convenient
                            // one.
                            text(peer.get("trust")).isEmpty()
                                    ? Mail.Peer.EXTERNAL
                                    : text(peer.get("trust")),
                            perDay(peer, name, origin),
                            text(peer.get("mode")).isEmpty() ? null : text(peer.get("mode"))));
        }
        return new Mail(java.util.List.copyOf(read), transports, reported, rules);
    }

    /**
     * Reads the optional {@code mail.rules}: a mode for the project's own tasks, its conversation
     * and everyone else.
     *
     * @param mail The mail section.
     * @param origin Name used in error messages.
     * @return What the file set; what it leaves out keeps its default.
     */
    private static Mail.Rules rules(Map<?, ?> mail, String origin) {
        final Object value = mail.get(Mail.RULES);
        if (value == null) {
            return Mail.Rules.UNSAID;
        }
        if (!(value instanceof Map<?, ?> rules)) {
            throw new ProjectException(
                    origin
                            + ": 'mail."
                            + Mail.RULES
                            + "' must be a mapping, for example"
                            + " 'project: allow', 'room: prompt', 'others: deny'");
        }
        try {
            return new Mail.Rules(
                    mode(rules.get("project")), mode(rules.get("room")), mode(rules.get("others")));
        } catch (ProjectException ex) {
            throw new ProjectException(origin + ": " + ex.getMessage(), ex);
        }
    }

    private static @org.jspecify.annotations.Nullable String mode(
            @org.jspecify.annotations.Nullable Object value) {
        return text(value).isEmpty() ? null : text(value);
    }

    /**
     * Reads {@code mail.outgoing_filter}: {@code blocking}, the default, or {@code reporting}.
     *
     * @param mail The mail section.
     * @param origin Name used in error messages.
     * @return Whether the outgoing filter only reports.
     */
    private static boolean outgoingReported(Map<?, ?> mail, String origin) {
        final String said = text(mail.get(Mail.OUTGOING_FILTER));
        if (said.isEmpty() || "blocking".equals(said)) {
            return false;
        }
        if ("reporting".equals(said)) {
            return true;
        }
        throw new ProjectException(
                origin
                        + ": 'mail."
                        + Mail.OUTGOING_FILTER
                        + "' is '"
                        + said
                        + "'; expected blocking (the default) or reporting");
    }

    /**
     * Reads {@code mail.transports}: each transport's settings, kept as written.
     *
     * @param mail The mail section.
     * @param origin Name used in error messages.
     * @return Scheme to its settings, in the order written; empty when absent.
     */
    private static Map<String, Object> transports(Map<?, ?> mail, String origin) {
        final Object value = mail.get("transports");
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> named)) {
            throw new ProjectException(
                    origin
                            + ": 'mail.transports' must be a mapping of a transport to its"
                            + " settings");
        }
        final Map<String, Object> transports = new java.util.LinkedHashMap<>();
        for (final Map.Entry<?, ?> entry : named.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?>)) {
                throw new ProjectException(
                        origin + ": 'mail.transports." + entry.getKey() + "' must be a mapping");
            }
            final String scheme = String.valueOf(entry.getKey());
            // Named as a peer's transport is: it becomes a directory of this machine's state, and
            // clearing a project deletes files under it - '../../x' reached outside it.
            if (!scheme.matches("[a-z0-9][a-z0-9-]*")) {
                throw new ProjectException(
                        origin
                                + ": 'mail.transports."
                                + scheme
                                + "' is not a transport's name;"
                                + " expected lower-case letters, digits and hyphens");
            }
            transports.put(scheme, entry.getValue());
        }
        return java.util.Collections.unmodifiableMap(transports);
    }

    /**
     * Reads the optional {@code egress} section.
     *
     * @param root The whole document.
     * @param origin Name used in error messages.
     * @return What the project declared, empty when it declared nothing.
     */
    private static Egress egress(Map<?, ?> root, String origin) {
        return egress(root, origin, "");
    }

    /**
     * Reads an {@code egress} block, at the top level or under a repository.
     *
     * <p>The same reader for both, so the two cannot come to mean different things - and so that a
     * bad set name under a repository is refused with the same message and the same rule as one at
     * the top.
     *
     * @param root The mapping the block sits in.
     * @param origin Name used in error messages.
     * @param path What to put before the key in an error message, so a reader is told which {@code
     *     egress} block was wrong.
     * @return What was declared, empty when nothing was.
     */
    private static Egress egress(Map<?, ?> root, String origin, String path) {
        final Object value = root.get("egress");
        if (value == null) {
            return Egress.none();
        }
        if (!(value instanceof Map<?, ?> egress)) {
            throw new ProjectException(origin + ": '" + path + "egress' must be a mapping");
        }
        return new Egress(
                strings(egress.get("sets"), path + "egress.sets", origin),
                strings(egress.get("domains"), path + "egress.domains", origin),
                strings(egress.get("refused"), path + "egress.refused", origin),
                strings(egress.get("host_services"), path + "egress.host_services", origin));
    }

    /**
     * Reads a repository's optional {@code limits} block.
     *
     * <p><strong>Absent keys stay absent.</strong> Unlike the project's limits, which fall back to
     * {@link Limits#defaults()} key by key, a key nobody wrote here has to remain unwritten - it
     * means "the project's", and resolving it to a default at this point would quietly undo a
     * project that had deliberately raised one.
     *
     * @param repository The repository's mapping.
     * @param origin Name used in error messages.
     * @param name The repository's name, for error messages.
     * @return What it declared, all absent when it declared nothing.
     */
    private static Limits.Declared declaredLimits(
            Map<?, ?> repository, String origin, String name) {
        final Object value = repository.get("limits");
        if (value == null) {
            return Limits.Declared.none();
        }
        if (!(value instanceof Map<?, ?> limits)) {
            throw new ProjectException(
                    origin + ": 'repositories." + name + ".limits' must be a mapping");
        }
        final Object pids = limits.get("pids");
        if (pids != null && !(pids instanceof Number)) {
            throw new ProjectException(
                    origin
                            + ": 'repositories."
                            + name
                            + ".limits.pids' must be a number, not '"
                            + pids
                            + "'");
        }
        return new Limits.Declared(
                limits.get("memory") == null ? null : text(limits.get("memory")),
                limits.get("cpus") == null ? null : text(limits.get("cpus")),
                pids == null ? null : ((Number) pids).intValue(),
                limits.get("hand_in") == null
                        ? null
                        : Limits.bytes(
                                text(limits.get("hand_in")),
                                origin + ": 'repositories." + name + ".limits.hand_in'"));
    }

    /**
     * Reads a list of strings, refusing anything that is not one.
     *
     * <p>A scalar is refused rather than wrapped: {@code sets: maven} is a plausible typo for
     * {@code sets: [maven]}, and accepting both would make the file's meaning depend on a detail of
     * YAML rather than on what it says.
     *
     * @param value Raw value, may be {@code null}.
     * @param key Key path used in error messages.
     * @param origin Name used in error messages.
     * @return The entries, empty when the key is absent.
     */
    private static java.util.List<String> strings(
            @org.jspecify.annotations.Nullable Object value, String key, String origin) {
        if (value == null) {
            return java.util.List.of();
        }
        if (!(value instanceof java.util.List<?> list)) {
            throw new ProjectException(origin + ": '" + key + "' must be a list");
        }
        final java.util.List<String> found = new java.util.ArrayList<>();
        for (final Object entry : list) {
            if (!(entry instanceof String text) || text.isBlank()) {
                throw new ProjectException(
                        origin + ": '" + key + "' has an entry that is not a" + " name: " + entry);
            }
            found.add(text.strip());
        }
        return found;
    }

    private static Map<?, ?> section(Map<?, ?> root, String name, String origin) {
        final Object value = root.get(name);
        if (value == null) {
            throw new ProjectException(origin + " has no '" + name + "' section");
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new ProjectException(origin + ": '" + name + "' must be a mapping");
        }
        return map;
    }

    /**
     * Reads the optional {@code limits} section.
     *
     * @param root The whole document.
     * @param origin Name used in error messages.
     * @return Declared limits, falling back to the defaults key by key.
     */
    private static Limits limits(Map<?, ?> root, String origin) {
        if (!(root.get("limits") instanceof Map<?, ?> limits)) {
            return Limits.defaults();
        }
        final String memory = text(limits.get("memory"));
        final String cpus = text(limits.get("cpus"));
        final Object pids = limits.get("pids");
        final long handIn =
                limits.get("hand_in") == null
                        ? Limits.DEFAULT_HAND_IN
                        : Limits.bytes(text(limits.get("hand_in")), origin + ": 'limits.hand_in'");
        try {
            return new Limits(
                    // 'none' is how a project opts out on purpose, which reads differently from
                    // having forgotten to set one.
                    memory.isEmpty()
                            ? Limits.DEFAULT_MEMORY
                            : "none".equals(memory) ? null : memory,
                    cpus.isEmpty() ? Limits.DEFAULT_CPUS : "none".equals(cpus) ? null : cpus,
                    pids == null ? Limits.DEFAULT_PIDS : Integer.parseInt(String.valueOf(pids)),
                    handIn);
        } catch (NumberFormatException ex) {
            throw new ProjectException(
                    origin + ": 'limits.pids' must be a number, not '" + pids + "'");
        }
    }

    private static String required(
            Map<?, ?> section, String key, String origin, String sectionName) {
        final Object value = section.get(key);
        if (value == null) {
            throw new ProjectException(origin + ": '" + sectionName + "." + key + "' is required");
        }
        return String.valueOf(value);
    }

    /**
     * Reads the operator's own image lines, either inline or from a file beside the project.
     *
     * <p>Both forms exist because both are wanted: a couple of packages read better inline, and a
     * long snippet reads better in a file its own syntax highlighting understands.
     */
    @Nullable
    private static String snippet(Map<?, ?> image, String origin) {
        final Object inline = image.get("snippet");
        final Object file = image.get("snippet_file");
        if (inline != null && file != null) {
            throw new ProjectException(
                    origin + ": 'image.snippet' and 'image.snippet_file' are mutually exclusive");
        }
        if (inline != null) {
            return String.valueOf(inline);
        }
        if (file == null) {
            return null;
        }
        return SnippetFiles.beside(Path.of(origin)).read(String.valueOf(file), origin);
    }

    /**
     * Reads {@code repositories.<name>.image}, the second source in the order an image is chosen
     * by, with the same keys and refusals as a repository's own {@code porelo.yml}.
     */
    private static @Nullable ImageSource repositoryImage(
            Map<?, ?> repository, String origin, String name) {
        final Object image = repository.get("image");
        if (image == null) {
            return null;
        }
        if (!(image instanceof Map<?, ?> map)) {
            throw new ProjectException(
                    origin + ": 'repositories." + name + ".image' must be a mapping");
        }
        return RepositoryImageReader.image(
                map,
                origin + " (repositories." + name + ".image)",
                SnippetFiles.beside(Path.of(origin)));
    }

    private static String text(@Nullable Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String image(String baseImage) {
        Repository.refuseAsOption(baseImage, "base_image");
        return baseImage;
    }

    private static String address(String upstream) {
        Repository.refuseAsOption(upstream, "upstream");
        return upstream;
    }
}
