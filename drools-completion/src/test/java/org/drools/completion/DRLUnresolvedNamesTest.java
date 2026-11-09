package org.drools.completion;

import java.util.List;
import java.util.Map;

import org.eclipse.lsp4j.Diagnostic;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DRLUnresolvedNamesTest {

    private static List<Diagnostic> run(String rhs) {
        String drl = "package p;\nfunction String hello(String n) { return n; }\ndeclare Person\n  name : String\nend\n"
                + "rule R\nwhen\n  $p : Person( $n : name )\nthen\n" + rhs + "\nend\n";
        return DRLUnresolvedNames.diagnostics("file:///a.drl", drl, null, Map.of());
    }

    @Test
    void unknownBindingAndUnknownCallAreFlagged() {
        List<Diagnostic> result = run("  System.out.println($p2 + hell2o(\"z\"));");
        assertThat(result).extracting(Diagnostic::getMessage)
                .containsExactlyInAnyOrder("Cannot resolve symbol '$p2'", "Cannot resolve method 'hell2o'");
    }

    @Test
    void knownNamesAreNotFlagged() {
        assertThat(run("  String $local = hello($p.getName());\n  System.out.println($local + $n);\n"
                + "  modify($p) { setName(\"x\") }\n  update($p);\n  insert(new Object());")).isEmpty();
    }
}
