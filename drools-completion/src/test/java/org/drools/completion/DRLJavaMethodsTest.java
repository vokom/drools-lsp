package org.drools.completion;

import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.eclipse.lsp4j.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class DRLJavaMethodsTest {
    @TempDir Path root;
    Path classes;
    @BeforeEach void compile() throws Exception {
        classes = Files.createDirectories(root.resolve("target/classes"));
        Path java = root.resolve("Passport.java");
        Files.writeString(java, "package demo; public class Passport { public boolean isExpired() {return false;} public void setValidation(int x) {} }");
        assertThat(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",classes.toString(),java.toString())).isZero();
    }
    List<Diagnostic> diagnostics(String text) {
        try (var members = ClassMemberIndex.of(Set.of(classes))) {
            return DRLJavaMethods.diagnostics(text, ClassIndex.build(Set.of(classes)), members, root.resolve("test.drl"), Map.of());
        }
    }
    @Test void missingMethodHighlightsOnlyNameAndClearsAfterFix() {
        String text = "import demo.Passport;\r\nrule R when\r\n Passport(is2Expired())\r\nthen\r\nend";
        var diagnostics = diagnostics(text);
        assertThat(diagnostics).hasSize(1);
        var issue = diagnostics.get(0);
        assertThat(issue.getSeverity()).isEqualTo(DiagnosticSeverity.Error);
        assertThat(issue.getCode().getLeft()).isEqualTo("unknown-method");
        assertThat(issue.getRange()).isEqualTo(new Range(new Position(2,10),new Position(2,20)));
        assertThat(diagnostics(text.replace("is2Expired", "isExpired"))).isEmpty();
    }
    @Test void boundReceiverAndJdkFieldChainAreChecked() {
        String text = "import demo.Passport; rule R when $p: Passport() then $p.noSuchMethod(); System.out.noSuchMethod(); end";
        assertThat(diagnostics(text)).hasSize(2).allMatch(d -> d.getSeverity()==DiagnosticSeverity.Error);
        assertThat(diagnostics(text.replace("$p.noSuchMethod()", "$p.setValidation(1)").replace("System.out.noSuchMethod()", "System.out.println(\"x\")"))).isEmpty();
    }
    @Test void unknownClasspathCommentsStringsAndDynamicReceiverProduceNoErrors() {
        assertThat(diagnostics("import demo.Passport; rule R when Passport(isExpired()) then other.noSuchMethod(); System.out.println(\"noSuchMethod()\"); /* noSuchMethod() */ end")).isEmpty();
        assertThat(diagnostics("rule R when UnknownType(noSuchMethod()) then end")).isEmpty();
        assertThat(diagnostics("import demo.Passport; rule R when Passport(new String() != null) then end")).isEmpty();
    }
    @Test void ruleScopedBindingsAndDeclaredFunctionsDoNotProduceFalseErrors() {
        assertThat(diagnostics("import demo.Passport; function boolean custom() {return true;} rule R when Passport(custom()) then end")).isEmpty();
        assertThat(diagnostics("import demo.Passport; rule R when $p: Passport() then $p.isExpired(); end rule S when $p: UnknownType() then $p.notKnown(); end")).isEmpty();
    }
    @Test void modifyBlockSettersAreCheckedAgainstTheModifiedBinding() {
        String text = "import demo.Passport; rule R when $p: Passport() then modify($p) { setValidationWrongName( 1 ), setValidation( 2 ) } end";
        var diagnostics = diagnostics(text);
        assertThat(diagnostics).hasSize(1);
        assertThat(diagnostics.get(0).getMessage()).contains("setValidationWrongName");
        assertThat(diagnostics(text.replace("setValidationWrongName", "setValidation"))).isEmpty();
    }
    @Test void modifyBlockOoPathBindingIsCheckedWhenOutputsKnown() throws Exception {
        Path src = root.resolve("src/main/java/demo"); Files.createDirectories(src);
        Files.writeString(src.resolve("Passport.java"), "package demo; public class Passport { public void setValidation(int x) {} }");
        Files.writeString(src.resolve("PassportRuleUnit.java"), "package demo; public class PassportRuleUnit { public DataStream<Passport> getPassports() {return null;} }");
        Files.createDirectories(classes.resolve("demo"));
        Files.write(classes.resolve("demo/PassportRuleUnit.class"), new byte[0]);
        String text = "package demo\nunit PassportRuleUnit;\nrule R when $p: /passports[ true ] then modify($p) { setBad( 1 ) } end";
        try (var members = ClassMemberIndex.of(Set.of(classes))) {
            var found = DRLJavaMethods.diagnostics(text, ClassIndex.build(Set.of(classes)), members, root.resolve("test.drl"), Map.of(), Set.of(classes));
            assertThat(found).hasSize(1);
            assertThat(found.get(0).getMessage()).contains("setBad");
        }
    }
    @Test void misspelledBareUnitMemberInConsequenceIsFlagged() throws Exception {
        Path src = Files.createDirectories(root.resolve("src/main/java/demo"));
        Files.writeString(root.resolve("Unit.java"), "package demo; public class Unit { java.util.List<String> invalidPassports; }");
        Files.writeString(src.resolve("Unit.java"), "package demo; public class Unit { java.util.List<String> invalidPassports; }");
        assertThat(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",classes.toString(),root.resolve("Unit.java").toString())).isZero();
        String text = "package demo\nunit Unit;\nglobal java.util.List g;\nrule R when then\n invalidPassportz.add( 1 );\n invalidPassports.add( 2 );\n g.add( 3 ); drools.getRule(); java.util.List.of(); int n = 1; n.toString(); System.out.println(1);\nend";
        try (var members = ClassMemberIndex.of(Set.of(classes))) {
            var found = DRLJavaMethods.diagnostics(text, ClassIndex.build(Set.of(classes)), members, root.resolve("test.drl"), Map.of(), Set.of(classes));
            assertThat(found).hasSize(1);
            assertThat(found.get(0).getMessage()).contains("invalidPassportz");
            assertThat(found.get(0).getRange().getStart().getLine()).isEqualTo(4);
        }
    }
    @Test void jdkPrintlnDefinitionUsesExtractedSourceAndExactNameRanges() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(Path.of(System.getProperty("java.home"),"lib/src.zip")), "JDK source archive required");
        String text = "rule R when then\nSystem.out.println(\"hello\");\nend";
        var definitions = DRLDefinitionHelper.findDefinitions(root.resolve("test.drl").toUri().toString(), text,
                new Position(1,14), ClassIndex.empty(), Set.of());
        assertThat(definitions).isNotEmpty();
        for (var location : definitions) {
            Path source = Path.of(java.net.URI.create(location.getUri()));
            assertThat(source.getFileName().toString()).isEqualTo("PrintStream.java");
            String line = Files.readAllLines(source).get(location.getRange().getStart().getLine());
            assertThat(line.substring(location.getRange().getStart().getCharacter(),location.getRange().getEnd().getCharacter())).isEqualTo("println");
        }
    }
}
