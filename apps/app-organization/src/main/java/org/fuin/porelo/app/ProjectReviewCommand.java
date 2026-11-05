package org.fuin.porelo.app;

import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * Shows the entry that says in the project's own file whether a merge needs another person's
 * approval. Porelo changes no project file that exists: a project admin writes it.
 */
@Command(
        name = "review",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description =
                "Shows the entry that says whether a merge needs another person (required or"
                        + " optional), for a project admin to write.")
public class ProjectReviewCommand extends ProjectRepositoryCommand.ByHand {

    @Parameters(arity = "2..3", paramLabel = "PROJECT [NAME] WORD", description = "What to say.")
    private List<String> words = new ArrayList<>();

    @Override
    String project() {
        return words.getFirst();
    }

    @Override
    String what() {
        if (words.size() < 2) {
            throw new Refused(Refused.USAGE, "name a project and a word", null);
        }
        final String review = ProjectRepositoryCommand.review(words.getLast());
        if (words.size() == 3) {
            return "set this key and keep the others:\n"
                    + ProjectRepositoryCommand.entry(words.get(1), null, null, review);
        }
        return "set this key and keep the others:\n"
                + ProjectRepositoryCommand.projectReview(review);
    }
}
