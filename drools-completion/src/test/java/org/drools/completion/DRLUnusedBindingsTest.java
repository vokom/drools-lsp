package org.drools.completion;

import java.util.List;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticTag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DRLUnusedBindingsTest {

    private static List<Diagnostic> run(String drl) {
        return DRLUnusedBindings.diagnostics(drl);
    }

    @Test
    void unusedPatternAndFieldBindingsAreTaggedUnnecessary() {
        String drl = "package p;\nrule R\nwhen\n  $p : Person( $n : name, age > 1 )\nthen\n  System.out.println(\"x\");\nend\n";
        List<Diagnostic> found = run(drl);
        assertThat(found).hasSize(2);
        assertThat(found).allMatch(d -> d.getTags().contains(DiagnosticTag.Unnecessary));
        assertThat(found.get(0).getRange().getStart().getLine()).isEqualTo(3);
        assertThat(found.get(0).getRange().getEnd().getCharacter() - found.get(0).getRange().getStart().getCharacter()).isEqualTo(2);
    }

    @Test
    void useInConsequenceOrConstraintCountsAsUsed() {
        String drl = "package p;\nrule R\nwhen\n  $p : Person( $n : name )\n  Other( x == $n )\nthen\n  update($p);\nend\n";
        assertThat(run(drl)).isEmpty();
    }

    @Test
    void sameNameInAnotherRuleDoesNotCountAsAUse() {
        String drl = "package p;\nrule A\nwhen\n  $p : Person()\nthen\nend\nrule B\nwhen\n  $p : Person()\nthen\n  update($p);\nend\n";
        List<Diagnostic> found = run(drl);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getRange().getStart().getLine()).isEqualTo(3);
    }

    @Test
    void undeclaredVariableIsNotReportedAsUnused() {
        assertThat(run("package p;\nrule R\nwhen\n  Person()\nthen\n  update($zz);\nend\n")).isEmpty();
    }
}
