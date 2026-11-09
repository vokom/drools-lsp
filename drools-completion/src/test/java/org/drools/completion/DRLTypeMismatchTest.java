package org.drools.completion;

import java.util.List;

import org.eclipse.lsp4j.Diagnostic;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DRLTypeMismatchTest {

    private static List<Diagnostic> run(String constraint) {
        String drl = "package p;\ndeclare Passport\n  pages : int\n  name : String\n  valid : boolean\nend\n"
                + "rule R\nwhen\n  $p : Passport( " + constraint + " )\nthen\nend\n";
        return DRLTypeMismatch.diagnostics(drl, ClassIndex.empty(), ClassMemberIndex.empty());
    }

    @Test
    void numericFieldComparedWithStringLiteralIsFlagged() {
        List<Diagnostic> result = run("$n : pages > \"3\"");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMessage()).contains("'pages' is int").contains("String literal");
        assertThat(result.get(0).getRange().getStart().getLine()).isEqualTo(8);
    }

    @Test
    void stringFieldComparedWithNumberIsFlagged() {
        assertThat(run("name == 5")).hasSize(1);
    }

    @Test
    void booleanFieldComparedWithStringIsFlagged() {
        assertThat(run("valid == \"yes\"")).hasSize(1);
    }

    @Test
    void matchingKindsAreNotFlagged() {
        assertThat(run("pages > 3, name == \"x\", valid == true, pages != -1")).isEmpty();
    }

    @Test
    void unknownFieldsAndMemberPathsAreIgnored() {
        assertThat(run("other > \"3\"")).isEmpty();
        assertThat(run("name.length > 3")).isEmpty();
    }
}
