package org.drools.completion;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.antlr.v4.runtime.Token;
import org.drools.drl.parser.antlr4.DRL10Parser;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

/**
 * Go-to-definition for functions declared with {@code function Type name(...) { ... }}
 * in the current DRL file or in a sibling file of the same group.
 */
final class DRLFunctionDefinitions {

    private DRLFunctionDefinitions() {
    }

    /** Locations of every function named {@code word}; the current document first. */
    static List<Location> find(String uri, ParsedDrl parsed, String word,
                               Path documentPath, Map<Path, String> openFiles) {
        List<Location> result = new ArrayList<>(declarations(uri, parsed, word));
        if (result.isEmpty() && documentPath != null) {
            DRLWorkspaceTypeIndex.forEachSiblingFile(documentPath, openFiles, (siblingUri, siblingText) -> {
                if (result.isEmpty() && siblingText != null) {
                    result.addAll(declarations(siblingUri, ParsedDrl.of(siblingText), word));
                }
            });
        }
        return result;
    }

    private static List<Location> declarations(String uri, ParsedDrl parsed, String word) {
        List<Location> found = new ArrayList<>();
        if (parsed.compilationUnit == null) {
            return found;
        }
        for (DRL10Parser.DrlStatementdefContext statement : parsed.compilationUnit.drlStatementdef()) {
            DRL10Parser.FunctiondefContext function = statement.functiondef();
            if (function == null || function.drlIdentifier() == null) {
                continue;
            }
            Token name = function.drlIdentifier().getStart();
            if (name != null && word.equals(name.getText())) {
                int line = name.getLine() - 1;
                int column = name.getCharPositionInLine();
                found.add(new Location(uri, new Range(new Position(line, column),
                        new Position(line, column + word.length()))));
            }
        }
        return found;
    }

    /** Names of the functions declared in this document and its siblings, in that order. */
    static java.util.Set<String> names(ParsedDrl parsed, Path documentPath, Map<Path, String> openFiles) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>(namesIn(parsed));
        if (documentPath != null) {
            DRLWorkspaceTypeIndex.forEachSiblingFile(documentPath, openFiles, (siblingUri, siblingText) -> {
                if (siblingText != null) {
                    names.addAll(namesIn(ParsedDrl.of(siblingText)));
                }
            });
        }
        return names;
    }

    private static List<String> namesIn(ParsedDrl parsed) {
        List<String> names = new ArrayList<>();
        if (parsed.compilationUnit != null) {
            for (DRL10Parser.DrlStatementdefContext statement : parsed.compilationUnit.drlStatementdef()) {
                DRL10Parser.FunctiondefContext function = statement.functiondef();
                if (function != null && function.drlIdentifier() != null) {
                    names.add(function.drlIdentifier().getStart().getText());
                }
            }
        }
        return names;
    }
}
