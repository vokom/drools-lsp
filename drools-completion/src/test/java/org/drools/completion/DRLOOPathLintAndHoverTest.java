package org.drools.completion;

import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.eclipse.lsp4j.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/** OOPath constraints inside a rule unit: type-mismatch lint and method hover. */
class DRLOOPathLintAndHoverTest {
    @TempDir Path root;
    Path classes;

    @BeforeEach void compile() throws Exception {
        classes = Files.createDirectories(root.resolve("target/classes"));
        Path src = Files.createDirectories(root.resolve("src/main/java/demo"));
        String passport = "package demo; public class Passport { public int age; public java.time.LocalDate expiresOn; }";
        String unit = "package demo; public class Unit { public java.util.List<Passport> passports; private final java.util.List<Passport> leaders = new java.util.ArrayList<>(); }";
        Files.writeString(src.resolve("Passport.java"), passport);
        Files.writeString(src.resolve("Unit.java"), unit);
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(),
                src.resolve("Passport.java").toString(), src.resolve("Unit.java").toString())).isZero();
    }

    @Test void mismatchInsideOoPathConstraintIsFlaggedOnlyWhenOutputsKnown() {
        String text = "package demo\nunit Unit;\nrule R when\n forall ( /passports[age >= \"10\"] )\nthen\nend";
        try (var members = ClassMemberIndex.of(Set.of(classes))) {
            var index = ClassIndex.build(Set.of(classes));
            var found = DRLTypeMismatch.diagnostics(text, index, members, Set.of(classes));
            assertThat(found).hasSize(1);
            assertThat(found.get(0).getMessage()).contains("'age' is int").contains("String literal");
            assertThat(DRLTypeMismatch.diagnostics(text, index, members)).isEmpty();
            assertThat(DRLTypeMismatch.diagnostics(text.replace("\"10\"", "10"), index, members, Set.of(classes))).isEmpty();
        }
    }

    @Test void hoverOnMethodCalledOnOoPathFieldShowsOverloads() {
        String text = "package demo\nunit Unit;\nrule R when\n $p: /passports[ expiresOn.plusMonths(6) != null ]\nthen\nend";
        int line = 3;
        int col = text.split("\n")[line].indexOf("plusMonths") + 2;
        try (var members = ClassMemberIndex.of(Set.of(classes))) {
            Hover hover = DRLHoverHelper.hover(text, new Position(line, col), ClassIndex.build(Set.of(classes)), members,
                    root.resolve("test.drl"), Map.of(), Set.of(classes));
            assertThat(hover).isNotNull();
            assertThat(hover.getContents().getRight().getValue()).contains("plusMonths").contains("java.time.LocalDate");
        }
    }

    @Test void hoverOnMethodCalledOnOoPathBindingChain() {
        String text = "package demo\nunit Unit;\nrule R when\n $p: /passports\n /passports[ $p.expiresOn.plusDays(1) != null ]\nthen\nend";
        int line = 4;
        int col = text.split("\n")[line].indexOf("plusDays") + 2;
        try (var members = ClassMemberIndex.of(Set.of(classes))) {
            Hover hover = DRLHoverHelper.hover(text, new Position(line, col), ClassIndex.build(Set.of(classes)), members,
                    root.resolve("test.drl"), Map.of(), Set.of(classes));
            assertThat(hover).isNotNull();
            assertThat(hover.getContents().getRight().getValue()).contains("plusDays");
        }
    }

    @Test void fieldBindingInsideOoPathBracketsIsNotTheElementType() throws Exception {
        Path src = root.resolve("src/main/java/demo");
        Files.writeString(src.resolve("Valid.java"), "package demo; public class Valid { public Passport passport; }");
        Files.writeString(src.resolve("Unit2.java"), "package demo; public class Unit2 { public java.util.List<Valid> valids; }");
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(),
                "-cp", classes.toString(), src.resolve("Valid.java").toString(), src.resolve("Unit2.java").toString())).isZero();
        String text = "package demo\nunit Unit2;\nrule R when\n /valids[ $v: this.passport ]\nthen\n Object o = $v.toString();\nend";
        try (var members = ClassMemberIndex.of(Set.of(classes))) {
            var found = DRLJavaMethods.diagnostics(text.replace("toString", "getPassportNumber"), ClassIndex.build(Set.of(classes)),
                    members, root.resolve("test.drl"), Map.of(), Set.of(classes));
            assertThat(found).noneMatch(d -> d.getMessage().contains("on demo.Valid"));
        }
    }

    @Test void misspelledMethodOnPrivateUnitFieldInConsequenceIsFlagged() {
        String text = "package demo\nunit Unit;\nrule R when\n $p: /passports\nthen\n leaders.ad21d($p);\n leaders.add($p);\nend";
        try (var members = ClassMemberIndex.of(Set.of(classes))) {
            var found = DRLJavaMethods.diagnostics(text, ClassIndex.build(Set.of(classes)), members,
                    root.resolve("test.drl"), Map.of(), Set.of(classes));
            assertThat(found).extracting(Diagnostic::getMessage).anyMatch(m -> m.contains("Unknown method 'ad21d'"));
            assertThat(found).extracting(Diagnostic::getMessage).noneMatch(m -> m.contains("'add'"));
        }
    }

    @Test void addLogicalOnRuleUnitDataStoreIsNotFlagged() throws Exception {
        Path src = Files.createDirectories(root.resolve("stub"));
        Files.writeString(src.resolve("DataStore.java"),
                "package org.drools.ruleunits.api; public interface DataStore<T> { Object add(T t); }");
        Files.writeString(src.resolve("Unit2.java"),
                "package demo; public class Unit2 { private org.drools.ruleunits.api.DataStore<Passport> visas; "
                + "public java.util.List<Passport> passports; }");
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-cp", classes.toString(), "-d", classes.toString(),
                src.resolve("DataStore.java").toString(), src.resolve("Unit2.java").toString())).isZero();
        String text = "package demo\nunit Unit2;\nrule R when\n $p: /passports\nthen\n visas.addLogical($p);\n visas.nope($p);\nend";
        try (var members = ClassMemberIndex.of(Set.of(classes))) {
            var found = DRLJavaMethods.diagnostics(text, ClassIndex.build(Set.of(classes)), members,
                    root.resolve("test.drl"), Map.of(), Set.of(classes));
            assertThat(found).extracting(Diagnostic::getMessage).noneMatch(m -> m.contains("addLogical"));
            assertThat(found).extracting(Diagnostic::getMessage).anyMatch(m -> m.contains("'nope'"));
        }
    }
}
