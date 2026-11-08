package org.drools.completion;

import java.util.ArrayList;
import java.util.List;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drl.parser.antlr4.DRL10Parser;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

/** Definition locations for LHS bindings, independently of the bound value's Java type. */
final class DRLBindingDefinitions {
    private DRLBindingDefinitions() {}

    static List<Location> find(String uri, ParsedDrl parsed, Position position, String word) {
        var token = parsed.tokenAt(position);
        if (token == null) return List.of();
        for (var statement : parsed.compilationUnit.drlStatementdef()) {
            var rule = statement.ruledef();
            if (rule == null || rule.lhs() == null || !contains(rule, token.getTokenIndex())) continue;
            List<Location> locations = new ArrayList<>();
            collect(rule.lhs(), uri, word, token.getTokenIndex(), position, locations);
            return List.copyOf(locations);
        }
        return List.of();
    }

    private static boolean contains(ParserRuleContext context, int token) {
        return context.getStart() != null && context.getStop() != null
                && context.getStart().getTokenIndex() <= token && token <= context.getStop().getTokenIndex();
    }

    private static void collect(ParseTree node, String uri, String word, int caretToken,
                                Position caret, List<Location> locations) {
        // Bindings in these conditional elements are local to the element.
        if ((node instanceof DRL10Parser.LhsNotContext || node instanceof DRL10Parser.LhsExistsContext
                || node instanceof DRL10Parser.LhsForallContext)
                && !contains((ParserRuleContext) node, caretToken)) return;
        DRL10Parser.DrlIdentifierContext name = null;
        if (node instanceof DRL10Parser.LabelContext label) name = label.drlIdentifier();
        else if (node instanceof DRL10Parser.UnifContext unification) name = unification.drlIdentifier();
        else if (node instanceof DRL10Parser.UnaryExpressionNotPlusMinusContext expression) name = expression.var;
        if (name != null && name.getText().equals(word)) {
            var start = name.getStart();
            Position declaration = new Position(start.getLine() - 1, start.getCharPositionInLine());
            if (declaration.getLine() < caret.getLine()
                    || (declaration.getLine() == caret.getLine() && declaration.getCharacter() <= caret.getCharacter())) {
                Location location = new Location(uri, new Range(declaration,
                        new Position(declaration.getLine(), declaration.getCharacter() + word.length())));
                if (!locations.contains(location)) locations.add(location);
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) collect(node.getChild(i), uri, word, caretToken, caret, locations);
    }
}
