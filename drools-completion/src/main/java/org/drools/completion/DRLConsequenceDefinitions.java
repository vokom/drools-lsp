package org.drools.completion;

import java.util.ArrayList;
import java.util.List;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drl.parser.antlr4.DRL10Parser;
import org.drools.drl.parser.antlr4.JavaLexer;
import org.drools.drl.parser.antlr4.JavaParser;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

/** Lexical Java locals in a single consequence, mapped back to original DRL UTF-16 ranges. */
final class DRLConsequenceDefinitions {
    private DRLConsequenceDefinitions() {}

    static List<Location> find(String uri, ParsedDrl parsed, Position position, String word) {
        int caret = offset(parsed.text, position);
        if (caret < 0) return List.of();
        int codePoint = parsed.text.codePointCount(0, caret);
        var body = bodyAt(parsed.compilationUnit, codePoint);
        if (body == null) return List.of();
        int start = parsed.text.offsetByCodePoints(0, body.getStart().getStartIndex());
        int end = parsed.text.offsetByCodePoints(0, body.getStop().getStopIndex() + 1);
        String java = "{" + parsed.text.substring(start, end) + "}";
        int localCaret = java.codePointCount(0, caret - start + 1);
        var lexer = new JavaLexer(CharStreams.fromString(java));
        lexer.removeErrorListeners();
        var tokens = new CommonTokenStream(lexer);
        tokens.fill();
        Token reference = null, previous = null;
        for (Token token : tokens.getTokens()) {
            if (token.getChannel() != Token.DEFAULT_CHANNEL) continue;
            if (token.getStartIndex() <= localCaret && localCaret <= token.getStopIndex() + 1 && token.getText().equals(word)) {
                reference = token; break;
            }
            if (token.getStopIndex() < localCaret) previous = token;
        }
        if (reference == null || (previous != null && (previous.getText().equals(".") || previous.getText().equals("::")))) return List.of();
        var parser = new JavaParser(tokens);
        parser.removeErrorListeners();
        var block = parser.block();
        List<Binding> bindings = new ArrayList<>();
        collect(block, bindings);
        Binding selected = null;
        for (Binding binding : bindings) {
            int index = reference.getStartIndex();
            if (!binding.name.getText().equals(word) || binding.name.getStartIndex() > index) continue;
            boolean self = binding.name.getStartIndex() == index;
            if (!self && (index < binding.visibleFrom || !contains(binding.scope, index))) continue;
            if (selected == null || depth(binding.scope) > depth(selected.scope)
                    || (depth(binding.scope) == depth(selected.scope) && binding.name.getStartIndex() > selected.name.getStartIndex())) selected = binding;
        }
        if (selected == null) return List.of();
        int declaration = start + java.offsetByCodePoints(0, selected.name.getStartIndex()) - 1;
        return List.of(new Location(uri, new Range(position(parsed.text, declaration), position(parsed.text, declaration + word.length()))));
    }

    private record Binding(Token name, ParserRuleContext scope, int visibleFrom) {}

    private static DRL10Parser.ConsequenceBodyContext bodyAt(ParseTree node, int index) {
        if (node instanceof ParserRuleContext context && !contains(context, index)) return null;
        if (node instanceof DRL10Parser.ConsequenceBodyContext body) return body;
        for (int i = 0; i < node.getChildCount(); i++) {
            var body = bodyAt(node.getChild(i), index);
            if (body != null) return body;
        }
        return null;
    }

    private static boolean contains(ParserRuleContext context, int index) {
        return context != null && context.getStart() != null && context.getStop() != null
                && context.getStart().getStartIndex() <= index && index <= context.getStop().getStopIndex();
    }

    private static <T> T ancestor(ParseTree node, Class<T> type) {
        for (ParseTree parent = node.getParent(); parent != null; parent = parent.getParent()) {
            if (type.isInstance(parent)) return type.cast(parent);
        }
        return null;
    }

    private static int depth(ParseTree node) {
        int depth = 0;
        for (; node != null; node = node.getParent()) depth++;
        return depth;
    }

    private static void add(List<Binding> bindings, Token name, ParserRuleContext scope, int visibleFrom) {
        if (name != null && name.getStartIndex() >= 0 && scope != null) bindings.add(new Binding(name, scope, visibleFrom));
    }

    private static void collect(ParseTree node, List<Binding> bindings) {
        if (node instanceof JavaParser.LocalVariableDeclarationContext local) {
            ParserRuleContext scope = null;
            for (ParseTree parent = local.getParent(); parent != null; parent = parent.getParent()) {
                if (parent instanceof JavaParser.BlockContext block) { scope = block; break; }
                if (parent instanceof JavaParser.StatementContext statement && statement.SWITCH() != null) { scope = statement; break; }
            }
            if (local.getParent() instanceof JavaParser.ForInitContext) scope = ancestor(local, JavaParser.StatementContext.class);
            if (local.identifier() != null) add(bindings, local.identifier().getStart(), scope, local.identifier().getStart().getStartIndex());
            if (local.variableDeclarators() != null) for (var variable : local.variableDeclarators().variableDeclarator()) {
                var name = variable.variableDeclaratorId().identifier().getStart();
                add(bindings, name, scope, name.getStartIndex());
            }
        } else if (node instanceof JavaParser.EnhancedForControlContext loop) {
            var scope = ancestor(loop, JavaParser.StatementContext.class);
            if (scope != null && !scope.statement().isEmpty()) add(bindings, loop.variableDeclaratorId().identifier().getStart(),
                    scope.statement(0), scope.statement(0).getStart().getStartIndex());
        } else if (node instanceof JavaParser.CatchClauseContext clause) {
            add(bindings, clause.identifier().getStart(), clause.block(), clause.block().getStart().getStartIndex());
        } else if (node instanceof JavaParser.ResourceContext resource && resource.ASSIGN() != null) {
            var statement = ancestor(resource, JavaParser.StatementContext.class);
            if (statement != null && statement.block() != null) {
                Token name = resource.variableDeclaratorId() != null ? resource.variableDeclaratorId().identifier().getStart() : resource.identifier().getStart();
                // Resource variables are visible in later resources and the try block, not catches/finally.
                add(bindings, name, statement.resourceSpecification(), name.getStartIndex());
                add(bindings, name, statement.block(), statement.block().getStart().getStartIndex());
            }
        } else if (node instanceof JavaParser.LambdaExpressionContext lambda) {
            var parameters = lambda.lambdaParameters();
            var scope = lambda.lambdaBody();
            int from = scope.getStart().getStartIndex();
            for (var name : parameters.identifier()) add(bindings, name.getStart(), scope, from);
            if (parameters.formalParameterList() != null) {
                var list = parameters.formalParameterList();
                for (var parameter : list.formalParameter()) add(bindings, parameter.variableDeclaratorId().identifier().getStart(), scope, from);
                if (list.lastFormalParameter() != null) add(bindings, list.lastFormalParameter().variableDeclaratorId().identifier().getStart(), scope, from);
            }
            if (parameters.lambdaLVTIList() != null) for (var parameter : parameters.lambdaLVTIList().lambdaLVTIParameter()) {
                add(bindings, parameter.identifier().getStart(), scope, from);
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) collect(node.getChild(i), bindings);
    }

    private static int offset(String text, Position position) {
        if (position.getLine() < 0 || position.getCharacter() < 0) return -1;
        int start = 0;
        for (int line = 0; line < position.getLine(); line++) {
            int newline = text.indexOf('\n', start);
            if (newline < 0) return -1;
            start = newline + 1;
        }
        int offset = start + position.getCharacter();
        return offset <= text.length() ? offset : -1;
    }

    private static Position position(String text, int offset) {
        int line = 0;
        for (int i = 0; i < offset; i++) if (text.charAt(i) == '\n') line++;
        return new Position(line, offset - text.lastIndexOf('\n', offset - 1) - 1);
    }
}
