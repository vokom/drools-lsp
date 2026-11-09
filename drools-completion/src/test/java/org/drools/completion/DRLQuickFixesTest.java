package org.drools.completion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.TextEdit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DRLQuickFixesTest {

    private static final ClassIndex INDEX = ClassIndex.of(Map.of(
            "Person", List.of("p.model.Person"),
            "Address", List.of("p.a.Address", "p.b.Address")));

    /** Applies {@code edits} to {@code text}, last edit first so earlier offsets stay valid. */
    static String apply(String text, List<TextEdit> edits) {
        List<TextEdit> sorted = new ArrayList<>(edits);
        sorted.sort(Comparator.comparingInt((TextEdit e) -> DRLQuickFixes.offset(text, e.getRange().getStart())).reversed());
        String out = text;
        for (TextEdit e : sorted) {
            int start = DRLQuickFixes.offset(text, e.getRange().getStart());
            int end = DRLQuickFixes.offset(text, e.getRange().getEnd());
            out = out.substring(0, start) + e.getNewText() + out.substring(end);
        }
        return out;
    }

    private static List<DRLQuickFixes.Fix> fixes(String text, List<Diagnostic> diagnostics) {
        return DRLQuickFixes.forDiagnostics(text, diagnostics, INDEX);
    }

    private static String fixOnce(String text, List<Diagnostic> diagnostics) {
        List<DRLQuickFixes.Fix> found = fixes(text, diagnostics);
        assertThat(found).hasSize(1);
        return apply(text, found.get(0).edits());
    }

    private static List<Diagnostic> withCode(List<Diagnostic> all, String code) {
        return all.stream().filter(d -> d.getCode() != null && code.equals(d.getCode().getLeft())).toList();
    }

    private static List<Diagnostic> typeDiagnostics(String text) {
        return DRLLintHelper.lintUnknownTypes(text, ParsedDrl.of(text).compilationUnit, null, Map.of(), INDEX,
                ClassMemberIndex.of(Set.of()), true);
    }

    // ── imports ──

    @Test
    void missingImportIsAddedAfterTheLastImport() {
        String text = "package p;\nimport java.util.List;\n\nrule R\nwhen\n  Person()\nthen\nend\n";
        List<Diagnostic> missing = withCode(typeDiagnostics(text), DRLLintHelper.MISSING_IMPORT);
        assertThat(missing).hasSize(1);
        List<DRLQuickFixes.Fix> found = fixes(text, missing);
        assertThat(found).singleElement().satisfies(f -> {
            assertThat(f.title()).isEqualTo("Import 'p.model.Person'");
            assertThat(f.preferred()).isTrue();
        });
        String fixed = apply(text, found.get(0).edits());
        assertThat(fixed).isEqualTo("package p;\nimport java.util.List;\nimport p.model.Person;\n\nrule R\nwhen\n  Person()\nthen\nend\n");
        assertThat(typeDiagnostics(fixed)).isEmpty();
    }

    @Test
    void missingImportGoesAfterThePackageWhenThereAreNoImports() {
        String text = "package p;\nrule R\nwhen\nthen\n  Person x = new Person();\nend\n";
        String fixed = fixOnce(text, withCode(typeDiagnostics(text), DRLLintHelper.MISSING_IMPORT));
        assertThat(fixed).startsWith("package p;\nimport p.model.Person;\nrule R\n");
    }

    @Test
    void ambiguousUnknownTypeOffersEachCandidate() {
        String text = "package p;\nrule R\nwhen\n  Address()\nthen\nend\n";
        List<Diagnostic> unknown = withCode(typeDiagnostics(text), "unknown-type");
        assertThat(unknown).hasSize(1);
        assertThat(fixes(text, unknown)).extracting(DRLQuickFixes.Fix::title)
                .containsExactly("Import 'p.a.Address'", "Import 'p.b.Address'");
    }

    @Test
    void importedSamePackageWildcardAndJavaLangTypesAreNotMissing() {
        String text = "package p.model;\nimport p.a.*;\nrule R\nwhen\n  Person()\n  Address()\n  String()\nthen\nend\n";
        assertThat(withCode(typeDiagnostics(text), DRLLintHelper.MISSING_IMPORT)).isEmpty();
    }

    @Test
    void unusedAndDuplicateImportsRemoveTheirWholeLine() {
        String text = "package p;\nimport p.model.Person;\nimport java.util.Map;\nimport p.model.Person;\nrule R\nwhen\n  Person()\nthen\nend\n";
        List<Diagnostic> all = new ArrayList<>(DRLStructureLint.diagnostics(text));
        all.addAll(DRLStructureLint.unusedImports(text, null, Map.of()));
        String noUnused = fixOnce(text, withCode(all, DRLStructureLint.UNUSED_IMPORT));
        assertThat(noUnused).isEqualTo("package p;\nimport p.model.Person;\nimport p.model.Person;\nrule R\nwhen\n  Person()\nthen\nend\n");
        String noDuplicate = fixOnce(text, withCode(all, DRLStructureLint.DUPLICATE_IMPORT));
        assertThat(noDuplicate).isEqualTo("package p;\nimport p.model.Person;\nimport java.util.Map;\nrule R\nwhen\n  Person()\nthen\nend\n");
    }

    @Test
    void lastLineImportWithoutNewlineIsRemoved() {
        String text = "package p;\nimport java.util.Map;";
        String fixed = fixOnce(text, DRLStructureLint.unusedImports(text, null, Map.of()));
        assertThat(fixed).isEqualTo("package p;\n");
    }

    // ── rules ──

    @Test
    void duplicateRuleIsRenamedToTheFirstFreeNumber() {
        String text = "rule \"A\"\nwhen\nthen\nend\nrule \"A 2\"\nwhen\nthen\nend\nrule A\nwhen\nthen\nend\n";
        List<Diagnostic> dup = withCode(DRLStructureLint.diagnostics(text), DRLStructureLint.DUPLICATE_RULE_NAME);
        assertThat(dup).hasSize(1);
        List<DRLQuickFixes.Fix> found = fixes(text, dup);
        assertThat(found).singleElement().satisfies(f -> assertThat(f.title()).isEqualTo("Rename rule to 'A 3'"));
        String fixed = apply(text, found.get(0).edits());
        assertThat(fixed).endsWith("rule \"A 3\"\nwhen\nthen\nend\n");
        assertThat(withCode(DRLStructureLint.diagnostics(fixed), DRLStructureLint.DUPLICATE_RULE_NAME)).isEmpty();
    }

    @Test
    void staleRenameDiagnosticOffersNothing() {
        String before = "rule \"A\"\nwhen\nthen\nend\nrule \"A\"\nwhen\nthen\nend\n";
        List<Diagnostic> dup = withCode(DRLStructureLint.diagnostics(before), DRLStructureLint.DUPLICATE_RULE_NAME);
        assertThat(fixes(before.replace("rule \"A\"\nwhen\nthen\nend\nrule \"A\"", "rule \"A\"\nwhen\nthen\nend\nrule \"B\""), dup)).isEmpty();
    }

    @Test
    void missingSemicolonIsInsertedAfterTheStatement() {
        String text = "rule R\nwhen\n  $p : Person()\nthen\n  update($p)\nend\n";
        List<Diagnostic> missing = withCode(DRLLintHelper.lint(text), DRLLintHelper.MISSING_SEMICOLON);
        assertThat(missing).hasSize(1);
        assertThat(fixOnce(text, missing)).contains("  update($p);\nend");
    }

    // ── bindings ──

    @Test
    void unusedPatternBindingsAreRemoved() {
        String text = "rule R\nwhen\n  $p : Person()\n  $q:p.model.Person()\n  $o : /people[age > 1]\nthen\nend\n";
        List<Diagnostic> unused = DRLUnusedBindings.diagnostics(text);
        assertThat(unused).hasSize(3);
        List<DRLQuickFixes.Fix> found = fixes(text, unused);
        assertThat(found).extracting(DRLQuickFixes.Fix::title)
                .containsExactly("Remove unused binding '$p'", "Remove unused binding '$q'", "Remove unused binding '$o'");
        String fixed = apply(text, found.stream().flatMap(f -> f.edits().stream()).toList());
        assertThat(fixed).isEqualTo("rule R\nwhen\n  Person()\n  p.model.Person()\n  /people[age > 1]\nthen\nend\n");
    }

    @Test
    void unusedFieldBindingGetsNoFix() {
        String text = "rule R\nwhen\n  Person( $n : name, $a : getAge() )\nthen\nend\n";
        List<Diagnostic> unused = DRLUnusedBindings.diagnostics(text);
        assertThat(unused).hasSize(2);
        assertThat(fixes(text, unused)).isEmpty();
    }

    @Test
    void diagnosticsWithoutAKnownCodeOrPastTheTextAreIgnored() {
        Diagnostic other = new Diagnostic(DRLLintHelper.rangeOf("x", 0, 1), "x");
        other.setCode("something-else");
        Diagnostic outside = new Diagnostic(new org.eclipse.lsp4j.Range(new org.eclipse.lsp4j.Position(9, 0),
                new org.eclipse.lsp4j.Position(9, 4)), "x");
        outside.setCode(DRLStructureLint.UNUSED_IMPORT);
        assertThat(fixes("rule R\nwhen\nthen\nend\n", List.of(other, outside, new Diagnostic()))).isEmpty();
    }
}
