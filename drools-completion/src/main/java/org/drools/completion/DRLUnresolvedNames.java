package org.drools.completion;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.antlr.v4.runtime.Token;
import org.drools.drl.parser.antlr4.DRL10Lexer;
import org.drools.drl.parser.antlr4.DRL10Parser;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

/**
 * Immediate findings for names in a Java-dialect consequence that nothing declares: a
 * {@code $binding} that no pattern or local declaration provides, and an unqualified call that is
 * neither a DRL function, a statically imported method nor a Drools built-in. The engine reports the
 * same mistakes, but only after a save and a project build. Controlled by
 * {@code drools.lsp.lint.unresolvedNames} ({@code off | hint | info | warning | error}, default
 * {@code error}).
 */
public final class DRLUnresolvedNames {

    static final String PROP = "drools.lsp.lint.unresolvedNames";
    public static final String CODE = "unresolved-name";

    private static final Pattern BINDING = Pattern.compile("(?<![\\w$])\\$[A-Za-z_]\\w*");
    private static final Pattern CALL = Pattern.compile("(?<![\\w$])([A-Za-z_]\\w*)\\s*\\(");
    private static final Pattern MODIFY = Pattern.compile("(?<![\\w$.])modify\\s*\\(");
    private static final Set<String> NOT_CALLS = Set.of("if", "for", "while", "switch", "catch", "synchronized",
            "return", "super", "this", "throw", "new", "else", "assert", "try", "do", "case", "yield",
            "insert", "insertLogical", "update", "modify", "delete", "retract", "setFocus", "with");

    private DRLUnresolvedNames() {
    }

    public static List<Diagnostic> diagnostics(String uri, String text, Path documentPath, Map<Path, String> openFiles) {
        List<Diagnostic> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        DiagnosticSeverity severity = DRLLintHelper.severityFor(PROP, "error");
        if (severity == null) {
            return out;
        }
        ParsedDrl parsed = ParsedDrl.of(text);
        if (parsed.compilationUnit == null) {
            return out;
        }
        Set<String> allowedCalls = new HashSet<>(DRLFunctionDefinitions.names(parsed, documentPath, openFiles));
        boolean wildcardStatic = collectStaticImports(parsed, allowedCalls);
        for (DRL10Parser.DrlStatementdefContext statement : parsed.compilationUnit.drlStatementdef()) {
            DRL10Parser.RuledefContext rule = statement.ruledef();
            if (rule == null || rule.getStart() == null || rule.getStop() == null) {
                continue;
            }
            if (rule.getText().replace(" ", "").contains("dialect\"mvel\"")) {
                continue;
            }
            List<Token> chunks = new ArrayList<>();
            for (int i = rule.getStart().getTokenIndex(); i <= rule.getStop().getTokenIndex(); i++) {
                Token token = parsed.tokens().get(i);
                if (token.getType() == DRL10Lexer.RHS_CHUNK) {
                    chunks.add(token);
                }
            }
            if (!chunks.isEmpty()) {
                check(uri, parsed, chunks, allowedCalls, wildcardStatic, severity, out);
            }
        }
        return out;
    }

    private static boolean collectStaticImports(ParsedDrl parsed, Set<String> allowed) {
        boolean wildcard = false;
        for (DRL10Parser.DrlStatementdefContext statement : parsed.compilationUnit.drlStatementdef()) {
            if (statement.importdef() instanceof DRL10Parser.ImportStandardDefContext imp
                    && (imp.STATIC() != null || imp.DRL_FUNCTION() != null) && imp.drlQualifiedName() != null) {
                if (imp.MUL() != null) {
                    wildcard = true;
                } else {
                    String name = imp.drlQualifiedName().getText();
                    allowed.add(name.substring(name.lastIndexOf('.') + 1));
                }
            }
        }
        return wildcard;
    }

    private static void check(String uri, ParsedDrl parsed, List<Token> chunks, Set<String> allowedCalls,
                              boolean wildcardStatic, DiagnosticSeverity severity, List<Diagnostic> out) {
        StringBuilder joined = new StringBuilder();
        List<Integer> starts = new ArrayList<>();
        for (Token chunk : chunks) {
            starts.add(joined.length());
            joined.append(chunk.getText()).append('\n');
        }
        String body = joined.toString();
        List<int[]> modifyBlocks = modifyBlocks(body);

        Matcher bindings = BINDING.matcher(body);
        while (bindings.find()) {
            Position at = positionOf(chunks, starts, bindings.start());
            if (at == null) {
                continue;
            }
            Position inside = new Position(at.getLine(), at.getCharacter() + 1);
            String word = bindings.group();
            if (DRLBindingDefinitions.find(uri, parsed, inside, word).isEmpty()
                    && DRLConsequenceDefinitions.find(uri, parsed, inside, word).isEmpty()) {
                add(out, at, word, "Cannot resolve symbol '" + word + "'", severity);
            }
        }
        if (wildcardStatic) {
            return;
        }
        Matcher calls = CALL.matcher(body);
        while (calls.find()) {
            String name = calls.group(1);
            int start = calls.start(1);
            if (NOT_CALLS.contains(name) || allowedCalls.contains(name) || Character.isUpperCase(name.charAt(0))
                    || insideAny(modifyBlocks, start) || followsDotOrNew(body, start)) {
                continue;
            }
            Position at = positionOf(chunks, starts, start);
            if (at != null) {
                add(out, at, name, "Cannot resolve method '" + name + "'", severity);
            }
        }
    }

    private static boolean followsDotOrNew(String body, int start) {
        int i = start - 1;
        while (i >= 0 && Character.isWhitespace(body.charAt(i))) {
            i--;
        }
        if (i >= 0 && body.charAt(i) == '.') {
            return true;
        }
        return i >= 2 && body.startsWith("new", i - 2) && (i < 3 || !Character.isJavaIdentifierPart(body.charAt(i - 3)));
    }

    private static boolean insideAny(List<int[]> blocks, int offset) {
        for (int[] block : blocks) {
            if (offset >= block[0] && offset <= block[1]) {
                return true;
            }
        }
        return false;
    }

    /** Character ranges of the braces following {@code modify(...)}. */
    private static List<int[]> modifyBlocks(String body) {
        List<int[]> blocks = new ArrayList<>();
        Matcher modify = MODIFY.matcher(body);
        while (modify.find()) {
            int i = modify.end();
            int depth = 1;
            while (i < body.length() && depth > 0) {
                char c = body.charAt(i++);
                if (c == '(') depth++;
                else if (c == ')') depth--;
            }
            while (i < body.length() && Character.isWhitespace(body.charAt(i))) i++;
            if (i < body.length() && body.charAt(i) == '{') {
                int open = i;
                int braces = 0;
                for (; i < body.length(); i++) {
                    if (body.charAt(i) == '{') braces++;
                    else if (body.charAt(i) == '}' && --braces == 0) break;
                }
                blocks.add(new int[] {open, i});
            }
        }
        return blocks;
    }

    private static Position positionOf(List<Token> chunks, List<Integer> starts, int offset) {
        for (int i = chunks.size() - 1; i >= 0; i--) {
            if (offset >= starts.get(i)) {
                Token chunk = chunks.get(i);
                int within = offset - starts.get(i);
                return within > chunk.getText().length() ? null
                        : new Position(chunk.getLine() - 1, chunk.getCharPositionInLine() + within);
            }
        }
        return null;
    }

    private static void add(List<Diagnostic> out, Position at, String word, String message, DiagnosticSeverity severity) {
        Diagnostic d = new Diagnostic(new Range(at, new Position(at.getLine(), at.getCharacter() + word.length())),
                message, severity, "drools-lint");
        d.setCode(CODE);
        out.add(d);
    }
}
