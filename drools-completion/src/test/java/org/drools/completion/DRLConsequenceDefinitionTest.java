package org.drools.completion;

import java.nio.file.Path;
import java.util.Set;
import org.eclipse.lsp4j.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DRLConsequenceDefinitionTest {
    private static final String URI = Path.of("/tmp/action-locals.drl").toUri().toString();
    private Position position(String text, int offset) {
        String prefix = text.substring(0, offset);
        return new Position((int) prefix.chars().filter(c -> c == '\n').count(), offset - text.lastIndexOf('\n', offset - 1) - 1);
    }
    private java.util.List<Location> find(String text, int offset) {
        return DRLDefinitionHelper.findDefinitions(URI, text, position(text, offset), ClassIndex.build(Set.of()), Set.of());
    }
    private void resolves(String text, String name, int declaration, int reference) {
        assertThat(find(text, reference)).containsExactly(new Location(URI,
                new Range(position(text, declaration), position(text, declaration + name.length()))));
    }
    @Test void enhancedForVariableResolvesInActionBody() {
        String text = "rule R when then\nfor (String passportNumber : numbers) {\n println(passportNumber); new Visa(passportNumber); }\nend";
        resolves(text, "passportNumber", text.indexOf("passportNumber :"), text.indexOf("passportNumber);"));
        resolves(text, "passportNumber", text.indexOf("passportNumber :"), text.lastIndexOf("passportNumber);"));
        resolves(text, "passportNumber", text.indexOf("passportNumber :"), text.indexOf("passportNumber :") + 2);
    }
    @Test void blockLocalsMultipleDeclaratorsAndVarResolveWithCrLfAndUtf16() {
        String text = "rule R when then\r\nString note = \"😀\"; int first = 1, second = first; var total = second; println(total);\r\nend";
        resolves(text, "first", text.indexOf("first ="), text.indexOf("first;"));
        resolves(text, "second", text.indexOf("second ="), text.indexOf("second;"));
        resolves(text, "total", text.indexOf("total ="), text.indexOf("total);"));
    }
    @Test void classicForVariablesResolveOnlyInsideLoop() {
        String text = "rule R when then for (int i = 0; i < 2; i++) println(i); println(i); end";
        resolves(text, "i", text.indexOf("i ="), text.indexOf("i <"));
        resolves(text, "i", text.indexOf("i ="), text.indexOf("i++"));
        resolves(text, "i", text.indexOf("i ="), text.indexOf("i);"));
        assertThat(find(text, text.lastIndexOf("i);"))).isEmpty();
    }
    @Test void siblingBlocksAndLoopsDoNotLeakLocals() {
        String text = "rule R when then {int value = 1; println(value);} {int value = 2; println(value);} println(value); end";
        resolves(text, "value", text.indexOf("value ="), text.indexOf("value);"));
        resolves(text, "value", text.lastIndexOf("value ="), text.indexOf("value);}", text.indexOf("value);}") + 1));
        assertThat(find(text, text.lastIndexOf("value);"))).isEmpty();
        text = "rule R when then for (String id : ids) println(id); println(id); end";
        assertThat(find(text, text.lastIndexOf("id);"))).isEmpty();
    }
    @Test void catchLambdaAndResourceVariablesResolveWithinTheirScopes() {
        String text = "rule R when then try (var input = open()) { println(input); } catch (Exception error) { println(error); } "
                + "names.forEach(name -> println(name)); println(error); end";
        resolves(text, "input", text.indexOf("input ="), text.indexOf("input);"));
        resolves(text, "error", text.indexOf("error)"), text.indexOf("error);"));
        resolves(text, "name", text.indexOf("name ->"), text.indexOf("name)"));
        assertThat(find(text, text.lastIndexOf("error);"))).isEmpty();
    }
    @Test void forwardReferencesOtherRulesStringsCommentsAndMembersDoNotResolveAsLocals() {
        String text = "rule One when then int value = 1; end\nrule Two when then println(value); end";
        assertThat(find(text, text.indexOf("value);"))).isEmpty();
        text = "rule R when then println(value); int value = 1; obj.value = 2; /* value */ String s = \"value\"; end";
        for (int offset : new int[]{text.indexOf("value);"), text.indexOf("value = 2"), text.indexOf("value */"), text.indexOf("value\"")}) {
            assertThat(find(text, offset)).isEmpty();
        }
    }
    @Test void actionLocalWithDollarTakesPrecedenceOverLhsBinding() {
        String text = "rule R when $p: Person() then {int $other = 1; println($other);} println($p); end";
        resolves(text, "$other", text.indexOf("$other ="), text.indexOf("$other);"));
        resolves(text, "$p", text.indexOf("$p:"), text.indexOf("$p);"));
    }
    @Test void namedConsequencesHaveSeparateLocalScopes() {
        String text = "rule R when Person() do[extra] then int value = 1; println(value); then[extra] println(value); end";
        resolves(text, "value", text.indexOf("value ="), text.indexOf("value);"));
        assertThat(find(text, text.lastIndexOf("value);"))).isEmpty();
    }
    @Test void enhancedForSourceUsesOuterLocalAndLoopVariableDoesNotEscape() {
        String text = "rule R when then String[] item = items; for (String item : item) println(item); println(item); end";
        resolves(text, "item", text.indexOf("item ="), text.indexOf("item)"));
        resolves(text, "item", text.indexOf("item :"), text.indexOf("item);"));
        resolves(text, "item", text.indexOf("item ="), text.lastIndexOf("item);"));
    }
    @Test void switchLocalsAndLambdaParametersDoNotLeak() {
        String text = "rule R when then switch (kind) {case 1: int value = 2; println(value); break;} println(value); end";
        resolves(text, "value", text.indexOf("value ="), text.indexOf("value);"));
        assertThat(find(text, text.lastIndexOf("value);"))).isEmpty();
        for (String parameter : java.util.List.of("name", "(String name)", "(var name)")) {
            text = "rule R when then names.forEach(" + parameter + " -> println(name)); println(name); end";
            resolves(text, "name", text.indexOf("name", text.indexOf("forEach")), text.indexOf("name)" , text.indexOf("->")));
            assertThat(find(text, text.lastIndexOf("name);"))).isEmpty();
        }
    }
}
