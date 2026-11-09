package org.drools.completion;

import java.util.List;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DRLConsequenceCompletionTest {

    private static final String DRL = "package p;\ndeclare Person\n  name : String\nend\n"
            + "rule R\nwhen\n  $p : Person( $n : name )\nthen\n  $\nend\n";

    @Test
    void bindingsInScopeAreOfferedInsideTheConsequence() {
        List<CompletionItem> items = DRLCompletionHelper.getCompletionItems(DRL, new Position(8, 3), null);
        assertThat(items).filteredOn(i -> i.getKind() == CompletionItemKind.Variable)
                .extracting(CompletionItem::getLabel).contains("$p", "$n");
    }

    @Test
    void bindingsAreNotOfferedOutsideTheConsequence() {
        String drl = DRL.replace("  $\nend", "end");
        List<CompletionItem> items = DRLCompletionHelper.getCompletionItems(drl, new Position(6, 4), null);
        assertThat(items).noneMatch(i -> i.getKind() == CompletionItemKind.Variable);
    }

    @Test
    void fieldsAreOfferedWhileAPrefixIsTyped() {
        String drl = "package p;\ndeclare Person\n  name : String\nend\n"
                + "rule R\nwhen\n  Person( na )\nthen\nend\n";
        List<CompletionItem> items = DRLCompletionHelper.getCompletionItems(drl, new Position(6, 11), null);
        assertThat(items).filteredOn(i -> i.getKind() == CompletionItemKind.Field)
                .extracting(CompletionItem::getLabel).contains("name");
    }

    @Test
    void bindingsAreOfferedWhileAPrefixIsTyped() {
        String drl = DRL.replace("  $\nend", "  $p\nend");
        List<CompletionItem> items = DRLCompletionHelper.getCompletionItems(drl, new Position(8, 4), null);
        assertThat(items).extracting(CompletionItem::getLabel).contains("$p");
    }

    @Test
    void functionsAndClassNamesAreOfferedWhileAPrefixIsTypedInTheConsequence() {
        String drl = "package p;\nimport java.util.List;\nfunction String hello(String n) { return n; }\n"
                + "declare Person\n  name : String\nend\nrule R\nwhen\n  Person()\nthen\n  hel\nend\n";
        List<CompletionItem> items = DRLCompletionHelper.getCompletionItems(drl, new Position(10, 5), null);
        assertThat(items).filteredOn(i -> i.getKind() == CompletionItemKind.Function)
                .extracting(CompletionItem::getLabel).contains("hello");
        String sys = drl.replace("  hel\n", "  Sys\n");
        assertThat(DRLCompletionHelper.getCompletionItems(sys, new Position(10, 5), null))
                .extracting(CompletionItem::getLabel).contains("System");
    }

    @Test
    void fieldsAreOfferedInALaterRuleAfterALabelledConstraint() {
        String drl = "package p;\ndeclare Person\n  age : int\n  name : String\nend\n"
                + "rule A\nwhen\n  Person( $n : age > 3 )\nthen\nend\n"
                + "rule B\nwhen\n  Person( na )\nthen\nend\n";
        List<CompletionItem> items = DRLCompletionHelper.getCompletionItems(drl, new Position(12, 12), null);
        assertThat(items).extracting(CompletionItem::getLabel).contains("name");
    }
}
