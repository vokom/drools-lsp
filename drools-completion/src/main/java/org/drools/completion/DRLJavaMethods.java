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
        return receiver(text, position, parsed, classes, members, document, openFiles, Set.of());
    }

    static Class<?> receiver(String text, Position position, ParsedDrl parsed,
            ClassIndex classes, ClassMemberIndex members, Path document, Map<Path, String> openFiles, Set<Path> outputs) {
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
        // Inside an OOPath constraint there is no pattern type; the element type of the path plays that role.
        String ooType = implicit != null || token == null || outputs.isEmpty() ? null
                : DRLOOPathDefinitions.enclosingPathOwner(parsed, token, classes, outputs);
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
        int firstHop = 1;
        if (chain.isEmpty()) {
            String target = modifyTargetType(before, text, start, parsed, document, openFiles, classes, outputs);
            type = target != null ? target : implicit != null ? implicit : ooType;
        } else if (chain.get(0).equals("this")) type = implicit != null ? implicit : ooType;
        else if (chain.get(0).startsWith("$")) {
            var declared = DRLWorkspaceTypeIndex.build(parsed.declaredTypes(), document, openFiles);
            type = LhsBindingResolver.resolveAt(text, start, declared).get(chain.get(0).substring(1));
            if (type == null && !outputs.isEmpty()) {
                // OOPath binding such as `$passport: /passports`.
                Token head = before.get(before.size() - 2 - 2 * (chain.size() - 1));
                type = DRLOOPathDefinitions.boundPathOwner(document.toUri().toString(), parsed,
                        new Position(head.getLine() - 1, head.getCharPositionInLine()), chain.get(0), classes, outputs);
            }
        } else {
            // A bare head is a property of the enclosing pattern's type when it names one, else a type name.
            type = chain.get(0);
            Class<?> unitField = implicit == null && ooType == null && token != null && !outputs.isEmpty()
                    ? unitFieldType(chain.get(0), before, chain.size(), text, parsed, token, document, classes, members, outputs)
                    : null;
            if (unitField != null) {
                Class<?> owner = unitField;
                for (int i = 1; owner != null && i < chain.size(); i++) owner = propertyType(owner, chain.get(i));
                return owner;
            }
            if (implicit != null) {
                Class<?> implicitClass = members.resolveClass(
                        DRLCompletionHelper.resolveFqcn(implicit, implicit, parsed.compilationUnit, classes));
                if (implicitClass != null && propertyType(implicitClass, chain.get(0)) != null) {
                    type = implicit;
                    firstHop = 0;
                }
            } else if (ooType != null) {
                Class<?> ooClass = members.resolveClass(ooType);
                if (ooClass != null && propertyType(ooClass, chain.get(0)) != null) {
                    type = ooType;
                    firstHop = 0;
                }
            }
        }
        if (type == null) return null;
        String fqcn = DRLCompletionHelper.resolveFqcn(type, type, parsed.compilationUnit, classes);
        Class<?> owner = fqcn == null ? null : members.resolveClass(fqcn);
        if (owner == null && type.indexOf('.') > 0) owner = members.resolveClass(type);
        for (int i = firstHop; owner != null && i < chain.size(); i++) owner = propertyType(owner, chain.get(i));
        return owner;
    }

    /**
     * Declared type of the rule unit field {@code name} when the chain head sits in a consequence and no
     * local variable or global of that name shadows it, else {@code null}. Generic arguments are erased:
     * {@code DataStore<GroupLeader>} yields {@code DataStore}.
     */
    private static Class<?> unitFieldType(String name, List<? extends Token> before, int chainSize, String text,
            ParsedDrl parsed, int token, Path document, ClassIndex classes, ClassMemberIndex members, Set<Path> outputs) {
        if (!Character.isLowerCase(name.charAt(0)) || CONSEQUENCE_ROOTS.contains(name)) return null;
        if (!DRLOOPathDefinitions.inConsequence(parsed.compilationUnit, token)) return null;
        String unit = DRLDefinitionHelper.resolveUnitFqcn(parsed, classes, outputs);
        Class<?> unitClass = unit == null ? null : members.resolveClass(unit);
        if (unitClass == null) return null;
        if (Pattern.compile("\\bglobal\\s+[^;\\n]*\\b" + Pattern.quote(name) + "\\b").matcher(text).find()) return null;
        Token head = before.get(before.size() - 2 - 2 * (chainSize - 1));
        Position at = new Position(head.getLine() - 1, head.getCharPositionInLine());
        String uri = document.toUri().toString();
        if (!DRLConsequenceDefinitions.find(uri, parsed, at, name).isEmpty()
                || !DRLBindingDefinitions.find(uri, parsed, at, name).isEmpty()) return null;
        try {
            for (Class<?> type = unitClass; type != null; type = type.getSuperclass()) {
                for (var field : type.getDeclaredFields()) {
                    if (field.getName().equals(name)) return field.getType();
                }
            }
        } catch (LinkageError | SecurityException unavailable) { return null; }
        return null;
    }

    /** Type of the public field, or of the getter ({@code getX}, {@code isX} or {@code x}), named {@code name}; else {@code null}. */
    static Class<?> propertyType(Class<?> owner, String name) {
        try {
            return owner.getField(name).getType();
        } catch (ReflectiveOperationException | LinkageError ignored) { }
        String capital = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (String getter : new String[] {"get" + capital, "is" + capital, name}) {
            try {
                return owner.getMethod(getter).getReturnType();
            } catch (ReflectiveOperationException | LinkageError ignored) { }
        }
        return null;
    }

    /**
     * Type name of the binding being modified when the identifier at {@code start} is a setter call at
     * statement level of a {@code modify($x) { ... }} block, else {@code null}. Nested calls inside setter
     * arguments are not statement level and keep the ordinary resolution.
     */
    static String modifyTargetType(List<? extends Token> before, String text, int start, ParsedDrl parsed,
            Path document, Map<Path, String> openFiles, ClassIndex classes, Set<Path> outputs) {
        if (before.isEmpty()) return null;
        String last = before.get(before.size() - 1).getText();
        if (!last.equals("{") && !last.equals(",")) return null;
        // Innermost unclosed brace, tracking nesting; parentheses and brackets must be balanced to stay at statement level.
        Deque<Integer> braces = new ArrayDeque<>();
        int parens = 0;
        Deque<Integer> parensAtBrace = new ArrayDeque<>();
        for (int i = 0; i < before.size(); i++) {
            switch (before.get(i).getText()) {
                case "{" -> { braces.push(i); parensAtBrace.push(parens); }
                case "}" -> { if (!braces.isEmpty()) { braces.pop(); parensAtBrace.pop(); } }
                case "(", "[" -> parens++;
                case ")", "]" -> parens--;
                default -> {}
            }
        }
        if (braces.isEmpty() || parens != parensAtBrace.peek()) return null;
        int open = braces.peek();
        // Shape: modify ( identifier ) {
        if (open < 4 || !before.get(open - 1).getText().equals(")") || !before.get(open - 3).getText().equals("(")
                || !before.get(open - 4).getText().equals("modify")) return null;
        String binding = before.get(open - 2).getText();
        if (binding.isEmpty() || !Character.isJavaIdentifierStart(binding.charAt(0))) return null;
        var declared = DRLWorkspaceTypeIndex.build(parsed.declaredTypes(), document, openFiles);
        var types = LhsBindingResolver.resolveAt(text, start, declared);
        String type = types.get(binding.startsWith("$") ? binding.substring(1) : binding);
        if (type != null || outputs.isEmpty()) return type;
        // OOPath binding such as `$a: /items[...]`: the element type comes from the rule unit's data source.
        Token name = before.get(open - 2);
        return DRLOOPathDefinitions.boundPathOwner(document.toUri().toString(), parsed,
                new Position(name.getLine() - 1, name.getCharPositionInLine()), binding, classes, outputs);
    }

    /** Tokens on the default channel preceding {@code start}, for {@link #modifyTargetType}. */
    static List<? extends Token> tokensBefore(String text, int start) {
        JavaLexer lexer = new JavaLexer(CharStreams.fromString(text.substring(0, start)));
        lexer.removeErrorListeners();
        return lexer.getAllTokens().stream().filter(t -> t.getChannel() == Token.DEFAULT_CHANNEL).toList();
    }

    /** Unqualified words DRL gives a call-like shape outside Java code. */
    private static final Set<String> DRL_CALL_LIKE = Set.of("in", "from", "accumulate", "acc", "collect", "forall",
        "exists", "not", "or", "and", "over", "init", "action", "reverse", "result",
        "sum", "average", "min", "max", "count", "collectList", "collectSet", "variance");

    public static List<Diagnostic> diagnostics(String text, ClassIndex classes, ClassMemberIndex members,
                                               Path document, Map<Path, String> openFiles) {
        return diagnostics(text, classes, members, document, openFiles, Set.of());
    }

    /** As above; {@code outputs} (build output dirs) additionally lets OOPath bindings be resolved in modify blocks. */
    public static List<Diagnostic> diagnostics(String text, ClassIndex classes, ClassMemberIndex members,
                                               Path document, Map<Path, String> openFiles, Set<Path> outputs) {
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
            // DRL LHS syntax that merely looks like a call: `age in (1, 2)`, `from accumulate(...)`,
            // accumulate functions such as `sum($a)`, and the pattern inside them (`Passport($a: age)`).
            boolean qualified = i > 0 && tokens.get(i-1).getText().equals(".");
            if (!qualified && (DRL_CALL_LIKE.contains(name.getText()) || Character.isUpperCase(name.getText().charAt(0)))) continue;
            Position position = new Position(name.getLine()-1, name.getCharPositionInLine());
            if (DRLDefinitionHelper.caretInCommentOrString(parsed, position)) continue;
            // DRL function declarations/imports can supply unqualified calls independently of the pattern.
            String word = name.getText();
            if (Pattern.compile("(?s)\\bfunction\\s+[^;{}]*?\\b" + Pattern.quote(word) + "\\s*\\(").matcher(text).find()
                    || Pattern.compile("\\bimport\\s+(?:function|static)\\s+[^;]*[.]" + Pattern.quote(word) + "\\s*;").matcher(text).find()) continue;
            Class<?> receiver = receiver(text, position, parsed, classes, members, document, openFiles, outputs);
            if (receiver == null || receiver.getSimpleName().equals(word)) continue;
            // `store.addLogical(x)` is rewritten by the rule unit compiler; DataStore itself has no such method.
            if (word.equals("addLogical") && receiver.getName().startsWith("org.drools.ruleunits.api.")) continue;
            try {
                if (Arrays.stream(receiver.getMethods()).anyMatch(m -> m.getName().equals(word))) continue;
            } catch (LinkageError | SecurityException unavailable) { continue; }
            Diagnostic diagnostic = new Diagnostic(new Range(position,
                new Position(position.getLine(), position.getCharacter()+word.length())),
                "Unknown method '" + word + "' on " + receiver.getName(), DiagnosticSeverity.Error, "drools-methods");
            diagnostic.setCode("unknown-method"); result.add(diagnostic);
        }
        if (!outputs.isEmpty()) unitMemberDiagnostics(text, parsed, tokens, classes, members, document, outputs, result);
        return result;
    }

    private static final Set<String> CONSEQUENCE_ROOTS = Set.of("drools", "kcontext", "java", "javax", "jakarta", "org", "com", "io", "net");

    /**
     * Reports a misspelled rule-unit member used as the root of a member chain in a consequence, e.g.
     * {@code invalidPassportz.add(x)}. Conservative: only lowercase roots that are not locals, bindings,
     * globals, package prefixes or consequence helpers, and only when the unit class loads.
     */
    private static void unitMemberDiagnostics(String text, ParsedDrl parsed, List<? extends Token> tokens,
            ClassIndex classes, ClassMemberIndex members, Path document, Set<Path> outputs, List<Diagnostic> result) {
        String unit = DRLDefinitionHelper.resolveUnitFqcn(parsed, classes, outputs);
        if (unit == null) return;
        Class<?> owner = members.resolveClass(unit);
        if (owner == null) return;
        String uri = document.toUri().toString();
        for (int i = 0; i + 1 < tokens.size() && result.size() < 20; i++) {
            Token name = tokens.get(i);
            String word = name.getText();
            if (name.getType() != JavaLexer.IDENTIFIER || !tokens.get(i + 1).getText().equals(".")
                    || !Character.isLowerCase(word.charAt(0)) || CONSEQUENCE_ROOTS.contains(word)) continue;
            if (i > 0 && Set.of(".", "new", "::").contains(tokens.get(i - 1).getText())) continue;
            Position position = new Position(name.getLine() - 1, name.getCharPositionInLine());
            if (DRLDefinitionHelper.caretInCommentOrString(parsed, position)) continue;
            // Query inside the word: at its first column the lookup can land on the preceding whitespace token.
            Token drlToken = parsed.tokenAt(new Position(position.getLine(), position.getCharacter() + 1));
            if (drlToken == null || !DRLOOPathDefinitions.inConsequence(parsed.compilationUnit, drlToken.getTokenIndex())) continue;
            String quoted = Pattern.quote(word);
            if (Pattern.compile("\\bglobal\\s+[^;\\n]*\\b" + quoted + "\\b").matcher(text).find()
                    || Pattern.compile("(?m)^\\s*(?:import|package)\\s+(?:static\\s+|function\\s+)?" + quoted + "\\.").matcher(text).find()) continue;
            if (!DRLConsequenceDefinitions.find(uri, parsed, position, word).isEmpty()
                    || !DRLBindingDefinitions.find(uri, parsed, position, word).isEmpty()) continue;
            if (hasMember(owner, word)) continue;
            Diagnostic diagnostic = new Diagnostic(new Range(position, new Position(position.getLine(), position.getCharacter() + word.length())),
                    "Unknown member '" + word + "' on rule unit " + owner.getName(), DiagnosticSeverity.Error, "drools-methods");
            diagnostic.setCode("unknown-unit-member"); result.add(diagnostic);
        }
    }

    private static boolean hasMember(Class<?> owner, String name) {
        try {
            for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
                if (Arrays.stream(type.getDeclaredFields()).anyMatch(f -> f.getName().equals(name))) return true;
            }
            return Arrays.stream(owner.getFields()).anyMatch(f -> f.getName().equals(name))
                    || Arrays.stream(owner.getMethods()).anyMatch(m -> m.getName().equals(name));
        } catch (LinkageError | SecurityException unavailable) { return true; }
    }
}
