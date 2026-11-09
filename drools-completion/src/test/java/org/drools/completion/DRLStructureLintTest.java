package org.drools.completion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DiagnosticTag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class DRLStructureLintTest {

    private static final ClassIndex INDEX = ClassIndex.of(Map.of(
            "Person", List.of("p.model.Person"),
            "Order", List.of("p.model.Order")));

    @AfterEach
    void clearProperties() {
        System.clearProperty(DRLStructureLint.PROP_DUPLICATE_RULE_NAMES);
        System.clearProperty("drools.dateformat");
    }

    private static List<Diagnostic> codes(String drl, String code) {
        return DRLStructureLint.diagnostics(drl).stream().filter(d -> code.equals(d.getCode().getLeft())).toList();
    }

    private static List<Diagnostic> unused(String drl) {
        return DRLStructureLint.unusedImports(drl, null, Map.of());
    }

    private static List<Diagnostic> unresolved(String drl, boolean classpathResolved) {
        return DRLStructureLint.unresolvedImports(drl, null, Map.of(), INDEX, classpathResolved);
    }

    // ── rule names ──

    @Test
    void secondRuleWithTheSameNameIsAWarningCarryingTheName() {
        String drl = "package p;\nrule \"A\"\nwhen\nthen\nend\nrule B\nwhen\nthen\nend\nrule \"A\"\nwhen\nthen\nend\n";
        List<Diagnostic> found = codes(drl, DRLStructureLint.DUPLICATE_RULE_NAME);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getSeverity()).isEqualTo(DiagnosticSeverity.Warning);
        assertThat(found.get(0).getRange().getStart().getLine()).isEqualTo(9);
        assertThat(found.get(0).getMessage()).contains("line 2");
        assertThat(found.get(0).getData()).isEqualTo("A");
    }

    @Test
    void quotedAndBareSpellingsOfOneNameAreDuplicates() {
        assertThat(codes("rule R\nwhen\nthen\nend\nrule \"R\"\nwhen\nthen\nend\n", DRLStructureLint.DUPLICATE_RULE_NAME)).hasSize(1);
    }

    @Test
    void ruleNamesInCommentsAndDistinctNamesAreIgnored() {
        assertThat(codes("rule A\nwhen\nthen\nend\n// rule A\nrule B\nwhen\nthen\nend\n", DRLStructureLint.DUPLICATE_RULE_NAME)).isEmpty();
    }

    @Test
    void passCanBeTurnedOff() {
        System.setProperty(DRLStructureLint.PROP_DUPLICATE_RULE_NAMES, "off");
        assertThat(codes("rule A\nwhen\nthen\nend\nrule A\nwhen\nthen\nend\n", DRLStructureLint.DUPLICATE_RULE_NAME)).isEmpty();
    }

    // ── imports ──

    @Test
    void repeatedImportIsADuplicateTaggedUnnecessary() {
        String drl = "package p;\nimport p.model.Person;\nimport p.model.Person;\nrule R\nwhen\n  Person()\nthen\nend\n";
        List<Diagnostic> found = codes(drl, DRLStructureLint.DUPLICATE_IMPORT);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getRange().getStart().getLine()).isEqualTo(2);
        assertThat(found.get(0).getTags()).contains(DiagnosticTag.Unnecessary);
    }

    @Test
    void importUsedOnlyInACommentOrStringIsUnused() {
        String drl = "package p;\nimport p.model.Person;\nimport p.model.Order;\nrule R\nwhen\n  Order()\nthen\n  // Person\n  System.out.println(\"Person\");\nend\n";
        List<Diagnostic> found = unused(drl);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getMessage()).contains("p.model.Person");
        assertThat(found.get(0).getSeverity()).isEqualTo(DiagnosticSeverity.Hint);
        assertThat(found.get(0).getTags()).contains(DiagnosticTag.Unnecessary);
        assertThat(found.get(0).getRange().getStart().getCharacter()).isZero();
        assertThat(found.get(0).getRange().getEnd().getCharacter()).isEqualTo("import p.model.Person;".length());
    }

    @Test
    void usesInGlobalsConsequencesAndDeclaresCount() {
        String drl = "package p;\nimport p.model.Person;\nimport p.model.Order;\nimport java.util.List;\n"
                + "global List names;\ndeclare Holder\n  owner : Person\nend\nrule R\nwhen\nthen\n  Order o = new Order();\nend\n";
        assertThat(unused(drl)).isEmpty();
    }

    @Test
    void wildcardStaticAndFunctionImportsAreNotJudged() {
        String drl = "package p;\nimport p.model.*;\nimport static p.Util.helper;\nimport function p.Util.f;\nrule R\nwhen\nthen\nend\n";
        assertThat(unused(drl)).isEmpty();
    }

    @Test
    void importUsedBySamePackageSiblingIsNotUnused(@TempDir Path dir) throws Exception {
        Path main = dir.resolve("a.drl");
        Path sibling = dir.resolve("b.drl");
        Path other = dir.resolve("c.drl");
        String drl = "package p;\nimport p.model.Person;\nrule A\nwhen\nthen\nend\n";
        Files.writeString(main, drl);
        Files.writeString(sibling, "package p;\nrule B\nwhen\n  Person()\nthen\nend\n");
        Files.writeString(other, "package q;\nrule C\nwhen\n  Person()\nthen\nend\n");
        WorkspaceSiblingResolver previous = WorkspaceSiblingResolvers.active();
        try {
            WorkspaceSiblingResolvers.setActive(file -> List.of(sibling, other));
            assertThat(DRLStructureLint.unusedImports(drl, main, Map.of())).isEmpty();
            WorkspaceSiblingResolvers.setActive(file -> List.of(other));
            assertThat(DRLStructureLint.unusedImports(drl, main, Map.of())).hasSize(1);
        } finally {
            WorkspaceSiblingResolvers.setActive(previous);
        }
    }

    @Test
    void unknownImportIsReportedOnItsNameOnlyOnceTheClasspathResolved() {
        String drl = "package p;\nimport p.model.Persn;\nrule R\nwhen\nthen\nend\n";
        assertThat(unresolved(drl, false)).isEmpty();
        List<Diagnostic> found = unresolved(drl, true);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getCode().getLeft()).isEqualTo(DRLStructureLint.UNRESOLVED_IMPORT);
        assertThat(found.get(0).getRange().getStart().getCharacter()).isEqualTo("import ".length());
        assertThat(found.get(0).getRange().getEnd().getCharacter()).isEqualTo("import p.model.Persn".length());
    }

    @Test
    void indexedJdkNestedWildcardAndDeclaredImportsResolve() {
        String drl = "package p;\nimport p.model.Person;\nimport java.util.List;\nimport java.util.Map.Entry;\n"
                + "import p.model.Order.Line;\nimport p.model.*;\nimport java.time.*;\nimport p.Local;\n"
                + "declare Local\n  x : int\nend\nrule R\nwhen\nthen\nend\n";
        assertThat(unresolved(drl, true)).isEmpty();
    }

    @Test
    void unknownWildcardPackageIsReported() {
        assertThat(unresolved("package p;\nimport com.nope.*;\nrule R\nwhen\nthen\nend\n", true)).hasSize(1);
    }

    // ── attributes ──

    @Test
    void salienceLiteralsThatCannotBeAnIntAreErrors() {
        for (String value : List.of("\"10\"", "1.5", "2e3", "true", "null", "10d")) {
            String drl = "rule R\n  salience " + value + "\nwhen\nthen\nend\n";
            List<Diagnostic> found = codes(drl, DRLStructureLint.INVALID_SALIENCE);
            assertThat(found).as(value).hasSize(1);
            assertThat(found.get(0).getSeverity()).isEqualTo(DiagnosticSeverity.Error);
            assertThat(found.get(0).getRange().getStart().getCharacter()).isEqualTo("  salience ".length());
        }
    }

    @Test
    void salienceIntegersAndExpressionsAreAccepted() {
        for (String value : List.of("10", "-5", "($p.getAge() * 2)", "$priority", "Priorities.HIGH", "trueValue")) {
            String drl = "rule R salience " + value + "\nwhen\n  $p : Person()\nthen\nend\n";
            assertThat(codes(drl, DRLStructureLint.INVALID_SALIENCE)).as(value).isEmpty();
        }
    }

    @Test
    void salienceInAttributesBlockAndRhsTextAreHandled() {
        assertThat(codes("rule R\n  attributes : salience 1.5, no-loop\nwhen\nthen\nend\n", DRLStructureLint.INVALID_SALIENCE)).hasSize(1);
        assertThat(codes("rule R\nwhen\nthen\n  salience \"x\";\nend\n", DRLStructureLint.INVALID_SALIENCE)).isEmpty();
    }

    @Test
    void datesFollowTheDroolsDefaultFormat() {
        assertThat(codes("rule R\n  date-effective \"1-Jan-2020\"\n  date-expires \"31-Dec-2030\"\nwhen\nthen\nend\n",
                DRLStructureLint.INVALID_DATE)).isEmpty();
        List<Diagnostic> found = codes("rule R\n  date-effective \"2020-01-01\"\nwhen\nthen\nend\n", DRLStructureLint.INVALID_DATE);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getMessage()).contains("d-MMM-yyyy");
    }

    @Test
    void dateFormatPropertyIsHonored() {
        System.setProperty("drools.dateformat", "yyyy-MM-dd");
        assertThat(codes("rule R\n  date-expires \"2020-01-01\"\nwhen\nthen\nend\n", DRLStructureLint.INVALID_DATE)).isEmpty();
    }

    // ── clause order and strings ──

    @Test
    void thenBeforeWhenIsReportedAtTheWhen() {
        List<Diagnostic> found = codes("rule R\nthen\n  System.out.println(1);\nwhen\n  Person()\nend\n", DRLStructureLint.CLAUSE_ORDER);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getRange().getStart().getLine()).isEqualTo(3);
    }

    @Test
    void normalOrderAndRhsOnlyRulesAreFine() {
        assertThat(codes("rule A\nwhen\nthen\nend\nrule B\nthen\nend\nrule C\nwhen\nthen\nend\n", DRLStructureLint.CLAUSE_ORDER)).isEmpty();
    }

    @Test
    void unterminatedStringIsReportedOnItsLine() {
        List<Diagnostic> found = codes("rule R\nwhen\n  Person(name == \"x)\nthen\nend\n", DRLStructureLint.UNTERMINATED_STRING);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getRange().getStart().getLine()).isEqualTo(2);
        assertThat(found.get(0).getRange().getStart().getCharacter()).isEqualTo("  Person(name == ".length());
    }

    @Test
    void escapesCommentsAndTextBlocksAreNotUnterminated() {
        String drl = "rule \"R\"\nwhen\n  Person(name == \"a\\\"b\", tag == 'x')\nthen\n  // don't\n  /* it's\n  */\n"
                + "  String s = \"\"\"\n    multi \"line\n    \"\"\";\nend\n";
        assertThat(codes(drl, DRLStructureLint.UNTERMINATED_STRING)).isEmpty();
    }

    @Test
    void cleanFileHasNoStructureDiagnostics() {
        String drl = "package p;\nimport p.model.Person;\n\nrule \"R\" salience 10\n  date-effective \"1-Jan-2020\"\nwhen\n  $p : Person(name == \"x\")\nthen\n  update($p);\nend\n";
        assertThat(DRLStructureLint.diagnostics(drl)).isEmpty();
        assertThat(unused(drl)).isEmpty();
        assertThat(unresolved(drl, true)).isEmpty();
    }
}
