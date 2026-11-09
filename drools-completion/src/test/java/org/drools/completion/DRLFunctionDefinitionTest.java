package org.drools.completion;

import java.util.List;
import java.util.Map;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DRLFunctionDefinitionTest {

    private static final String DRL = "package p;\n"
            + "function String hello(String name) {\n  return \"Hello \" + name;\n}\n"
            + "rule R\nwhen\n  Object()\nthen\n  System.out.println(hello(\"y\"));\nend\n";

    @Test
    void callInConsequenceResolvesToTheFunctionDeclaration() {
        List<Location> result = DRLDefinitionHelper.findDefinitions("file:///a.drl", DRL,
                new Position(8, 22), null, java.util.Set.of(), null, Map.of());
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getRange().getStart()).isEqualTo(new Position(1, 16));
        assertThat(result.get(0).getRange().getEnd()).isEqualTo(new Position(1, 21));
    }
}
