package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests that a tool call's command line is stored without a secret, whatever kind, and still says
 * what ran. Every secret here is an obviously fake string.
 */
class CommandMaskTest {

    /** A value the vault holds for the task, shaped like nothing the rules know. */
    private static final String VAULT_VALUE = "fake-vault-value-0123";

    private static final CommandMask MASK = new CommandMask(List.of(VAULT_VALUE));

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            quoteCharacter = '`',
            textBlock =
                    """
                    option with equals   | deploy --password=FAKEpass123 --verbose         | FAKEpass123           | deploy --password=*** --verbose
                    option with a space  | gh api --token FAKEtok456 /user                  | FAKEtok456            | gh api --token *** /user
                    api key option       | tool --api-key 'FAKE key 789' run                | FAKE key 789          | tool --api-key *** run
                    mysql short password | mysql -u root -pFAKEdbpw1 shop                   | FAKEdbpw1             | mysql -u root -p*** shop
                    sshpass password     | sshpass -p FAKEsshpw2 ssh host                   | FAKEsshpw2            | sshpass -p *** ssh host
                    curl user password   | curl -u alice:FAKEcurlpw3 https://example.org    | FAKEcurlpw3           | curl -u alice:*** https://example.org
                    authorization header | curl -H 'Authorization: Bearer FAKEbearer4' x    | FAKEbearer4           | curl -H 'Authorization: Bearer ***' x
                    curl user quoted     | curl -u "alice:FAKEcurlpw4" https://example.org  | FAKEcurlpw4           | curl -u "alice:***" https://example.org
                    other auth scheme    | curl -H 'Authorization: ApiKey FAKEapikey5' x    | FAKEapikey5           | curl -H 'Authorization: ***' x
                    environment token    | GH_TOKEN=FAKEenv5 gh pr list                     | FAKEenv5              | GH_TOKEN=*** gh pr list
                    environment password | export DB_PASSWORD="FAKE env 6" && run          | FAKE env 6            | export DB_PASSWORD=*** && run
                    environment secret   | APP_SECRET=FAKEsec7 APP_KEY=FAKEkey8 make        | FAKEsec7              | APP_SECRET=*** APP_KEY=*** make
                    url user information | git clone https://bob:FAKEurlpw9@example.org/r.git | FAKEurlpw9          | git clone https://***@example.org/r.git
                    forge token shape    | echo ghs_FAKEFAKEFAKEFAKEFAKEFAKEFAKEFAKEFAKE00 | ghs_FAKEFAKEFAKEFAKEFAKEFAKEFAKEFAKEFAKE00 | echo ***
                    provider key shape   | echo sk-ant-FAKEFAKEFAKEFAKEFAKEFAKE0          | sk-ant-FAKEFAKEFAKEFAKEFAKEFAKE0 | echo ***
                    provider key after = | run MODEL=sk-ant-FAKEFAKEFAKEFAKEFAKEFAKE0 now | sk-ant-FAKEFAKEFAKEFAKEFAKEFAKE0 | run MODEL=*** now
                    key glued to base64 | verify --sig QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo+sk-FAKEFAKEFAKEFAKEFAKEFAKE/YWJj= | sk-FAKEFAKEFAKEFAKEFAKEFAKE | verify --sig QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo+***/YWJj=
                    key inside a word | echo QUJDsk-FAKEFAKEFAKEFAKEFAKEFAKE         | sk-FAKEFAKEFAKEFAKEFAKEFAKE | echo QUJD***
                    gitlab token shape   | echo glpat-FAKEFAKEFAKEFAKEFAKE0               | glpat-FAKEFAKEFAKEFAKEFAKE0 | echo ***
                    cloud key shape      | aws s3 ls # AKIAFAKEFAKEFAKEFAKE                | AKIAFAKEFAKEFAKEFAKE  | aws s3 ls # ***
                    phantom token shape  | echo porelo_pt_FAKEFAKEFAKEFAKEFAKE             | porelo_pt_FAKEFAKEFAKEFAKEFAKE | echo ***
                    private key block    | printf '-----BEGIN OPENSSH PRIVATE KEY-----FAKEbody-----END OPENSSH PRIVATE KEY-----' > k | FAKEbody | printf '***' > k
                    vault value          | ./publish fake-vault-value-0123 now             | fake-vault-value-0123 | ./publish *** now
                    vault value in a url | curl https://example.org/?q=fake-vault-value-0123 | fake-vault-value-0123 | curl https://example.org/?q=***
                    """)
    void aSecretOfEveryKindIsMaskedAndTheRestOfTheLineKept(
            final String kind, final String line, final String secret, final String masked) {

        assertThat(MASK.mask(line)).as("%s is never stored", kind).doesNotContain(secret);
        assertThat(MASK.mask(line)).as("the line still says what ran").isEqualTo(masked);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            quoteCharacter = '`',
            textBlock =
                    """
                    a build         | ./mvnw -B -q -s settings.xml test
                    a commit        | git log --oneline -3 4f2a9c1e
                    a plain url     | curl https://example.org/path?page=2
                    a key of a sort | sort -k 2 file.txt
                    """)
    void aLineWithoutASecretIsKeptAsItIs(final String kind, final String line) {
        assertThat(MASK.mask(line)).as(kind).isEqualTo(line);
    }

    @org.junit.jupiter.api.Test
    void aTaskWhoseVaultCannotBeReadHasEveryCommandLineMaskedWhole() {
        assertThat(CommandMask.whole().mask("./publish fake-vault-value-0123 now"))
                .as("its values cannot be looked for")
                .isEqualTo(CommandMask.MASK);
    }
}
