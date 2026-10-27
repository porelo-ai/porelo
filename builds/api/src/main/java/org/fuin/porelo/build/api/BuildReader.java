package org.fuin.porelo.build.api;

import org.jspecify.annotations.Nullable;

/**
 * What a build reader implements: one forge, asked about one branch or one commit at a time.
 *
 * <p>A reader in its own repository implements this, and its main method hands it to {@link
 * BuildMain#run}; Porelo finds the executable, starts it, and asks it over {@link
 * BuildProtocol#INTERFACE}.
 */
public interface BuildReader {

    /**
     * Returns the forge this reader reads, which is what a project file names under {@code
     * builds.forge} and the name its executable is installed under.
     *
     * @return Lower-case letters, digits and hyphens.
     */
    String forge();

    /**
     * Returns the commit a branch points at on the forge.
     *
     * @param target Where and with what.
     * @param branch The branch's name, such as {@code fix-login}; not a ref.
     * @return The full sha; "" when the forge has no such branch.
     * @throws BuildRefused When the forge would not answer.
     */
    String head(Target target, String branch);

    /**
     * Returns what the build of one commit did, across everything the forge ran for it.
     *
     * @param target Where and with what.
     * @param commit The full sha.
     * @param logs Which jobs' logs to download and hand over: none, each failed one, or every one
     *     once the verdict is final; jobs are listed even when no logs are requested.
     * @return The build; {@link Build#UNKNOWN} when the forge holds none for the commit.
     * @throws BuildRefused When the forge would not answer.
     */
    Build build(Target target, String commit, Build.Logs logs);

    /**
     * Opens a pull request from a branch into the repository's default branch, or answers the one
     * already open from it, so asking twice opens one.
     *
     * <p>A reader that does not open pull requests leaves this as it is: Porelo then hears that it
     * cannot, and the task runs as before.
     *
     * @param target Where and with what; the token must be allowed to open one.
     * @param branch The branch's name, such as {@code porelo/fix-login}.
     * @param title Its title, or {@code null} for the subject of the branch's newest commit.
     * @return The pull request.
     * @throws BuildRefused When the forge would not answer or not open it.
     * @throws UnsupportedOperationException When this reader opens none.
     */
    default PullRequest openPullRequest(
            final Target target, final String branch, final @Nullable String title) {
        throw new UnsupportedOperationException(forge() + " opens no pull request");
    }
}
