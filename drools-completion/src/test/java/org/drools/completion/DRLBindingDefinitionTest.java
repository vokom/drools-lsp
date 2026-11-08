package org.drools.completion;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.eclipse.lsp4j.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DRLBindingDefinitionTest {
    private static final String URI = Path.of("/tmp/binding-test.drl").toUri().toString();

    private Position position(String text, int offset) {
        String prefix = text.substring(0, offset);
        return new Position((int) prefix.chars().filter(c -> c == '\n').count(), offset - text.lastIndexOf('\n', offset - 1) - 1);
    }
    private List<Location> find(String text, int offset) {
        return DRLDefinitionHelper.findDefinitions(URI, text, position(text, offset), ClassIndex.build(Set.of()), Set.of());
    }
    private void assertBinding(String text, String name, int declaration, int reference) {
        assertThat(find(text, reference)).containsExactly(new Location(URI,
                new Range(position(text, declaration), position(text, declaration + name.length()))));
    }
    @Test void ooPathPatternAndPropertyBindingsResolveWithinTheirOwnRules() {
        String text = "package demo\r\nunit PassportRuleUnit;\r\n"
                + "rule One when $passport: /passports[$unusedPages: unusedVisaPages > 0] then println($passport, $unusedPages); end\r\n"
                + "rule Two when $passport: /passports[isExpired()] then println($passport); end";
        assertBinding(text, "$passport", text.indexOf("$passport:"), text.indexOf("$passport,"));
        assertBinding(text, "$unusedPages", text.indexOf("$unusedPages:"), text.indexOf("$unusedPages);"));
        assertBinding(text, "$passport", text.lastIndexOf("$passport:"), text.lastIndexOf("$passport);"));
    }
    @Test void traditionalPatternsLhsReferencesAndDeclarationItselfResolve() {
        String text = "rule R when $p: Person($age: age) Person(age > $age) then println($p, $age); end";
        assertBinding(text, "$age", text.indexOf("$age:"), text.indexOf("$age)"));
        assertBinding(text, "$p", text.indexOf("$p:"), text.indexOf("$p,"));
        assertBinding(text, "$p", text.indexOf("$p:"), text.indexOf("$p:") + 1);
    }
    @Test void bindingsWithoutDollarAndUnificationResolve() {
        String text = "rule R when p: Person(age := age) then println(p, age); end";
        assertBinding(text, "p", text.indexOf("p:"), text.indexOf("p,"));
        assertBinding(text, "age", text.indexOf("age :="), text.indexOf("age);"));
    }
    @Test void otherRulesCommentsStringsAndUnknownBindingsDoNotSupplyDefinitions() {
        String text = "rule One when $p: Person() then end\n"
                + "rule Two when /* $p: Person() */ Person(name == \"$p: Person()\") then println($p); end";
        assertThat(find(text, text.indexOf("$p);"))).isEmpty();
        assertThat(find(text, text.indexOf("$p: Person() */") + 1)).isEmpty();
        assertThat(find(text, text.indexOf("$p: Person()\"") + 1)).isEmpty();
        assertThat(find(text + "\n// $p", text.length() + 5)).isEmpty();
    }
    @Test void bindingsInsideNegativePatternsDoNotEscapeTheirScopes() {
        String text = "rule R when not($p: Person()) then println($p); end";
        assertThat(find(text, text.indexOf("$p);"))).isEmpty();
    }
    @Test void ternaryAndMapColonsAreNotBindingDeclarations() {
        String text = "rule R when Person(value == (true ? $p : 0), data == [$p: 1]) then println($p); end";
        assertThat(find(text, text.indexOf("$p);"))).isEmpty();
    }
}
