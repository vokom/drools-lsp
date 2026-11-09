package org.drools.completion;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DiagnosticTag;

/**
 * Hint diagnostics for bindings that are declared but never used, tagged
 * {@link DiagnosticTag#Unnecessary} so clients render them greyed out, as
 * unused locals are in Java. Controlled by {@code drools.lsp.lint.unusedBindings}
 * ({@code off | hint | info | warning | error}, default {@code hint}).
 */
public final class DRLUnusedBindings {

    static final String PROP = "drools.lsp.lint.unusedBindings";
    public static final String CODE = "unused-binding";

    private DRLUnusedBindings() {
    }

    public static List<Diagnostic> diagnostics(String text) {
        List<Diagnostic> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        DiagnosticSeverity severity = DRLLintHelper.severityFor(PROP, "hint");
        if (severity == null) {
            return out;
        }
        for (var range : DRLReferenceScanner.unusedBindings(ParsedDrl.of(text))) {
            Diagnostic d = new Diagnostic(range, "Binding is never used", severity, "drools-lint");
            d.setCode(CODE);
            d.setTags(List.of(DiagnosticTag.Unnecessary));
            out.add(d);
        }
        return out;
    }
}
