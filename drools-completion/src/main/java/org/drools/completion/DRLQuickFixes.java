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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;

/**
 * Text edits that fix the lint diagnostics a client sends back in a
 * {@code textDocument/codeAction} request. Each fix is computed from the
 * current document text and the diagnostic's range, code and data; a
 * diagnostic whose range no longer matches the text yields no fix.
 */
public final class DRLQuickFixes {

    /** One quick fix: a title, the edits for the document, and the diagnostic it resolves. */
    public record Fix(String title, List<TextEdit> edits, Diagnostic diagnostic, boolean preferred) {
    }

    private static final int MAX_IMPORT_CANDIDATES = 10;
    private static final Pattern IMPORT_OR_PACKAGE_LINE = Pattern.compile("(?m)^[ \\t]*(import|package)\\b[^\\n]*\\n?");
    /** What may follow an unused pattern binding: {@code : [pkg.]Type(} or {@code : /oopath}. */
    private static final Pattern PATTERN_BINDING_TAIL = Pattern.compile(
            "\\s*:\\s*(?=(?:[A-Za-z_$][\\w$]*\\s*\\.\\s*)*[A-Z][\\w$]*\\s*\\(|/)");

    private DRLQuickFixes() {
    }

    /** Fixes for {@code diagnostics} in {@code text}; {@code classIndex} supplies import candidates. */
    public static List<Fix> forDiagnostics(String text, List<Diagnostic> diagnostics, ClassIndex classIndex) {
        List<Fix> out = new ArrayList<>();
        if (text == null || diagnostics == null) {
            return out;
        }
        for (Diagnostic d : diagnostics) {
            if (d == null || d.getRange() == null || d.getCode() == null || d.getCode().getLeft() == null) {
                continue;
            }
            switch (d.getCode().getLeft()) {
                case "unknown-type" -> addImportFixes(text, d, classIndex, out);
                case DRLLintHelper.MISSING_IMPORT -> {
                    String fqcn = dataString(d.getData());
                    if (fqcn != null && fqcn.contains(".")) {
                        out.add(importFix(text, fqcn, d, true));
                    }
                }
                case DRLStructureLint.UNUSED_IMPORT -> removeLine(text, d, "Remove unused import", out);
                case DRLStructureLint.DUPLICATE_IMPORT -> removeLine(text, d, "Remove duplicate import", out);
                case DRLStructureLint.DUPLICATE_RULE_NAME -> renameRule(text, d, out);
                case DRLLintHelper.MISSING_SEMICOLON -> addSemicolon(text, d, out);
                case DRLUnusedBindings.CODE -> removeBinding(text, d, out);
                default -> {
                }
            }
        }
        return out;
    }

    /** "Import 'p.Type'" for each class-index type with the unknown simple name, inserted with the other imports. */
    private static void addImportFixes(String text, Diagnostic d, ClassIndex classIndex, List<Fix> out) {
        String name = textAt(text, d.getRange());
        if (classIndex == null || name == null || !name.matches("[A-Za-z_$][\\w$]*")) {
            return;
        }
        Set<String> candidates = new TreeSet<>(classIndex.forSimpleName(name));
        int count = 0;
        for (String fqcn : candidates) {
            if (count++ >= MAX_IMPORT_CANDIDATES) {
                break;
            }
            out.add(importFix(text, fqcn, d, candidates.size() == 1));
        }
    }

    private static Fix importFix(String text, String fqcn, Diagnostic d, boolean preferred) {
        return new Fix("Import '" + fqcn + "'", List.of(importEdit(text, fqcn)), d, preferred);
    }

    /**
     * The edit adding {@code import fqcn;} with the other imports. With an empty
     * {@code fqcn} the new text is only the leading newline a last line without one
     * needs, so callers can reuse the position for several candidates.
     */
    static TextEdit importEdit(String text, String fqcn) {
        int insertAt = importInsertion(text);
        Position at = position(text, insertAt);
        // A last import or package line without a newline (end of file) needs one before the new import.
        String lead = insertAt > 0 && text.charAt(insertAt - 1) != '\n' ? "\n" : "";
        return new TextEdit(new Range(at, at), fqcn.isEmpty() ? lead : lead + "import " + fqcn + ";\n");
    }

    /** A diagnostic's data as a string; clients may echo it back as a JSON primitive. */
    private static String dataString(Object data) {
        if (data instanceof String s) {
            return s;
        }
        if (data instanceof com.google.gson.JsonPrimitive primitive && primitive.isString()) {
            return primitive.getAsString();
        }
        return null;
    }

    /** Offset of the line after the last import, else after the package line, else the file start. */
    static int importInsertion(String text) {
        String sanitized = DRLLintHelper.sanitize(text);
        int afterImport = -1;
        int afterPackage = -1;
        Matcher m = IMPORT_OR_PACKAGE_LINE.matcher(sanitized);
        while (m.find()) {
            if ("import".equals(m.group(1))) {
                afterImport = m.end();
            } else if (afterPackage < 0) {
                afterPackage = m.end();
            }
        }
        return afterImport >= 0 ? afterImport : Math.max(afterPackage, 0);
    }

    /** Deletes the diagnostic's whole lines when nothing else is on them, else just its range. */
    private static void removeLine(String text, Diagnostic d, String title, List<Fix> out) {
        int start = offset(text, d.getRange().getStart());
        int end = offset(text, d.getRange().getEnd());
        if (start < 0 || end < start) {
            return;
        }
        int lineStart = text.lastIndexOf('\n', start - 1) + 1;
        int lineEnd = text.indexOf('\n', end);
        lineEnd = lineEnd < 0 ? text.length() : lineEnd + 1;
        boolean alone = text.substring(lineStart, start).isBlank() && text.substring(end, lineEnd).isBlank();
        Range range = alone
                ? new Range(position(text, lineStart), position(text, lineEnd))
                : d.getRange();
        out.add(new Fix(title, List.of(new TextEdit(range, "")), d, true));
    }

    /** Renames a duplicate rule to "name N", the first N from 2 that no rule in the file uses. */
    private static void renameRule(String text, Diagnostic d, List<Fix> out) {
        String written = textAt(text, d.getRange());
        if (written == null || written.isEmpty() || !written.equals(quotedOrBare(dataString(d.getData()), written))) {
            return; // the text moved since the diagnostic was computed
        }
        String name = written.length() >= 2 && (written.charAt(0) == '"' || written.charAt(0) == '\'')
                ? written.substring(1, written.length() - 1) : written;
        Set<String> taken = DRLStructureLint.ruleNames(text);
        int n = 2;
        while (taken.contains(name + " " + n)) {
            n++;
        }
        String renamed = name + " " + n;
        String quoted = "\"" + renamed.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        out.add(new Fix("Rename rule to '" + renamed + "'", List.of(new TextEdit(d.getRange(), quoted)), d, true));
    }

    /** {@code name} as written at {@code written}: quoted with the same quote, or bare. */
    private static String quotedOrBare(String name, String written) {
        if (name == null) {
            return written;
        }
        char q = written.charAt(0);
        return q == '"' || q == '\'' ? q + name + q : name;
    }

    /** Inserts ';' after the statement's last character, which the diagnostic's range covers. */
    private static void addSemicolon(String text, Diagnostic d, List<Fix> out) {
        int end = offset(text, d.getRange().getEnd());
        if (end <= 0 || end > text.length() || text.charAt(end - 1) == ';') {
            return;
        }
        Position at = d.getRange().getEnd();
        out.add(new Fix("Add missing ';'", List.of(new TextEdit(new Range(at, at), ";")), d, true));
    }

    /**
     * Removes an unused pattern binding: {@code $p : Person(...)} becomes {@code Person(...)}.
     * A field binding such as {@code Person( $n : name )} gets no fix, since
     * removing it would leave a bare property where a constraint is expected.
     */
    private static void removeBinding(String text, Diagnostic d, List<Fix> out) {
        int start = offset(text, d.getRange().getStart());
        int end = offset(text, d.getRange().getEnd());
        if (start < 0 || end <= start || text.charAt(start) != '$') {
            return;
        }
        Matcher tail = PATTERN_BINDING_TAIL.matcher(text).region(end, text.length());
        if (!tail.lookingAt()) {
            return;
        }
        Range range = new Range(d.getRange().getStart(), position(text, tail.end()));
        out.add(new Fix("Remove unused binding '" + text.substring(start, end) + "'",
                List.of(new TextEdit(range, "")), d, true));
    }

    // ── positions ───────────────────────────────────────────────────────

    private static String textAt(String text, Range range) {
        int start = offset(text, range.getStart());
        int end = offset(text, range.getEnd());
        return start < 0 || end < start ? null : text.substring(start, end);
    }

    /** UTF-16 offset of {@code p} in {@code text}, or -1 when it lies outside the text. */
    static int offset(String text, Position p) {
        if (p == null || p.getLine() < 0 || p.getCharacter() < 0) {
            return -1;
        }
        int lineStart = 0;
        for (int line = 0; line < p.getLine(); line++) {
            int nl = text.indexOf('\n', lineStart);
            if (nl < 0) {
                return -1;
            }
            lineStart = nl + 1;
        }
        int lineEnd = text.indexOf('\n', lineStart);
        lineEnd = lineEnd < 0 ? text.length() : lineEnd;
        int offset = lineStart + p.getCharacter();
        return offset <= lineEnd ? offset : -1;
    }

    private static Position position(String text, int offset) {
        return DRLLintHelper.rangeOf(text, offset, offset).getStart();
    }
}
