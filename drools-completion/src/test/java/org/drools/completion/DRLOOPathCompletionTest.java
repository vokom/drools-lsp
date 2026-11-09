package org.drools.completion;

import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.eclipse.lsp4j.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/** Consequence completion in a rule unit: OOPath bindings and unit data-source fields. */
class DRLOOPathCompletionTest {
    @TempDir Path root;
    Path classes;

    private static final String RULE = "package demo\nunit Unit;\n"
            + "rule R when\n $p: /passports\nthen\n%s\nend\n"
            + "rule Other when\n $q: /passports\nthen\nend\n";

    @BeforeEach void compile() throws Exception {
        classes = Files.createDirectories(root.resolve("target/classes"));
        Path src = Files.createDirectories(root.resolve("src/main/java/demo"));
        Files.writeString(src.resolve("Passport.java"), "package demo; public class Passport {"
                + " public int getAge() { return 0; } public String getName() { return \"\"; } }");
        Files.writeString(src.resolve("Unit.java"), "package demo; public class Unit {"
                + " public java.util.List<Passport> passports; private final java.util.List<Passport> leaders = new java.util.ArrayList<>();"
                + " static int counter; }");
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(),
                src.resolve("Passport.java").toString(), src.resolve("Unit.java").toString())).isZero();
    }

    private List<String> labels(String consequenceLine, Set<Path> outputs) {
        String text = RULE.formatted(consequenceLine);
        int line = 5;
        Position caret = new Position(line, text.split("\n", -1)[line].indexOf('|'));
        text = text.replace("|", "");
        try (var members = ClassMemberIndex.of(Set.of(classes))) {
            return DRLCompletionHelper.getCompletionItems(text, caret, null, ClassIndex.build(Set.of(classes)), members,
                    root.resolve("src/main/resources/demo/test.drl"), Map.of(), outputs)
                    .stream().map(CompletionItem::getLabel).toList();
        }
    }

    @Test void membersOfOoPathBindingAfterDot() {
        assertThat(labels(" $p.|", Set.of(classes))).contains("getAge", "getName").doesNotContain("end", "System");
    }

    @Test void membersOfOoPathBindingWithTypedPrefix() {
        assertThat(labels(" $p.get|", Set.of(classes))).contains("getAge", "getName");
    }

    @Test void membersOfUnitFieldAfterDot() {
        assertThat(labels(" leaders.|", Set.of(classes))).contains("add", "size").doesNotContain("end");
    }

    @Test void ooPathBindingOfTheCaretRuleIsOfferedAsVariable() {
        assertThat(labels(" |", Set.of(classes))).contains("$p").doesNotContain("$q");
        assertThat(labels(" $|", Set.of(classes))).contains("$p");
    }

    @Test void unitInstanceFieldsAreOfferedAsNames() {
        assertThat(labels(" |", Set.of(classes))).contains("passports", "leaders").doesNotContain("counter");
    }

    @Test void globalWithTheSameNameHidesTheUnitField() {
        String text = RULE.formatted(" |").replace("unit Unit;\n", "unit Unit;\nglobal java.util.List leaders;\n");
        int line = 6;
        Position caret = new Position(line, text.split("\n", -1)[line].indexOf('|'));
        try (var members = ClassMemberIndex.of(Set.of(classes))) {
            var labels = DRLCompletionHelper.getCompletionItems(text.replace("|", ""), caret, null,
                    ClassIndex.build(Set.of(classes)), members, root.resolve("test.drl"), Map.of(), Set.of(classes))
                    .stream().filter(i -> i.getKind() == CompletionItemKind.Field).map(CompletionItem::getLabel).toList();
            assertThat(labels).contains("passports").doesNotContain("leaders");
        }
    }

    @Test void withoutBuildOutputsNothingIsGuessed() {
        assertThat(labels(" $p.|", Set.of())).doesNotContain("getAge");
    }
}
