package org.drools.completion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drl.parser.antlr4.JavaLexer;
import org.drools.drl.parser.antlr4.JavaParser;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;

/**
 * Maps a classpath FQCN to its project Maven source file, when present, along
 * with the declaration's range and kind. Shared by go-to-definition and type
 * hierarchy: when the resolved classpath contains
 * {@code <module>/target/classes/pkg/Type.class}, the source is
 * {@code <module>/src/main/java/pkg/Type.java} if it exists.
 *
 * <p>JAR classes (and anything without project source) yield {@code null} — i.e.
 * no navigable location.
 */
final class JavaSourceLocator {

    private static final Logger logger = Logger.getLogger(JavaSourceLocator.class.getName());

    /** {@code class|interface|enum|record <Simple>}; %s is the quoted simple name. */
    private static final String TYPE_DECL_TEMPLATE = "\\b(class|interface|enum|record)\\s+(%s)\\b";

    private JavaSourceLocator() {
    }

    /** A resolved Java source: its {@link Location} plus the declaration's symbol kind. */
    static final class Result {
        final Location location;
        final SymbolKind kind;

        Result(Location location, SymbolKind kind) {
            this.location = location;
            this.kind = kind;
        }
    }

    /**
     * Resolves {@code fqcn} to its project Maven source, or {@code null} when no
     * source is found (JAR-only classes, or no build-output directories).
     */
    static Result locate(String fqcn, Set<Path> buildOutputDirs) {
        if (fqcn == null || fqcn.isEmpty()) {
            return null;
        }
        String relClass = fqcn.replace('.', '/') + ".class";
        String relJava = fqcn.replace('.', '/') + ".java";
        String simpleName = fqcn.substring(fqcn.lastIndexOf('.') + 1);

        for (Path outputDir : buildOutputDirs == null ? Set.<Path>of() : buildOutputDirs) {
            if (!Files.isRegularFile(outputDir.resolve(relClass))) {
                continue;
            }
            // <module>/target/classes → <module>
            Path target = outputDir.getParent();
            Path module = target == null ? null : target.getParent();
            if (module == null) {
                continue;
            }
            Path javaFile = module.resolve("src/main/java").resolve(relJava);
            if (!Files.isRegularFile(javaFile)) {
                continue;
            }
            return readDeclaration(javaFile, simpleName);
        }
        return null;
    }

    /** Project sources, matching resolved dependency sources, then the server JDK archive. */
    static Result locateWithJdk(String fqcn, Set<Path> outputs) {
        Result projectSource = locate(fqcn, outputs);
        if (projectSource != null) return projectSource;
        Path dependencySource = DependencySourceArchive.locate(fqcn, outputs);
        if (dependencySource != null) return readDeclaration(dependencySource, fqcn.substring(fqcn.lastIndexOf('.') + 1));
        Path jdkSource = fqcn == null ? null : JdkSourceArchive.locate(fqcn);
        return jdkSource == null ? null : readDeclaration(jdkSource, fqcn.substring(fqcn.lastIndexOf('.')+1));
    }

    private static Result readDeclaration(Path javaFile, String simpleName) {
        Pattern decl = Pattern.compile(String.format(TYPE_DECL_TEMPLATE, Pattern.quote(simpleName)));
        String uri = javaFile.toUri().toString();
        try {
            List<String> lines = Files.readAllLines(javaFile);
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = decl.matcher(lines.get(i));
                if (m.find()) {
                    Range range = new Range(new Position(i, m.start(2)),
                                            new Position(i, m.start(2) + simpleName.length()));
                    return new Result(new Location(uri, range), kindOf(m.group(1)));
                }
            }
        } catch (Exception e) {
            logger.fine(() -> "Could not locate declaration in " + javaFile + ": " + e.getMessage());
        }
        // Source exists but the declaration line wasn't found — anchor at the top.
        return new Result(new Location(uri, new Range(new Position(0, 0), new Position(0, 0))),
                          SymbolKind.Class);
    }

    private static SymbolKind kindOf(String keyword) {
        switch (keyword) {
            case "interface":
                return SymbolKind.Interface;
            case "enum":
                return SymbolKind.Enum;
            default:
                return SymbolKind.Class;  // class, record
        }
    }

    /** Source-backed Java members, including inherited project members. Overloads stay separate. */
    static List<Location> locateMembers(String fqcn, String name, boolean methodCall, Set<Path> outputs) {
        return locateMembers(fqcn, name, methodCall, outputs, new HashSet<>());
    }

    private static List<Location> locateMembers(String fqcn, String name, boolean methodCall,
                                               Set<Path> outputs, Set<String> visited) {
        if (!visited.add(fqcn)) return List.of();
        Result source = locateWithJdk(fqcn, outputs);
        if (source == null) return List.of();
        try {
            Path file = Path.of(java.net.URI.create(source.location.getUri()));
            var lexer = new JavaLexer(
                    CharStreams.fromString(Files.readString(file)));
            lexer.removeErrorListeners();
            var parser = new JavaParser(new CommonTokenStream(lexer));
            parser.removeErrorListeners();
            var unit = parser.compilationUnit();
            String simpleName = fqcn.substring(fqcn.lastIndexOf('.') + 1);
            ParseTree owner = null;
            for (var declaration : unit.typeDeclaration()) {
                if (declaration.classDeclaration() != null && simpleName.equals(declaration.classDeclaration().identifier().getText())) owner = declaration.classDeclaration();
                if (declaration.interfaceDeclaration() != null && simpleName.equals(declaration.interfaceDeclaration().identifier().getText())) owner = declaration.interfaceDeclaration();
                if (declaration.enumDeclaration() != null && simpleName.equals(declaration.enumDeclaration().identifier().getText())) owner = declaration.enumDeclaration();
                if (declaration.recordDeclaration() != null && simpleName.equals(declaration.recordDeclaration().identifier().getText())) owner = declaration.recordDeclaration();
            }
            if (owner == null) return List.of();
            List<Location> exact = new ArrayList<>();
            List<Location> accessors = new ArrayList<>();
            List<Location> fields = new ArrayList<>();
            collectMembers(owner, owner, name, methodCall, file.toUri().toASCIIString(), exact, accessors, fields);
            if (!exact.isEmpty()) return exact;
            if (!accessors.isEmpty()) return accessors;
            if (!fields.isEmpty()) return fields;
            List<JavaParser.TypeTypeContext> parents = new ArrayList<>();
            if (owner instanceof JavaParser.ClassDeclarationContext c) {
                if (c.typeType() != null) parents.add(c.typeType());
                // Exclude permits lists: only the first typeList following 'implements' is a supertype list.
                if (c.IMPLEMENTS() != null && !c.typeList().isEmpty()) parents.addAll(c.typeList(0).typeType());
            } else if (owner instanceof JavaParser.InterfaceDeclarationContext i) {
                if (i.EXTENDS() != null && !i.typeList().isEmpty()) parents.addAll(i.typeList(0).typeType());
            }
            LinkedHashSet<Location> inherited = new LinkedHashSet<>();
            for (var parent : parents) {
                String type = parent.getText().replaceAll("<.*>", "");
                for (String candidate : sourceTypeCandidates(type, unit)) {
                    if (locateWithJdk(candidate, outputs) != null) {
                        inherited.addAll(locateMembers(candidate, name, methodCall, outputs, visited));
                        break;
                    }
                }
            }
            return List.copyOf(inherited);
        } catch (Exception e) {
            logger.fine(() -> "Could not locate Java member " + fqcn + "." + name + ": " + e.getMessage());
            return List.of();
        }
    }

    static List<String> sourceTypeCandidates(String type,
            JavaParser.CompilationUnitContext unit) {
        List<String> names = new ArrayList<>();
        if (type.contains(".")) names.add(type);
        for (var imported : unit.importDeclaration()) {
            if (imported.STATIC() != null) continue;
            String value = imported.qualifiedName().getText();
            if (imported.MUL() != null) names.add(value + "." + type);
            else if (value.endsWith("." + type)) names.add(value);
        }
        if (unit.packageDeclaration() != null) names.add(unit.packageDeclaration().qualifiedName().getText() + "." + type);
        names.add(type);
        return names;
    }

    private static void collectMembers(ParseTree node,
            ParseTree owner, String name, boolean methodCall, String uri,
            List<Location> exact, List<Location> accessors, List<Location> fields) {
        // Do not confuse nested/local classes or anonymous-class bodies with the receiver's members.
        if (node != owner && (node instanceof JavaParser.ClassDeclarationContext
                || node instanceof JavaParser.InterfaceDeclarationContext
                || node instanceof JavaParser.EnumDeclarationContext
                || node instanceof JavaParser.RecordDeclarationContext
                || node instanceof JavaParser.BlockContext)) return;
        Token methodName = null;
        JavaParser.FormalParametersContext parameters = null;
        if (node instanceof JavaParser.MethodDeclarationContext m) {
            methodName = m.identifier().getStart(); parameters = m.formalParameters();
        } else if (node instanceof JavaParser.InterfaceCommonBodyDeclarationContext m) {
            methodName = m.identifier().getStart(); parameters = m.formalParameters();
        }
        if (methodName != null) {
            if (methodCall && methodName.getText().equals(name)) exact.add(tokenLocation(uri, methodName));
            String suffix = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            if (!methodCall && parameters.formalParameterList() == null
                    && (methodName.getText().equals("get" + suffix) || methodName.getText().equals("is" + suffix))) {
                accessors.add(tokenLocation(uri, methodName));
            }
            return;
        }
        if (node instanceof JavaParser.EnumConstantContext constant) {
            if (!methodCall && constant.identifier().getText().equals(name)) {
                fields.add(tokenLocation(uri, constant.identifier().getStart()));
            }
            // Arguments and constant-specific class bodies are not members of the enum.
            return;
        }
        if (node instanceof JavaParser.ConstDeclarationContext declaration) {
            if (!methodCall) for (var constant : declaration.constantDeclarator()) {
                Token token = constant.identifier().getStart();
                if (token.getText().equals(name)) fields.add(tokenLocation(uri, token));
            }
            return;
        }
        if (node instanceof JavaParser.FieldDeclarationContext f) {
            if (!methodCall) for (var variable : f.variableDeclarators().variableDeclarator()) {
                var token = variable.variableDeclaratorId().identifier().getStart();
                if (token.getText().equals(name)) fields.add(tokenLocation(uri, token));
            }
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) collectMembers(node.getChild(i), owner, name, methodCall, uri, exact, accessors, fields);
    }

    private static Location tokenLocation(String uri, Token token) {
        return new Location(uri, new Range(new Position(token.getLine() - 1, token.getCharPositionInLine()),
                new Position(token.getLine() - 1, token.getCharPositionInLine() + token.getText().length())));
    }
}
