package org.drools.completion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
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

/** Source-backed rule-unit OOPath navigation without loading or initializing user classes. */
final class DRLOOPathDefinitions {
    private DRLOOPathDefinitions() {}

    /** Null means the caret is outside OOPath; an empty list means unresolved OOPath. */
    static List<Location> find(ParsedDrl parsed, Position position, String word,
                               ClassIndex classes, Set<Path> outputs) {
        Token token = parsed.tokenAt(position);
        if (token == null) return null;
        var path = enclosingPath(parsed.compilationUnit, token.getTokenIndex());
        if (path == null) return null;
        int previous = token.getTokenIndex() - 1;
        while (previous >= 0 && parsed.tokens().get(previous).getChannel() != Token.DEFAULT_CHANNEL) previous--;
        if (previous >= 0 && parsed.tokens().get(previous).getText().equals(".")) {
            int receiver = previous - 1;
            while (receiver >= 0 && parsed.tokens().get(receiver).getChannel() != Token.DEFAULT_CHANNEL) receiver--;
            if (receiver < 0 || !parsed.tokens().get(receiver).getText().equals("this")) return null;
        }
        String owner = DRLDefinitionHelper.resolveUnitFqcn(parsed, classes, outputs);
        if (owner == null) return List.of();
        for (var chunk : path.xpathChunk()) {
            // Inline casts and back references require additional scope/type inference.
            if (chunk.HASH() != null) return List.of();
            for (var identifier : chunk.drlIdentifier()) {
                if (contains(identifier, token.getTokenIndex())) {
                    return JavaSourceLocator.locateMembers(owner, word, false, outputs);
                }
                owner = propertyType(owner, identifier.getText(), outputs, true);
                if (owner == null) return List.of();
            }
            if (chunk.xpathExpressionList() != null
                    && contains(chunk.xpathExpressionList(), token.getTokenIndex())) {
                int next = token.getTokenIndex() + 1;
                while (next < parsed.tokens().size() && parsed.tokens().get(next).getChannel() != Token.DEFAULT_CHANNEL) next++;
                boolean call = next < parsed.tokens().size() && parsed.tokens().get(next).getText().equals("(");
                return JavaSourceLocator.locateMembers(owner, word, call, outputs);
            }
        }
        return List.of();
    }

    /** Resolve member chains on OOPath bindings, pattern properties and rule-unit data. */
    static List<Location> findBoundMember(String uri, String text, ParsedDrl parsed, Position position, String word,
                                          ClassIndex classes, Set<Path> outputs) {
        var range = DRLDefinitionHelper.wordRangeAt(text, position);
        if (range == null) return null;
        int offset = 0;
        for (int line = 0; line < position.getLine(); line++) {
            int newline = text.indexOf('\n', offset);
            if (newline < 0) return null;
            offset = newline + 1;
        }
        offset += range.getStart().getCharacter();
        if (offset > text.length()) return null;
        int codePoint = text.codePointCount(0, offset);
        // Consequences use opaque DRL tokens, so tokenize member chains with the Java lexer.
        var lexer = new JavaLexer(CharStreams.fromString(text));
        lexer.removeErrorListeners();
        var stream = new CommonTokenStream(lexer);
        stream.fill();
        var tokens = stream.getTokens();
        Token token = null;
        for (var candidate : tokens) {
            if (candidate.getStartIndex() <= codePoint && codePoint <= candidate.getStopIndex()) {
                token = candidate;
                break;
            }
        }
        if (token == null || !token.getText().equals(word)) return null;
        int previous = previousCode(tokens, token.getTokenIndex());
        if (previous < 0 || !tokens.get(previous).getText().equals(".")) return null;
        var chain = new java.util.ArrayList<String>();
        Token rootToken = null;
        while (previous >= 0 && tokens.get(previous).getText().equals(".")) {
            int identifier = previousCode(tokens, previous);
            if (identifier < 0) return null;
            String name = tokens.get(identifier).getText();
            if (!name.matches("[$\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*")) return null;
            chain.add(0, name);
            rootToken = tokens.get(identifier);
            previous = previousCode(tokens, identifier);
        }
        if (chain.isEmpty()) return null;
        int rootOffset = text.offsetByCodePoints(0, rootToken.getStartIndex());
        String prefix = text.substring(0, rootOffset);
        Position rootPosition = new Position((int) prefix.chars().filter(c -> c == '\n').count(),
                rootOffset - prefix.lastIndexOf('\n') - 1);
        // A Java local shadows a rule-unit property or an LHS binding.
        if (!DRLConsequenceDefinitions.find(uri, parsed, rootPosition, chain.get(0)).isEmpty()) return List.of();
        var declarations = DRLBindingDefinitions.find(uri, parsed, position, chain.get(0));
        String owner;
        int firstProperty;
        if (declarations.size() == 1) {
            Position declaredAt = declarations.get(0).getRange().getStart();
            Token declaration = parsed.tokenAt(new Position(declaredAt.getLine(), declaredAt.getCharacter() + 1));
            if (declaration == null) return null;
            var boundPath = boundPath(parsed.compilationUnit, declaration.getTokenIndex());
            if (boundPath == null) return null;
            owner = pathOwner(parsed, boundPath, null, classes, outputs);
            firstProperty = 1;
        } else if (declarations.isEmpty()) {
            Token drlToken = parsed.tokenAt(position);
            if (drlToken == null) return null;
            var path = enclosingPath(parsed.compilationUnit, drlToken.getTokenIndex());
            if (path != null) {
                owner = pathOwner(parsed, path, drlToken.getTokenIndex(), classes, outputs);
            } else if (inConsequence(parsed.compilationUnit, drlToken.getTokenIndex())) {
                owner = DRLDefinitionHelper.resolveUnitFqcn(parsed, classes, outputs);
            } else return null;
            if (owner == null) return null;
            firstProperty = chain.get(0).equals("this") ? 1 : 0;
            // Leave static Java receivers and unknown names to the ordinary resolver.
            if (firstProperty == 0) {
                owner = propertyType(owner, chain.get(0), outputs, false);
                if (owner == null) return null;
                firstProperty = 1;
            }
        } else return List.of();
        if (owner == null) return List.of();
        for (int i = firstProperty; i < chain.size(); i++) {
            owner = propertyType(owner, chain.get(i), outputs, false);
            if (owner == null) return List.of();
        }
        int next = token.getTokenIndex() + 1;
        while (next < tokens.size() && tokens.get(next).getChannel() != Token.DEFAULT_CHANNEL) next++;
        boolean call = next < tokens.size() && tokens.get(next).getText().equals("(");
        return JavaSourceLocator.locateMembers(owner, word, call, outputs);
    }

    private static String pathOwner(ParsedDrl parsed, DRL10Parser.XpathPrimaryContext path, Integer caret,
                                    ClassIndex classes, Set<Path> outputs) {
        String owner = DRLDefinitionHelper.resolveUnitFqcn(parsed, classes, outputs);
        if (owner == null) return null;
        for (var chunk : path.xpathChunk()) {
            if (chunk.HASH() != null) return null;
            for (var identifier : chunk.drlIdentifier()) {
                owner = propertyType(owner, identifier.getText(), outputs, true);
                if (owner == null) return null;
            }
            if (caret != null && chunk.xpathExpressionList() != null
                    && contains(chunk.xpathExpressionList(), caret)) return owner;
        }
        return caret == null ? owner : null;
    }

    private static boolean inConsequence(ParseTree node, int token) {
        if (node instanceof ParserRuleContext context && !contains(context, token)) return false;
        if (node instanceof DRL10Parser.ConsequenceBodyContext) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (inConsequence(node.getChild(i), token)) return true;
        }
        return false;
    }

    private static int previousCode(List<Token> tokens, int index) {
        do { index--; } while (index >= 0 && tokens.get(index).getChannel() != Token.DEFAULT_CHANNEL);
        return index;
    }

    private static DRL10Parser.XpathPrimaryContext boundPath(ParseTree node, int declaration) {
        if (node instanceof ParserRuleContext context && !contains(context, declaration)) return null;
        if (node instanceof DRL10Parser.LhsPatternBindContext binding) {
            if (binding.lhsPattern().size() != 1) return null;
            return binding.lhsPattern(0).xpathPrimary();
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            var found = boundPath(node.getChild(i), declaration);
            if (found != null) return found;
        }
        return null;
    }

    private static boolean contains(ParserRuleContext context, int token) {
        return context.getStart() != null && context.getStop() != null
                && context.getStart().getTokenIndex() <= token && token <= context.getStop().getTokenIndex();
    }

    private static DRL10Parser.XpathPrimaryContext enclosingPath(ParseTree node, int token) {
        if (node instanceof ParserRuleContext context && !contains(context, token)) return null;
        for (int i = 0; i < node.getChildCount(); i++) {
            var nested = enclosingPath(node.getChild(i), token);
            if (nested != null) return nested;
        }
        return node instanceof DRL10Parser.XpathPrimaryContext path ? path : null;
    }

    private static String propertyType(String owner, String property, Set<Path> outputs, boolean iterate) {
        var locations = JavaSourceLocator.locateMembers(owner, property, false, outputs);
        if (locations.size() != 1) return null;
        var location = locations.get(0);
        try {
            var lexer = new JavaLexer(CharStreams.fromString(Files.readString(Path.of(java.net.URI.create(location.getUri())))));
            lexer.removeErrorListeners();
            var parser = new JavaParser(new CommonTokenStream(lexer));
            parser.removeErrorListeners();
            var unit = parser.compilationUnit();
            String type = declaredType(unit, location.getRange().getStart());
            if (type == null) return null;
            // OOPath iterates data sources, collections and arrays. Raw or unknown generic types stay unknown.
            int generic = type.indexOf('<');
            if (generic >= 0) {
                if (!iterate) {
                    type = type.substring(0, generic);
                } else {
                    String container = type.substring(0, generic);
                    container = container.substring(container.lastIndexOf('.') + 1);
                    if (!Set.of("DataSource", "DataStream", "DataStore", "List", "Set", "Collection", "Iterable").contains(container)) return null;
                    type = type.substring(generic + 1, type.lastIndexOf('>'));
                    if (type.startsWith("?extends")) type = type.substring(8);
                    if (type.contains(",") || type.contains("<") || type.contains("?")) return null;
                }
            }
            if (type.endsWith("[]")) {
                if (!iterate) return null;
                type = type.substring(0, type.length() - 2);
            }
            for (String candidate : JavaSourceLocator.sourceTypeCandidates(type, unit)) {
                if (JavaSourceLocator.locateWithJdk(candidate, outputs) != null) return candidate;
            }
        } catch (Exception unavailable) {
            return null;
        }
        return null;
    }

    private static boolean at(Token token, Position position) {
        return token.getLine() - 1 == position.getLine() && token.getCharPositionInLine() == position.getCharacter();
    }

    private static String declaredType(ParseTree node, Position position) {
        if (node instanceof JavaParser.MethodDeclarationContext method
                && at(method.identifier().getStart(), position)) return method.typeTypeOrVoid().getText();
        if (node instanceof JavaParser.InterfaceCommonBodyDeclarationContext method
                && at(method.identifier().getStart(), position)) return method.typeTypeOrVoid().getText();
        if (node instanceof JavaParser.FieldDeclarationContext field) {
            for (var variable : field.variableDeclarators().variableDeclarator()) {
                if (at(variable.variableDeclaratorId().identifier().getStart(), position)) {
                    return field.typeType().getText() + "[]".repeat(variable.variableDeclaratorId().LBRACK().size());
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            String type = declaredType(node.getChild(i), position);
            if (type != null) return type;
        }
        return null;
    }
}
