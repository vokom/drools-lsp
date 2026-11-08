package org.drools.completion;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import org.antlr.v4.runtime.*;
import org.drools.drl.parser.antlr4.JavaLexer;
import org.eclipse.lsp4j.*;

/** Conservative method checks: never report a missing method on an unresolved receiver. */
public final class DRLJavaMethods {
    private DRLJavaMethods() {}

    static Class<?> receiver(String text, Position position, ParsedDrl parsed,
            ClassIndex classes, ClassMemberIndex members, Path document, Map<Path, String> openFiles) {
        Range range = DRLDefinitionHelper.wordRangeAt(text, position);
        if (range == null) return null;
        int offset = 0;
        for (int line = 0; line < position.getLine(); line++) {
            int newline = text.indexOf('\n', offset);
            if (newline < 0) return null;
            offset = newline + 1;
        }
        int start = offset + range.getStart().getCharacter();
        JavaLexer lexer = new JavaLexer(CharStreams.fromString(text.substring(0, start)));
        lexer.removeErrorListeners();
        List<? extends Token> before = lexer.getAllTokens().stream().filter(t -> t.getChannel() == Token.DEFAULT_CHANNEL).toList();
        Integer token = parsed.tokenIndexAt(position);
        String implicit = token == null ? null : DRLCompletionHelper.findEnclosingPatternTypeName(parsed.compilationUnit, token);
        List<String> chain = new ArrayList<>();
        if (!before.isEmpty() && before.get(before.size()-1).getText().equals(".")) {
            int index = before.size()-2;
            while (index >= 0) {
                String part = before.get(index).getText();
                if (part.isEmpty() || !Character.isJavaIdentifierStart(part.charAt(0))) return null;
                chain.add(0, part);
                if (index < 1 || !before.get(index-1).getText().equals(".")) break;
                index -= 2;
            }
            if (chain.isEmpty()) return null;
        }
        String type;
        if (chain.isEmpty() || chain.get(0).equals("this")) type = implicit;
        else if (chain.get(0).startsWith("$")) {
            var declared = DRLWorkspaceTypeIndex.build(parsed.declaredTypes(), document, openFiles);
            type = LhsBindingResolver.resolveAt(text, start, declared).get(chain.get(0).substring(1));
        } else type = chain.get(0);
        if (type == null) return null;
        String fqcn = DRLCompletionHelper.resolveFqcn(type, type, parsed.compilationUnit, classes);
        Class<?> owner = members.resolveClass(fqcn);
        try {
            for (int i=1; owner != null && i<chain.size(); i++) owner = owner.getField(chain.get(i)).getType();
        } catch (ReflectiveOperationException | LinkageError e) { return null; }
        return owner;
    }

    public static List<Diagnostic> diagnostics(String text, ClassIndex classes, ClassMemberIndex members,
                                               Path document, Map<Path, String> openFiles) {
        if (text == null || text.isBlank()) return List.of();
        ParsedDrl parsed = ParsedDrl.of(text);
        JavaLexer lexer = new JavaLexer(CharStreams.fromString(text)); lexer.removeErrorListeners();
        List<? extends Token> tokens = lexer.getAllTokens().stream().filter(t -> t.getChannel()==Token.DEFAULT_CHANNEL).toList();
        List<Diagnostic> result = new ArrayList<>();
        for (int i=0; i+1<tokens.size() && result.size()<20; i++) {
            Token name = tokens.get(i);
            if (name.getType()!=JavaLexer.IDENTIFIER || !tokens.get(i+1).getText().equals("(")) continue;
            if (i > 0 && tokens.get(i-1).getText().equals("new")) continue;
            if (Set.of("eval", "modify", "insert", "insertLogical", "delete", "retract", "update").contains(name.getText())) continue;
            Position position = new Position(name.getLine()-1, name.getCharPositionInLine());
            if (DRLDefinitionHelper.caretInCommentOrString(parsed, position)) continue;
            // DRL function declarations/imports can supply unqualified calls independently of the pattern.
            String word = name.getText();
            if (Pattern.compile("(?s)\\bfunction\\s+[^;{}]*?\\b" + Pattern.quote(word) + "\\s*\\(").matcher(text).find()
                    || Pattern.compile("\\bimport\\s+(?:function|static)\\s+[^;]*[.]" + Pattern.quote(word) + "\\s*;").matcher(text).find()) continue;
            Class<?> receiver = receiver(text, position, parsed, classes, members, document, openFiles);
            if (receiver == null || receiver.getSimpleName().equals(word)) continue;
            try {
                if (Arrays.stream(receiver.getMethods()).anyMatch(m -> m.getName().equals(word))) continue;
            } catch (LinkageError | SecurityException unavailable) { continue; }
            Diagnostic diagnostic = new Diagnostic(new Range(position,
                new Position(position.getLine(), position.getCharacter()+word.length())),
                "Unknown method '" + word + "' on " + receiver.getName(), DiagnosticSeverity.Error, "drools-methods");
            diagnostic.setCode("unknown-method"); result.add(diagnostic);
        }
        return result;
    }
}
