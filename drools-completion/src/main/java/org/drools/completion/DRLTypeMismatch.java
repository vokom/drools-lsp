package org.drools.completion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drl.parser.antlr4.DRL10Lexer;
import org.drools.drl.parser.antlr4.DRL10Parser;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

/**
 * Warns when a pattern constraint compares a field with a literal of an
 * incompatible kind, such as {@code unusedVisaPages > "3"} on an {@code int}.
 * Drools coerces these at run time, so the engine reports no build error;
 * the warning flags the likely mistake. Only {@code field OP literal} on a
 * field of the pattern's own type is checked. Controlled by
 * {@code drools.lsp.lint.typeMismatch} ({@code off | hint | info | warning | error},
 * default {@code warning}).
 */
public final class DRLTypeMismatch {

    static final String PROP = "drools.lsp.lint.typeMismatch";
    public static final String CODE = "type-mismatch";

    private static final Set<String> NUMERIC = Set.of("int", "long", "short", "byte", "float", "double",
            "Integer", "Long", "Short", "Byte", "Float", "Double", "BigDecimal", "BigInteger");
    private static final Set<String> TEXT = Set.of("String", "char", "Character");
    private static final Set<String> BOOLEAN = Set.of("boolean", "Boolean");
    private static final Set<Integer> OPERATORS = Set.of(DRL10Lexer.EQUAL, DRL10Lexer.NOTEQUAL,
            DRL10Lexer.GT, DRL10Lexer.LT, DRL10Lexer.GE, DRL10Lexer.LE);
    private static final Set<Integer> NUMBER_LITERALS = Set.of(DRL10Lexer.DECIMAL_LITERAL,
            DRL10Lexer.HEX_LITERAL, DRL10Lexer.OCT_LITERAL, DRL10Lexer.BINARY_LITERAL, DRL10Lexer.FLOAT_LITERAL);
    private static final Set<Integer> CONTINUATIONS = Set.of(DRL10Lexer.DOT, DRL10Lexer.ADD, DRL10Lexer.SUB,
            DRL10Lexer.MUL, DRL10Lexer.DIV, DRL10Lexer.MOD);

    private DRLTypeMismatch() {
    }

    public static List<Diagnostic> diagnostics(String text, ClassIndex classIndex, ClassMemberIndex memberIndex) {
        return diagnostics(text, classIndex, memberIndex, Set.of());
    }

    /** As above; {@code outputs} (build output dirs) additionally lets OOPath element types be resolved. */
    public static List<Diagnostic> diagnostics(String text, ClassIndex classIndex, ClassMemberIndex memberIndex,
                                               Set<java.nio.file.Path> outputs) {
        List<Diagnostic> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        DiagnosticSeverity severity = DRLLintHelper.severityFor(PROP, "warning");
        if (severity == null) {
            return out;
        }
        ParsedDrl parsed = ParsedDrl.of(text);
        if (parsed.compilationUnit == null) {
            return out;
        }
        Map<String, DeclaredType> declared = new HashMap<>();
        for (DeclaredType type : DRLDeclaredTypeParser.extractFromCompilationUnit(parsed.compilationUnit)) {
            declared.put(type.name, type);
        }
        scan(parsed.compilationUnit, parsed, declared, classIndex, memberIndex, outputs, severity, out);
        return out;
    }

    private static void scan(ParseTree node, ParsedDrl parsed, Map<String, DeclaredType> declared,
                             ClassIndex classIndex, ClassMemberIndex memberIndex,
                             Set<java.nio.file.Path> outputs, DiagnosticSeverity severity, List<Diagnostic> out) {
        if (node instanceof DRL10Parser.LhsPatternContext pattern
                && pattern.objectType != null && pattern.constraints() != null) {
            Map<String, String> fields = fieldTypes(pattern.objectType.getText(), parsed, declared, classIndex, memberIndex);
            if (!fields.isEmpty()) {
                check(pattern.constraints(), parsed.tokens(), fields, severity, out);
            }
        }
        if (node instanceof DRL10Parser.XpathChunkContext chunk && chunk.xpathExpressionList() != null
                && !outputs.isEmpty()) {
            // OOPath constraint such as /passports[age >= "10"]: the field owner is the path's element type.
            String owner = DRLOOPathDefinitions.enclosingPathOwner(parsed,
                    chunk.xpathExpressionList().getStart().getTokenIndex(), classIndex, outputs);
            if (owner != null) {
                Map<String, String> fields = new HashMap<>();
                for (Field field : memberIndex.membersOf(owner)) {
                    if (field.type != null && field.origin != Field.Origin.ENUM_CONSTANT) {
                        fields.put(field.name, field.type.substring(field.type.lastIndexOf('.') + 1));
                    }
                }
                if (!fields.isEmpty()) {
                    check(chunk.xpathExpressionList(), parsed.tokens(), fields, severity, out);
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            scan(node.getChild(i), parsed, declared, classIndex, memberIndex, outputs, severity, out);
        }
    }

    private static Map<String, String> fieldTypes(String typeName, ParsedDrl parsed, Map<String, DeclaredType> declared,
                                                  ClassIndex classIndex, ClassMemberIndex memberIndex) {
        String simple = typeName.substring(typeName.lastIndexOf('.') + 1);
        List<Field> members;
        DeclaredType local = declared.get(simple);
        if (local != null) {
            members = DRLDeclaredTypeParser.fieldsIncludingInherited(local, declared);
        } else {
            String fqcn = DRLCompletionHelper.resolveFqcn(typeName, simple, parsed.compilationUnit, classIndex);
            members = fqcn == null ? List.of() : memberIndex.membersOf(fqcn);
        }
        Map<String, String> result = new HashMap<>();
        for (Field field : members) {
            if (field.type != null && field.origin != Field.Origin.ENUM_CONSTANT) {
                result.put(field.name, field.type.substring(field.type.lastIndexOf('.') + 1));
            }
        }
        return result;
    }

    private static void check(ParserRuleContext constraints, CommonTokenStream stream, Map<String, String> fields,
                              DiagnosticSeverity severity, List<Diagnostic> out) {
        List<Token> tokens = new ArrayList<>();
        for (int i = constraints.getStart().getTokenIndex(); i <= constraints.getStop().getTokenIndex(); i++) {
            Token token = stream.get(i);
            if (token.getChannel() == Token.DEFAULT_CHANNEL) {
                tokens.add(token);
            }
        }
        for (int i = 0; i + 2 < tokens.size(); i++) {
            Token name = tokens.get(i);
            if (name.getType() != DRL10Lexer.IDENTIFIER || !OPERATORS.contains(tokens.get(i + 1).getType())) {
                continue;
            }
            if (i > 0 && tokens.get(i - 1).getType() == DRL10Lexer.DOT) {
                continue;
            }
            int at = i + 2;
            if (tokens.get(at).getType() == DRL10Lexer.SUB && at + 1 < tokens.size()) {
                at++;
            }
            Token literal = tokens.get(at);
            if (at + 1 < tokens.size() && CONTINUATIONS.contains(tokens.get(at + 1).getType())) {
                continue;
            }
            String literalKind = literalKind(literal);
            String fieldType = fields.get(name.getText());
            if (literalKind == null || fieldType == null) {
                continue;
            }
            String fieldKind = NUMERIC.contains(fieldType) ? "number"
                    : TEXT.contains(fieldType) ? "String"
                    : BOOLEAN.contains(fieldType) ? "boolean" : null;
            if (fieldKind == null || fieldKind.equals(literalKind)
                    || ("String".equals(fieldKind) && "char".equals(literalKind))) {
                continue;
            }
            Diagnostic d = new Diagnostic(range(name, literal),
                    "Type mismatch: '" + name.getText() + "' is " + fieldType + " but is compared with a "
                            + literalKind + " literal",
                    severity, "drools-lint");
            d.setCode(CODE);
            out.add(d);
        }
    }

    private static String literalKind(Token literal) {
        int type = literal.getType();
        if (type == DRL10Lexer.STRING_LITERAL || type == DRL10Lexer.DRL_STRING_LITERAL) {
            return "String";
        }
        if (NUMBER_LITERALS.contains(type)) {
            return "number";
        }
        if (type == DRL10Lexer.BOOL_LITERAL) {
            return "boolean";
        }
        return null;
    }

    private static Range range(Token from, Token to) {
        return new Range(new Position(from.getLine() - 1, from.getCharPositionInLine()),
                new Position(to.getLine() - 1, to.getCharPositionInLine() + (to.getStopIndex() - to.getStartIndex() + 1)));
    }
}
