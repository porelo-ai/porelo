package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import org.fuin.porelo.runtime.Containerfile;
import org.fuin.porelo.wire.Directories;

/**
 * What a task's agent is told about Porelo in its task: its handed-in files, the builds of its
 * pushes, and its mailbox where it has one.
 *
 * <p>Nothing told an agent that {@code /porelo/files} existed, so it pushed and never looked at its
 * red build unless a person remembered to say so. <strong>The same for every task of one Porelo
 * version</strong>, as the mailbox's guide is and for the same reason: an agent takes it into its
 * system prompt, which a provider caches by its beginning. The files' part comes first, since every
 * task has {@code /porelo/files}; the mailbox's follows it.
 */
public final class TaskGuideText {

    private TaskGuideText() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns the guide, as its agent is told.
     *
     * @param mailbox Whether the task has a mailbox, whose guide then follows.
     * @return Markdown.
     */
    public static String text(final boolean mailbox) {
        return text(mailbox, java.util.Map.of());
    }

    /**
     * Returns the guide of a task that reads its whole project: what every task is told, then that
     * its workspace is every repository, read only, and that nothing there goes anywhere.
     *
     * <p>Last, after the values, so what every task shares stays the beginning a provider caches;
     * and needed, since what every task shares tells it to push.
     *
     * @param mailbox Whether the task has a mailbox, whose guide then follows.
     * @param values Each value by its name, with the variable it is in or {@code null}.
     * @param reading The repositories it reads, by name, or {@code null} for a task on one.
     * @return Markdown.
     */
    public static String text(
            final boolean mailbox,
            final java.util.Map<String, java.util.Map.Entry<String, String>> values,
            final java.util.@org.jspecify.annotations.Nullable List<String> reading) {
        final String shared = text(mailbox, values);
        if (reading == null) {
            return shared;
        }
        final StringBuilder part =
                new StringBuilder(
                        """

                        # This task reads its whole project

                        `/workspace` holds every repository of this project, one directory each, as its main
                        stood when the task started. It is read only: nothing in it can be changed. A repository
                        of the project that is not there could not be read on this machine.

                        **Nothing in `/workspace` is pushed**, and this task has no gate: what is said above about
                        pushing does not apply here. The task is removed when it ends, with whatever it holds.

                        """);
        for (final String name : reading) {
            part.append("- `/workspace/").append(name).append("/`\n");
        }
        return shared + part;
    }

    /**
     * Returns the guide of a task, with the file it hands back last, after everything every task
     * shares, so a provider's cache of the shared beginning still holds.
     *
     * @param mailbox Whether the task has a mailbox, whose guide then follows.
     * @param values Each value by its name, with the variable it is in or {@code null}.
     * @param reading The repositories it reads, by name, or {@code null} for a task on one.
     * @param result The name of the file it hands back, or {@code null} for none.
     * @return Markdown.
     */
    public static String text(
            final boolean mailbox,
            final java.util.Map<String, java.util.Map.Entry<String, String>> values,
            final java.util.@org.jspecify.annotations.Nullable List<String> reading,
            final @org.jspecify.annotations.Nullable String result) {
        final String before = text(mailbox, values, reading);
        if (result == null) {
            return before;
        }
        return before
                + """

                # The file this task hands back

                When this task ends, Porelo takes **`@FILE@`** from `@BOX@/` and hands it to whoever
                started the task: when you declare your work done, and when your run ends. Write it there,
                whole, as UTF-8 text of at most 1 MiB, readable by others (mode 644). A link, a directory,
                anything larger or anything that is not text is not taken. Only this one file is.
                """
                        .replace("@FILE@", result)
                        .replace("@BOX@", TaskResult.MOUNT);
    }

    /**
     * What a task is told of its MCP endpoint: its own name, which a call naming a task must give,
     * the methods the endpoint carries, and how many ideas it may propose.
     *
     * <p>Never its token: the agent sends its own from {@code $PORELO_TOKEN_PORELO_MCP}, and the
     * guide only names the variable.
     *
     * @param task The task's own name, its container's.
     * @param methods The methods the endpoint carries, by name, in the order the file writes them.
     * @param ideas The most ideas the task may propose, the project's {@code limits.ideas}.
     */
    public record Endpoint(String task, java.util.List<String> methods, int ideas) {

        /**
         * Constructor.
         *
         * @param task The task.
         * @param methods The methods.
         * @param ideas The ideas bound.
         */
        public Endpoint {
            methods = java.util.List.copyOf(methods);
        }

        /**
         * Whether the task is told how to propose an idea: the endpoint carries {@code ProposeIdea}
         * and the project allows at least one.
         *
         * @return Whether it may propose one.
         */
        public boolean proposes() {
            return ideas > 0 && methods.contains("ProposeIdea");
        }
    }

    /**
     * Returns the guide of a task, with its MCP endpoint's part last, after everything every task
     * shares and the parts before it, so a provider's cache of the shared beginning still holds.
     *
     * @param mailbox Whether the task has a mailbox, whose guide then follows.
     * @param values Each value by its name, with the variable it is in or {@code null}.
     * @param reading The repositories it reads, by name, or {@code null} for a task on one.
     * @param result The name of the file it hands back, or {@code null} for none.
     * @param endpoint Its MCP endpoint, or {@code null} for a task that has none.
     * @return Markdown.
     */
    public static String text(
            final boolean mailbox,
            final java.util.Map<String, java.util.Map.Entry<String, String>> values,
            final java.util.@org.jspecify.annotations.Nullable List<String> reading,
            final @org.jspecify.annotations.Nullable String result,
            final @org.jspecify.annotations.Nullable Endpoint endpoint) {
        final String before = text(mailbox, values, reading, result);
        if (endpoint == null) {
            return before;
        }
        final StringBuilder part =
                new StringBuilder(
                        """

                        # This task's MCP endpoint

                        This task can call Porelo itself through an MCP endpoint on this machine. Two variables
                        name it:

                        - `$PORELO_URL_PORELO_MCP` is where it is. Send every request to this URL exactly as it
                          stands: append no `/mcp` and no other path.
                        - `$PORELO_TOKEN_PORELO_MCP` is this task's own token for it. Send it in the header
                          `Authorization: Bearer $PORELO_TOKEN_PORELO_MCP`, and never write its value anywhere.

                        It speaks MCP over HTTP: each request is one `POST` of one JSON-RPC 2.0 message with
                        `Content-Type: application/json`, answered with one JSON message. `initialize` says which
                        MCP versions it speaks, `tools/list` lists its tools with their parameters, and
                        `tools/call` calls one by its `name` with its `arguments`. It keeps no session: no
                        `Mcp-Session-Id` is needed, and a `tools/call` needs no `initialize` before it. A refusal
                        comes back as a result with `isError: true`, its `structuredContent.error` naming it.

                        Its tools are Porelo's own methods of the same names, these alone:

                        """);
        for (final String method : endpoint.methods()) {
            part.append("- `").append(method).append("`\n");
        }
        part.append(
                """

                To list them with their parameters:

                ```
                curl -s -X POST "$PORELO_URL_PORELO_MCP" \\
                  -H "Authorization: Bearer $PORELO_TOKEN_PORELO_MCP" \\
                  -H 'Content-Type: application/json' \\
                  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
                ```
                """);
        if (endpoint.proposes()) {
            part.append(
                    """

                    ## Proposing an idea

                    When you find a new issue while working, one that is not this task's to fix, propose it
                    as an idea with `ProposeIdea` instead of writing it anywhere else. Porelo keeps it with
                    this task and shows it to a person. A person takes it from there, keeping or declining
                    it; you do nothing more with it. This task may propose at most **@MOST@** @IDEAS@ in
                    its life, ones a person already took counted too, so propose only what matters.

                    - `task`: this task's own name, `@TASK@`, and no other.
                    - `title`: one line of at most @TITLE@ characters.
                    - `whatMustBeTrue`: what must be true once it is done, in one or two sentences of at
                      most @TEXT@ characters, with no control characters but line breaks.

                    The answer names its id, such as `idea-1`. It is refused with `NotItsOwn` when `task`
                    names another task, with `NotAllowed` past this task's bound, and with
                    `InvalidParameter` when the title or the text is out of these bounds; nothing is trimmed
                    or cut, so shorten it and call again.

                    ```
                    curl -s -X POST "$PORELO_URL_PORELO_MCP" \\
                      -H "Authorization: Bearer $PORELO_TOKEN_PORELO_MCP" \\
                      -H 'Content-Type: application/json' \\
                      -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"ProposeIdea",
                    "arguments":{"task":"@TASK@","title":"The listing names the branch of a stopped task",
                    "whatMustBeTrue":"The row of a stopped task in the listing names the branch it worked on."}}}'
                    ```

                    The body is in single quotes, so a `'` in your title or text ends it: leave it out,
                    or write the body to a file and send it with `-d @file`.
                    """
                            .replace("@MOST@", Integer.toString(endpoint.ideas()))
                            .replace("@IDEAS@", endpoint.ideas() == 1 ? "idea" : "ideas")
                            .replace("@TASK@", endpoint.task())
                            .replace("@TITLE@", Integer.toString(TaskIdeas.MOST_TITLE))
                            .replace("@TEXT@", Integer.toString(TaskIdeas.MOST_WHAT_MUST_BE_TRUE)));
        }
        return before + part;
    }

    /**
     * Returns the guide with the values this task was started with, in a part after everything
     * every task shares, so a provider's cache of the shared beginning still holds.
     *
     * @param mailbox Whether the task has a mailbox, whose guide then follows.
     * @param values Each value by its name: the value and the variable it is in, or {@code null}
     *     for one its declaration keeps out of the task.
     * @return Markdown.
     */
    public static String text(
            final boolean mailbox,
            final java.util.Map<String, java.util.Map.Entry<String, String>> values) {
        final String files =
                """
                # Porelo in this task

                This task runs in Porelo. What follows is what Porelo gives you here besides your workspace:
                files handed to you, the builds of what you push, and a mailbox where the task has one.

                **When your work is done, commit it and push it with `git push porelo`, and name no branch.** The push
                goes to this task's own place, `$PORELO_TASK_REF`, at the gate on this machine: where a person reviews
                it, or, in an online project, from where it goes on at once to this task's own branch at the forge -
                never onto a branch you name. A person takes back only what you pushed; what is only in your
                workspace stays in this task.

                **When you are told the repository you work from moved on, `git fetch porelo` brings it.**
                Rebase or merge your work onto it before you push again.

                # Files handed to you

                - **`@FILES@` holds the files handed to this task**, by a person or by Porelo, and nothing
                  else. A file appears there whole, never half written.
                - You can read them but not change or remove them. Copy one into your workspace to change it.
                - A file handed in again under the same name replaces the one before.
                - **Look there when you start, and whenever you come to rest.** When a file arrives while you
                  wait at your prompt, a line may appear there naming it; not every agent is given one.

                ## The builds of what you push

                Where the project follows its builds, Porelo watches what the forge builds for each commit you
                push to the task's branch, and hands in what it did. Where no build comes while this task runs,
                because the push waits at the gate for a person or the project follows no builds, Porelo says so
                at once, in the same file:

                - `build-<commit>.txt`, `<commit>` the first 12 characters of the commit: lines of
                  `key: value` - `commit`, `verdict`, one `run` line per workflow run, one `job` line per
                  job, `since`, and `detail` when there is more to say.
                - `verdict` is `queued` or `running` while the build goes on, then `success`, `failure` or
                  `cancelled`. It is `none` when no build of this push is reported while this task runs, and
                  `unknown` when Porelo cannot read the build yet. `detail` says why, such as "held at the gate
                  for a person".
                - A `run` line names a workflow of the commit and its state when the file was written:
                  `queued` or `in_progress` while it goes on, then what became of it. Not every forge names
                  its runs.
                - A `job` line names the job and what became of it, and the file holding the end of its log
                  when one was handed in: `build-<commit>-<n>.log`. A failed job's log always is, when the
                  forge has one.
                - The file is replaced each time the verdict changes.

                **After you push, read `build-<commit>.txt` until its verdict is final, before you call the work
                done.** The final verdicts are `success`, `failure`, `cancelled` and `none`. `unknown` is not final:
                read the file again later. On `failure`, read the log each failed `job` line names, fix the cause,
                and push again. With `none` no build comes while this task runs, and your work is done once it is
                pushed: do not wait for one.
                """
                        .replace("@FILES@", Containerfile.HAND_IN_FILES);
        final String shared = mailbox ? files + "\n" + MailboxGuide.text() : files;
        if (values.isEmpty()) {
            return shared;
        }
        final StringBuilder part =
                new StringBuilder(
                        """

                        # The values this task was started with

                        A person chose these when the task was started. They are fixed for the task's life, and
                        not secrets.

                        """);
        values.forEach(
                (name, given) -> {
                    part.append("- `")
                            .append(name)
                            .append("`: `")
                            .append(given.getKey())
                            .append('`');
                    if (given.getValue() != null) {
                        part.append(", in `$").append(given.getValue()).append('`');
                    }
                    part.append('\n');
                });
        return shared + part;
    }

    /**
     * Writes the guide into its directory, made when missing, and only when it changed.
     *
     * @param guide Where it goes.
     * @param mailbox Whether the task has a mailbox.
     * @throws IOException Writing failed.
     */
    public static void write(final TaskGuide guide, final boolean mailbox) throws IOException {
        write(guide, mailbox, java.util.Map.of());
    }

    /**
     * Writes the guide with the task's values into its directory, made when missing, and only when
     * it changed.
     *
     * @param guide Where it goes.
     * @param mailbox Whether the task has a mailbox.
     * @param values Each value by its name, with the variable it is in or {@code null}.
     * @throws IOException Writing failed.
     */
    public static void write(
            final TaskGuide guide,
            final boolean mailbox,
            final java.util.Map<String, java.util.Map.Entry<String, String>> values)
            throws IOException {
        write(guide, mailbox, values, null);
    }

    /**
     * Writes the guide of a task, the repositories it reads among it, into its directory, made when
     * missing, and only when it changed.
     *
     * @param guide Where it goes.
     * @param mailbox Whether the task has a mailbox.
     * @param values Each value by its name, with the variable it is in or {@code null}.
     * @param reading The repositories it reads, or {@code null} for a task on one.
     * @throws IOException Writing failed.
     */
    public static void write(
            final TaskGuide guide,
            final boolean mailbox,
            final java.util.Map<String, java.util.Map.Entry<String, String>> values,
            final java.util.@org.jspecify.annotations.Nullable List<String> reading)
            throws IOException {
        write(guide, mailbox, values, reading, null);
    }

    /**
     * Writes the guide of a task, the file it hands back among it, into its directory, made when
     * missing, and only when it changed.
     *
     * @param guide Where it goes.
     * @param mailbox Whether the task has a mailbox.
     * @param values Each value by its name, with the variable it is in or {@code null}.
     * @param reading The repositories it reads, or {@code null} for a task on one.
     * @param result The name of the file it hands back, or {@code null} for none.
     * @throws IOException Writing failed.
     */
    public static void write(
            final TaskGuide guide,
            final boolean mailbox,
            final java.util.Map<String, java.util.Map.Entry<String, String>> values,
            final java.util.@org.jspecify.annotations.Nullable List<String> reading,
            final @org.jspecify.annotations.Nullable String result)
            throws IOException {
        write(guide, mailbox, values, reading, result, null);
    }

    /**
     * Writes the guide of a task, its MCP endpoint's part among it, into its directory, made when
     * missing, and only when it changed.
     *
     * @param guide Where it goes.
     * @param mailbox Whether the task has a mailbox.
     * @param values Each value by its name, with the variable it is in or {@code null}.
     * @param reading The repositories it reads, or {@code null} for a task on one.
     * @param result The name of the file it hands back, or {@code null} for none.
     * @param endpoint Its MCP endpoint, or {@code null} for a task that has none.
     * @throws IOException Writing failed.
     */
    public static void write(
            final TaskGuide guide,
            final boolean mailbox,
            final java.util.Map<String, java.util.Map.Entry<String, String>> values,
            final java.util.@org.jspecify.annotations.Nullable List<String> reading,
            final @org.jspecify.annotations.Nullable String result,
            final @org.jspecify.annotations.Nullable Endpoint endpoint)
            throws IOException {
        final Path directory = guide.directory();
        if (Files.isSymbolicLink(directory)) {
            throw new IOException(directory + " is a link; the guide is not written through it");
        }
        Directories.create(directory);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"));
        final String text = text(mailbox, values, reading, result, endpoint);
        final Path file = guide.file();
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                && Files.readString(file, StandardCharsets.UTF_8).equals(text)) {
            return;
        }
        // Beside the mounted directory and renamed into it, so the agent never reads half of it.
        final Path staged = Files.createTempFile(directory.getParent(), ".guide", ".tmp");
        Files.writeString(staged, text, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(staged, PosixFilePermissions.fromString("rw-r--r--"));
        Files.move(
                staged, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
