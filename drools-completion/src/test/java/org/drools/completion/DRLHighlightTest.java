package org.drools.completion;

import java.util.List;

import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DRLHighlightTest {

    private static final String DRL = "package p;\nrule A\nwhen\n  $p : Person( age > 1 )\nthen\n  update($p);\nend\n"
            + "rule B\nwhen\n  $p : Person()\nthen\n  update($p);\nend\n";

    @Test
    void bindingHighlightsStayInsideTheEnclosingRule() {
        List<DocumentHighlight> found = DRLReferencesHelper.highlights(DRL, new Position(3, 4), ClassIndex.empty());
        assertThat(found).hasSize(2);
        assertThat(found).allMatch(h -> h.getRange().getStart().getLine() < 7);
    }

    @Test
    void highlightingAUseFindsTheDeclarationToo() {
        List<DocumentHighlight> found = DRLReferencesHelper.highlights(DRL, new Position(11, 10), ClassIndex.empty());
        assertThat(found).hasSize(2);
        assertThat(found).allMatch(h -> h.getRange().getStart().getLine() >= 9);
    }

    @Test
    void nothingToHighlightOnAKeyword() {
        assertThat(DRLReferencesHelper.highlights(DRL, new Position(2, 1), ClassIndex.empty())).isEmpty();
    }
}
