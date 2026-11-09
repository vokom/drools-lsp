/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.drools.completion;

import java.nio.file.Path;
import java.text.DateFormatSymbols;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DiagnosticTag;
import org.eclipse.lsp4j.Range;

/**
 * Parser-free checks on the file's top-level structure: imports, rule names,
 * rule attributes, clause order and string literals. Each pass is gated by its
 * own system property ({@code off | hint | info | warning | error}):
 *
 * <pre>
 *   drools.lsp.lint.duplicateRuleNames  = warning
 *   drools.lsp.lint.duplicateImports    = warning
 *   drools.lsp.lint.unusedImports       = hint     (tagged Unnecessary)
 *   drools.lsp.lint.unresolvedImports   = warning  (needs the resolved classpath)
 *   drools.lsp.lint.invalidAttributes   = error    (salience, date-effective, date-expires)
 *   drools.lsp.lint.clauseOrder         = error
 *   drools.lsp.lint.unterminatedStrings = error
 * </pre>
 *
 * Positions come from {@link DRLLintHelper#sanitize}, which blanks comments and
 * string contents in place, so offsets in the sanitized and the original text
 * agree. Quoted values (rule names, dates) are read from the original text.
 */
public final class DRLStructureLint {

    public static final String SOURCE = "drools-lint";
    public static final String DUPLICATE_RULE_NAME = "duplicate-rule-name";
    public static final String DUPLICATE_IMPORT = "duplicate-import";
    public static final String UNUSED_IMPORT = "unused-import";
    public static final String UNRESOLVED_IMPORT = "unresolved-import";
    public static final String INVALID_SALIENCE = "invalid-salience";
    public static final String INVALID_DATE = "invalid-date-attribute";
    public static final String CLAUSE_ORDER = "clause-order";
    public static final String UNTERMINATED_STRING = "unterminated-string";

    static final String PROP_DUPLICATE_RULE_NAMES = "drools.lsp.lint.duplicateRuleNames";
    static final String PROP_DUPLICATE_IMPORTS = "drools.lsp.lint.duplicateImports";
    static final String PROP_UNUSED_IMPORTS = "drools.lsp.lint.unusedImports";
    static final String PROP_UNRESOLVED_IMPORTS = "drools.lsp.lint.unresolvedImports";
    static final String PROP_INVALID_ATTRIBUTES = "drools.lsp.lint.invalidAttributes";
    static final String PROP_CLAUSE_ORDER = "drools.lsp.lint.clauseOrder";
    static final String PROP_UNTERMINATED_STRINGS = "drools.lsp.lint.unterminatedStrings";

    private static final int MAX_PER_PASS = 50;

    /** {@code import [static|function|accumulate] name[.name]*[.*] [;]}, anchored at line start. */
    private static final Pattern IMPORT = Pattern.compile(
            "(?m)^[ \\t]*import\\s+(?:(static|function|accumulate)\\s+)?"
                    + "([A-Za-z_$][\\w$]*(?:\\s*\\.\\s*(?:[A-Za-z_$][\\w$]*|\\*))*)[^\\n;]*;?");
    private static final Pattern RULE_HEADER = Pattern.compile("(?m)^[ \\t]*rule\\s+");
    private static final Pattern RULE_OR_QUERY = Pattern.compile("(?m)^[ \\t]*(?:rule|query)\\b");
    private static final Pattern WHEN_LINE = Pattern.compile("(?m)^[ \\t]*when\\b");
    private static final Pattern THEN_LINE = Pattern.compile("(?m)^[ \\t]*then\\b");
    private static final Pattern END_LINE = Pattern.compile("(?m)^[ \\t]*end\\b");
    private static final Pattern ATTRIBUTE = Pattern.compile("(?<![\\w-])(salience|date-effective|date-expires)\\s+");
    private static final Pattern NUMBER = Pattern.compile(
            "[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?[fFdDlL]?");
    private static final Pattern IDENTIFIER_START = Pattern.compile("[A-Za-z_$]");

    private DRLStructureLint() {
    }

    /** The checks that need only {@code text}: rule names, imports, attributes, clause order and strings. */
    public static List<Diagnostic> diagnostics(String text) {
        List<Diagnostic> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        String sanitized = DRLLintHelper.sanitize(text);
        run(PROP_DUPLICATE_RULE_NAMES, "warning", out, (s, o) -> duplicateRuleNames(text, sanitized, s, o));
        run(PROP_DUPLICATE_IMPORTS, "warning", out, (s, o) -> duplicateImports(sanitized, s, o));
        run(PROP_INVALID_ATTRIBUTES, "error", out, (s, o) -> invalidAttributes(text, sanitized, s, o));
        run(PROP_CLAUSE_ORDER, "error", out, (s, o) -> clauseOrder(sanitized, s, o));
        run(PROP_UNTERMINATED_STRINGS, "error", out, (s, o) -> unterminatedStrings(text, s, o));
        return out;
    }

    /**
     * Imports whose simple name is never referenced in this file or in a
     * same-package sibling. Drools merges a package's imports across its files,
     * so a sibling may rely on an import declared here.
     */
    public static List<Diagnostic> unusedImports(String text, Path documentPath, Map<Path, String> openFiles) {
        List<Diagnostic> out = new ArrayList<>();
        DiagnosticSeverity severity = DRLLintHelper.severityFor(PROP_UNUSED_IMPORTS, "hint");
        if (text == null || text.isBlank() || severity == null) {
            return out;
        }
        String sanitized = DRLLintHelper.sanitize(text);
        List<ImportStatement> imports = imports(sanitized);
        if (imports.stream().allMatch(imp -> imp.modifier != null || imp.wildcard())) {
            return out;
        }
        // Same-package siblings come from the cached file info; only those are read as text.
        String ownPackage = DRLDeclaredTypeParser.parseFileInfo(text).packageName;
        Set<String> samePackage = new HashSet<>();
        DRLWorkspaceTypeIndex.forEachSiblingInfo(documentPath, openFiles, (info, uri) -> {
            if (ownPackage.equals(info.packageName)) {
                samePackage.add(uri);
            }
        });
        List<String[]> scopes = new ArrayList<>();
        scopes.add(useScopes(text, sanitized, imports));
        if (!samePackage.isEmpty()) {
            DRLWorkspaceTypeIndex.forEachSiblingFile(documentPath, openFiles, (uri, siblingText) -> {
                if (samePackage.contains(uri)) {
                    String siblingSanitized = DRLLintHelper.sanitize(siblingText);
                    scopes.add(useScopes(siblingText, siblingSanitized, imports(siblingSanitized)));
                }
            });
        }
        for (ImportStatement imp : imports) {
            if (imp.modifier != null || imp.wildcard() || out.size() >= MAX_PER_PASS) {
                continue;
            }
            String simple = Pattern.quote(imp.simpleName());
            Pattern use = Pattern.compile("(?<![\\w$])" + simple + "(?![\\w$])");
            // An inline cast (address#LongAddress, /addresses#Abroad) follows a name or bracket
            // directly; sanitize blanks it as a '#' comment, so it is matched in the original text.
            Pattern cast = Pattern.compile("(?<=[\\w$)\\]])#\\s*" + simple + "(?![\\w$])");
            boolean used = false;
            for (int i = 0; !used && i < scopes.size(); i++) {
                used = use.matcher(scopes.get(i)[0]).find() || cast.matcher(scopes.get(i)[1]).find();
            }
            if (!used) {
                Diagnostic d = diagnostic(sanitized, imp.start, imp.end,
                        "Import '" + imp.name + "' is never used", severity, UNUSED_IMPORT);
                d.setTags(List.of(DiagnosticTag.Unnecessary));
                out.add(d);
            }
        }
        return out;
    }

    /**
     * Imports that name no class on the resolved classpath, in the server JDK,
     * or among the DRL-declared types of the document and its siblings. Runs
     * only once the classpath has resolved; before that every dependency import
     * would look unknown. Nested types count as resolved when their outer type is.
     */
    public static List<Diagnostic> unresolvedImports(String text, Path documentPath, Map<Path, String> openFiles,
                                                     ClassIndex classIndex, boolean classpathResolved) {
        List<Diagnostic> out = new ArrayList<>();
        DiagnosticSeverity severity = DRLLintHelper.severityFor(PROP_UNRESOLVED_IMPORTS, "warning");
        if (text == null || text.isBlank() || severity == null || !classpathResolved || classIndex == null) {
            return out;
        }
        String sanitized = DRLLintHelper.sanitize(text);
        List<ImportStatement> imports = imports(sanitized);
        if (imports.isEmpty()) {
            return out;
        }
        Set<String> declared = new HashSet<>(DRLWorkspaceTypeIndex.build(text, documentPath, openFiles).keySet());
        Set<String> drlPackages = new HashSet<>();
        drlPackages.add(DRLDeclaredTypeParser.parseFileInfo(text).packageName);
        DRLWorkspaceTypeIndex.forEachSiblingInfo(documentPath, openFiles, (info, uri) -> drlPackages.add(info.packageName));
        Set<String> indexedPackages = null;
        for (ImportStatement imp : imports) {
            if (imp.modifier != null || out.size() >= MAX_PER_PASS) {
                continue;
            }
            boolean resolved;
            if (imp.wildcard()) {
                String owner = imp.name.substring(0, imp.name.length() - 2);
                if (indexedPackages == null) {
                    indexedPackages = new HashSet<>();
                    for (String fqcn : classIndex.getAll()) {
                        int dot = fqcn.lastIndexOf('.');
                        if (dot > 0) {
                            indexedPackages.add(fqcn.substring(0, dot));
                        }
                    }
                }
                resolved = indexedPackages.contains(owner) || drlPackages.contains(owner)
                        || jdkPackage(owner) || typeResolves(owner, classIndex);
            } else {
                resolved = declared.contains(imp.simpleName()) || typeResolves(imp.name, classIndex);
            }
            if (!resolved) {
                out.add(diagnostic(sanitized, imp.nameStart, imp.nameEnd,
                        "Cannot resolve import '" + imp.name + "'. Check the name, or compile the project and its dependencies.",
                        severity, UNRESOLVED_IMPORT));
            }
        }
        return out;
    }

    // ── duplicate rule names ────────────────────────────────────────────

    private static void duplicateRuleNames(String text, String sanitized, DiagnosticSeverity severity, List<Diagnostic> out) {
        Map<String, Integer> firstLine = new HashMap<>();
        Matcher m = RULE_HEADER.matcher(sanitized);
        while (m.find() && out.size() < MAX_PER_PASS) {
            int[] span = ruleName(text, m.end());
            if (span == null) {
                continue;
            }
            String name = unquote(text.substring(span[0], span[1]));
            Range range = DRLLintHelper.rangeOf(sanitized, span[0], span[1]);
            Integer first = firstLine.putIfAbsent(name, range.getStart().getLine());
            if (first != null) {
                Diagnostic d = diagnostic(sanitized, span[0], span[1],
                        "Duplicate rule name '" + name + "' (first declared at line " + (first + 1) + ")",
                        severity, DUPLICATE_RULE_NAME);
                d.setData(name);
                out.add(d);
            }
        }
    }

    /** Every rule name in {@code text}, unquoted, as the duplicate-name pass reads them. */
    static Set<String> ruleNames(String text) {
        Set<String> names = new HashSet<>();
        Matcher m = RULE_HEADER.matcher(DRLLintHelper.sanitize(text));
        while (m.find()) {
            int[] span = ruleName(text, m.end());
            if (span != null) {
                names.add(unquote(text.substring(span[0], span[1])));
            }
        }
        return names;
    }

    /** The span of the rule name starting at {@code from}: a quoted string or an identifier. */
    static int[] ruleName(String text, int from) {
        if (from >= text.length()) {
            return null;
        }
        char c = text.charAt(from);
        if (c == '"' || c == '\'') {
            int close = text.indexOf(c, from + 1);
            int eol = text.indexOf('\n', from);
            if (close < 0 || (eol >= 0 && close > eol)) {
                return null;
            }
            return new int[]{from, close + 1};
        }
        if (!IDENTIFIER_START.matcher(String.valueOf(c)).matches()) {
            return null;
        }
        int end = from + 1;
        while (end < text.length() && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_'
                || text.charAt(end) == '$')) {
            end++;
        }
        return new int[]{from, end};
    }

    private static String unquote(String name) {
        if (name.length() >= 2 && (name.charAt(0) == '"' || name.charAt(0) == '\'')) {
            return name.substring(1, name.length() - 1);
        }
        return name;
    }

    // ── imports ─────────────────────────────────────────────────────────

    record ImportStatement(String modifier, String name, int start, int end, int nameStart, int nameEnd) {
        boolean wildcard() {
            return name.endsWith(".*");
        }

        String simpleName() {
            return name.substring(name.lastIndexOf('.') + 1);
        }
    }

    static List<ImportStatement> imports(String sanitized) {
        List<ImportStatement> out = new ArrayList<>();
        Matcher m = IMPORT.matcher(sanitized);
        while (m.find()) {
            int start = m.start();
            while (start < m.end() && Character.isWhitespace(sanitized.charAt(start))) {
                start++;
            }
            int end = m.end();
            while (end > start && Character.isWhitespace(sanitized.charAt(end - 1))) {
                end--;
            }
            String name = m.group(2).replaceAll("\\s+", "");
            out.add(new ImportStatement(m.group(1), name, start, end, m.start(2), m.end(2)));
        }
        return out;
    }

    /**
     * The texts in which an import counts as used: the sanitized text and the
     * original text (for inline casts), both with import and package lines blanked.
     * Sanitizing preserves offsets, so the same import spans apply to both.
     */
    private static String[] useScopes(String text, String sanitized, List<ImportStatement> imports) {
        return new String[]{blankImports(sanitized, sanitized, imports), blankImports(text, sanitized, imports)};
    }

    private static String blankImports(String text, String sanitized, List<ImportStatement> imports) {
        char[] chars = text.toCharArray();
        for (ImportStatement imp : imports) {
            for (int i = imp.start; i < imp.end; i++) {
                if (chars[i] != '\n' && chars[i] != '\r') {
                    chars[i] = ' ';
                }
            }
        }
        // The package name is not a use of an imported type either.
        Matcher pkg = Pattern.compile("(?m)^[ \\t]*package\\s+[\\w$.\\s]*;?").matcher(sanitized);
        if (pkg.find()) {
            for (int i = pkg.start(); i < pkg.end(); i++) {
                if (chars[i] != '\n' && chars[i] != '\r') {
                    chars[i] = ' ';
                }
            }
        }
        return new String(chars);
    }

    private static void duplicateImports(String sanitized, DiagnosticSeverity severity, List<Diagnostic> out) {
        Map<String, Integer> firstLine = new HashMap<>();
        for (ImportStatement imp : imports(sanitized)) {
            String key = (imp.modifier == null ? "" : imp.modifier + " ") + imp.name;
            Range range = DRLLintHelper.rangeOf(sanitized, imp.start, imp.end);
            Integer first = firstLine.putIfAbsent(key, range.getStart().getLine());
            if (first != null && out.size() < MAX_PER_PASS) {
                Diagnostic d = diagnostic(sanitized, imp.start, imp.end,
                        "Duplicate import '" + imp.name + "' (also at line " + (first + 1) + ")",
                        severity, DUPLICATE_IMPORT);
                d.setTags(List.of(DiagnosticTag.Unnecessary));
                out.add(d);
            }
        }
    }

    /** Whether {@code name} loads as a class, or names a nested type of an indexed or JDK class. */
    private static boolean typeResolves(String name, ClassIndex classIndex) {
        String[] parts = name.split("\\.");
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                prefix.append('.');
            }
            prefix.append(parts[i]);
            String candidate = prefix.toString();
            if (classIndex.forSimpleName(parts[i]).contains(candidate) || jdkClass(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean jdkClass(String fqcn) {
        try {
            Class.forName(fqcn, false, ClassLoader.getPlatformClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static boolean jdkPackage(String pkg) {
        for (Module module : ModuleLayer.boot().modules()) {
            if (module.getPackages().contains(pkg)) {
                return true;
            }
        }
        return false;
    }

    // ── rule attributes ─────────────────────────────────────────────────

    private static void invalidAttributes(String text, String sanitized, DiagnosticSeverity severity, List<Diagnostic> out) {
        Matcher rule = RULE_HEADER.matcher(sanitized);
        while (rule.find() && out.size() < MAX_PER_PASS) {
            int headerEnd = headerEnd(sanitized, rule.end());
            Matcher attr = ATTRIBUTE.matcher(sanitized).region(rule.end(), headerEnd);
            while (attr.find() && out.size() < MAX_PER_PASS) {
                int valueStart = attr.end();
                if (valueStart >= headerEnd) {
                    continue;
                }
                if ("salience".equals(attr.group(1))) {
                    checkSalience(text, sanitized, valueStart, headerEnd, severity, out);
                } else {
                    checkDate(attr.group(1), text, sanitized, valueStart, severity, out);
                }
            }
        }
    }

    /** End of a rule header: its first {@code when} or {@code then} line, the next rule or query, or {@code end}. */
    private static int headerEnd(String sanitized, int from) {
        int end = sanitized.length();
        for (Pattern p : List.of(WHEN_LINE, THEN_LINE, RULE_OR_QUERY, END_LINE)) {
            Matcher m = p.matcher(sanitized);
            if (m.find(from)) {
                end = Math.min(end, m.start());
            }
        }
        return end;
    }

    /**
     * Salience accepts any expression, including variables and globals, so only
     * literals that can never be an int are reported: strings, decimals, booleans and null.
     */
    private static void checkSalience(String text, String sanitized, int start, int limit,
                                      DiagnosticSeverity severity, List<Diagnostic> out) {
        char c = text.charAt(start);
        int end;
        String reason = null;
        if (c == '"' || c == '\'') {
            int close = text.indexOf(c, start + 1);
            end = close < 0 || close >= limit ? start + 1 : close + 1;
            reason = "a string";
        } else {
            Matcher number = NUMBER.matcher(text).region(start, limit);
            Matcher word = Pattern.compile("[A-Za-z_$][\\w$]*").matcher(text).region(start, limit);
            if (number.lookingAt()) {
                end = number.end();
                String literal = number.group();
                if (literal.matches(".*[.eEfFdD].*")) {
                    reason = "a decimal number";
                }
            } else if (word.lookingAt()) {
                end = word.end();
                String literal = word.group();
                boolean qualified = end < limit && text.charAt(end) == '.';
                if (!qualified && (literal.equals("true") || literal.equals("false") || literal.equals("null"))) {
                    reason = "'" + literal + "'";
                }
            } else {
                return;
            }
        }
        if (reason != null) {
            out.add(diagnostic(sanitized, start, end,
                    "Salience must be an integer or an integer expression, not " + reason, severity, INVALID_SALIENCE));
        }
    }

    private static void checkDate(String attribute, String text, String sanitized, int start,
                                  DiagnosticSeverity severity, List<Diagnostic> out) {
        char c = text.charAt(start);
        if (c != '"' && c != '\'') {
            return;
        }
        int close = text.indexOf(c, start + 1);
        int eol = text.indexOf('\n', start);
        if (close < 0 || (eol >= 0 && close > eol)) {
            return; // the unterminated-string pass reports this
        }
        String value = text.substring(start + 1, close);
        if (!isDroolsDate(value)) {
            out.add(diagnostic(sanitized, start, close + 1,
                    "Invalid " + attribute + " date '" + value + "'; expected the format '" + dateFormatMask() + "'",
                    severity, INVALID_DATE));
        }
    }

    /**
     * Mirrors {@code org.drools.util.DateUtils.parseDate} (drools-util 10.2.0): a
     * lenient {@code SimpleDateFormat} with {@code drools.dateformat}
     * (default {@code d-MMM-yyyy}) in the {@code drools.defaultlanguage} /
     * {@code drools.defaultcountry} locale, then {@code Locale.UK}. The server
     * reads these from its own JVM, so they must match the runtime's settings.
     */
    static boolean isDroolsDate(String value) {
        String mask = dateFormatMask();
        Locale configured = new Locale(
                System.getProperty("drools.defaultlanguage", Locale.getDefault().getLanguage()),
                System.getProperty("drools.defaultcountry", Locale.getDefault().getCountry()));
        try {
            new SimpleDateFormat(mask, new DateFormatSymbols(configured)).parse(value);
            return true;
        } catch (ParseException | IllegalArgumentException e) {
            try {
                new SimpleDateFormat(mask, Locale.UK).parse(value);
                return true;
            } catch (ParseException | IllegalArgumentException e2) {
                return false;
            }
        }
    }

    private static String dateFormatMask() {
        return System.getProperty("drools.dateformat", "d-MMM-yyyy");
    }

    // ── clause order ────────────────────────────────────────────────────

    /** A rule whose {@code then} line comes before its {@code when} line. */
    private static void clauseOrder(String sanitized, DiagnosticSeverity severity, List<Diagnostic> out) {
        Matcher rule = RULE_HEADER.matcher(sanitized);
        while (rule.find() && out.size() < MAX_PER_PASS) {
            int limit = sanitized.length();
            Matcher next = RULE_OR_QUERY.matcher(sanitized);
            if (next.find(rule.end())) {
                limit = next.start();
            }
            Matcher end = END_LINE.matcher(sanitized).region(rule.end(), limit);
            if (end.find()) {
                limit = end.start();
            }
            Matcher then = THEN_LINE.matcher(sanitized).region(rule.end(), limit);
            if (!then.find()) {
                continue;
            }
            Matcher when = WHEN_LINE.matcher(sanitized).region(then.end(), limit);
            if (when.find() && !WHEN_LINE.matcher(sanitized).region(rule.end(), then.start()).find()) {
                int start = when.end() - "when".length();
                out.add(diagnostic(sanitized, start, when.end(),
                        "'when' must come before 'then' in a rule", severity, CLAUSE_ORDER));
            }
        }
    }

    // ── unterminated strings ────────────────────────────────────────────

    /**
     * A {@code "} or {@code '} literal still open at the end of its line. DRL
     * string literals do not span lines; Java text blocks ({@code """}) are skipped.
     */
    private static void unterminatedStrings(String text, DiagnosticSeverity severity, List<Diagnostic> out) {
        boolean blockComment = false;
        boolean textBlock = false;
        int i = 0;
        int n = text.length();
        while (i < n && out.size() < MAX_PER_PASS) {
            char c = text.charAt(i);
            if (blockComment) {
                if (c == '*' && i + 1 < n && text.charAt(i + 1) == '/') {
                    blockComment = false;
                    i += 2;
                } else {
                    i++;
                }
                continue;
            }
            if (text.startsWith("\"\"\"", i)) {
                textBlock = !textBlock;
                i += 3;
                continue;
            }
            if (textBlock) {
                i++;
                continue;
            }
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                blockComment = true;
                i += 2;
            } else if ((c == '/' && i + 1 < n && text.charAt(i + 1) == '/') || c == '#') {
                while (i < n && text.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && text.charAt(j) != c && text.charAt(j) != '\n' && text.charAt(j) != '\r') {
                    j += text.charAt(j) == '\\' ? 2 : 1;
                }
                if (j >= n || text.charAt(j) != c) {
                    int lineEnd = Math.min(j, n);
                    out.add(diagnostic(text, i, lineEnd, "Unterminated string literal", severity, UNTERMINATED_STRING));
                    i = lineEnd;
                } else {
                    i = j + 1;
                }
            } else {
                i++;
            }
        }
    }

    // ── shared ──────────────────────────────────────────────────────────

    /** Runs {@code pass} into its own list (each pass caps itself) unless its property turns it off. */
    private static void run(String property, String defaultValue, List<Diagnostic> out,
                            BiConsumer<DiagnosticSeverity, List<Diagnostic>> pass) {
        DiagnosticSeverity severity = DRLLintHelper.severityFor(property, defaultValue);
        if (severity != null) {
            List<Diagnostic> found = new ArrayList<>();
            pass.accept(severity, found);
            out.addAll(found);
        }
    }

    private static Diagnostic diagnostic(String text, int start, int end, String message,
                                         DiagnosticSeverity severity, String code) {
        Diagnostic d = new Diagnostic(DRLLintHelper.rangeOf(text, start, end), message, severity, SOURCE);
        d.setCode(code);
        return d;
    }
}
