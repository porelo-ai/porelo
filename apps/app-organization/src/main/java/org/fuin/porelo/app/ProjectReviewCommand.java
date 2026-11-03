package org.fuin.porelo.app;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.OrganizationWrites;
import org.fuin.porelo.organization.gittree.ProjectFile;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/** Says in the project's own file whether a merge needs another person's approval. */
@Command(
        name = "review",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = "Says whether a merge needs another person: required or optional.")
public class ProjectReviewCommand extends ProjectRepositoryCommand.Changing {

    @Parameters(arity = "2..3", paramLabel = "PROJECT [NAME] WORD", description = "What to say.")
    private List<String> words = new ArrayList<>();

    @Override
    String project() {
        return words.getFirst();
    }

    @Override
    OrganizationWrites.Written make(
            final ProjectFile file,
            final OrganizationWrites.Change change,
            final ProjectRepositoryCommand.Place place)
            throws IOException {
        if (words.size() < 2) {
            throw new Refused(Refused.USAGE, "name a project and a word", null);
        }
        return file.review(
                change,
                place.address(),
                place.keys(),
                words.size() == 3 ? words.get(1) : null,
                ProjectRepositoryCommand.review(words.getLast()));
    }
}
