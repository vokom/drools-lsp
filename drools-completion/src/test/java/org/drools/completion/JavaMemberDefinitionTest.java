package org.drools.completion;

import java.nio.file.*;
import java.util.*;
import org.eclipse.lsp4j.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class JavaMemberDefinitionTest {
    @TempDir Path module;
    Path outputs;
    Path source(String name, String text) throws Exception {
        outputs = Files.createDirectories(module.resolve("target/classes/demo" )).getParent();
        Files.write(outputs.resolve("demo/" + name + ".class"), new byte[0]);
        Path java = module.resolve("src/main/java/demo/" + name + ".java");
        Files.createDirectories(java.getParent()); Files.writeString(java, text);
        return java;
    }
    List<Location> find(String drl, String word, String following) {
        int offset = drl.indexOf(word + following) + 1;
        assertThat(offset).isPositive();
        String prefix = drl.substring(0, offset);
        int line = (int) prefix.chars().filter(c -> c == '\n').count();
        int col = offset - drl.lastIndexOf('\n', offset - 1) - 1;
        return DRLDefinitionHelper.findDefinitions(module.resolve("test.drl").toUri().toString(), drl,
                new Position(line, col), ClassIndex.build(Set.of(outputs)), Set.of(outputs));
    }
    void assertName(Path file, Location location, String name) throws Exception {
        assertThat(Path.of(java.net.URI.create(location.getUri()))).isEqualTo(file);
        String line = Files.readAllLines(file).get(location.getRange().getStart().getLine());
        assertThat(line.substring(location.getRange().getStart().getCharacter(), location.getRange().getEnd().getCharacter())).isEqualTo(name);
    }
    @Test void implicitMethodReturnsExactJavaNameRangeWithCrLf() throws Exception {
        Path java = source("Passport", "package demo;\r\npublic class Passport {\r\n public boolean isExpired() { return false; }\r\n}\r\n");
        String drl = "import demo.Passport;\r\nrule Test when\r\n $passport: Passport( isExpired() )\r\nthen\r\nend\r\n";
        var result = find(drl, "isExpired", "()");
        assertThat(result).hasSize(1); assertName(java, result.get(0), "isExpired");
    }
    @Test void modifyBlockSetterResolvesOnModifiedBinding() throws Exception {
        Path passport = source("Passport", "package demo; public class Passport { public void setValidation(int value) {} }");
        String drl = "import demo.Passport;\nrule R when\n $p: Passport()\nthen\n modify($p) {\n  setValidation( 2 ),\n  setValidation( 3 )\n }\nend\n";
        var result = find(drl, "setValidation", "( 2 )"); assertThat(result).hasSize(1); assertName(passport, result.get(0), "setValidation");
        result = find(drl, "setValidation", "( 3 )"); assertThat(result).hasSize(1); assertName(passport, result.get(0), "setValidation");
    }
@Test void modifyBlockSetterResolvesOnOoPathBinding() throws Exception {
        source("PassportRuleUnit", "package demo; public class PassportRuleUnit { public DataStream<Passport> getPassports() {return null;} }");
        Path passport = source("Passport", "package demo; public class Passport { public void setValidation(int value) {} }");
        String drl = "package demo\nunit PassportRuleUnit;\nrule R when\n $p: /passports[ true ]\nthen\n modify($p) {\n  setValidation( 2 )\n }\nend\n";
        var result = find(drl, "setValidation", "( 2 )"); assertThat(result).hasSize(1); assertName(passport, result.get(0), "setValidation");
    }
@Test void bareRuleUnitFieldInConsequenceResolves() throws Exception {
        Path unit = source("PassportRuleUnit", "package demo; import java.util.List; public class PassportRuleUnit { private List<String> invalidPassports; public DataStream<Passport> getPassports() {return null;} }");
        source("Passport", "package demo; public class Passport {}");
        String drl = "package demo\nunit PassportRuleUnit;\nrule R when\n $p: /passports[ true ]\nthen\n invalidPassports.add( \"x\" );\nend\n";
        var result = find(drl, "invalidPassports", ".add"); assertThat(result).hasSize(1); assertName(unit, result.get(0), "invalidPassports");
    }
@Test void dataStoreMethodsInConsequenceResolveOnConsequenceDataStore() throws Exception {
        source("PassportRuleUnit", "package demo; import org.drools.ruleunits.api.DataStore; public class PassportRuleUnit { public DataStore<String> getVisas() {return null;} }");
        Path api = module.resolve("src/main/java/org/drools/ruleunits/api/DataStore.java");
        Path impl = module.resolve("src/main/java/org/drools/ruleunits/impl/datasources/ConsequenceDataStore.java");
        Files.createDirectories(api.getParent()); Files.createDirectories(impl.getParent());
        Files.writeString(api, "package org.drools.ruleunits.api; public interface DataStore<T> { void add(T o); }");
        Files.writeString(impl, "package org.drools.ruleunits.impl.datasources; public interface ConsequenceDataStore<T> { void add(T o); void addLogical(T o); }");
        Files.createDirectories(outputs.resolve("org/drools/ruleunits/api")); Files.createDirectories(outputs.resolve("org/drools/ruleunits/impl/datasources"));
        Files.write(outputs.resolve("org/drools/ruleunits/api/DataStore.class"), new byte[0]);
        Files.write(outputs.resolve("org/drools/ruleunits/impl/datasources/ConsequenceDataStore.class"), new byte[0]);
        String drl = "package demo\nunit PassportRuleUnit;\nimport org.drools.ruleunits.api.DataStore;\nrule R when then\n visas.addLogical( \"x\" );\nend\n";
        var result = find(drl, "addLogical", "(");
        assertThat(result).hasSize(1); assertName(impl, result.get(0), "addLogical");
    }
@Test void boundReceiverOnRhsAndRuleScope() throws Exception {
        Path passport = source("Passport", "package demo; public class Passport { public void setValidation(int value) {} }");
        Path other = source("Other", "package demo; public class Other { public void setValidation(int value) {} }");
        String drl = "import demo.Passport; import demo.Other;\nrule One when $p: Other() then $p.setValidation(1); end\nrule Two when $p: Passport() then $p.setValidation(2); end\n";
        var first = find(drl, "setValidation", "(1)"); assertThat(first).hasSize(1); assertName(other, first.get(0), "setValidation");
        var second = find(drl, "setValidation", "(2)"); assertThat(second).hasSize(1); assertName(passport, second.get(0), "setValidation");
    }
    @Test void overloadsRemainSeparateAndNestedDecoysAreExcluded() throws Exception {
        Path java = source("Passport", "package demo; public class Passport {\n // public void check() {}\n String decoy = \"check()\";\n public void check() {}\n public void check(int value) {}\n class Nested { public void check() {} }\n}");
        var result = find("import demo.Passport; rule R when Passport(check()) then end", "check", "()");
        assertThat(result).hasSize(2);
        for (var location : result) assertName(java, location, "check");
        assertThat(result.stream().map(l -> l.getRange().getStart().getLine()).toList()).containsExactly(3, 4);
    }
    @Test void inheritedMethodUsesParentSourceAndPropertiesUseAccessors() throws Exception {
        Path parent = source("Base", "package demo; public class Base { public boolean isExpired() {return false;} }");
        Path child = source("Passport", "package demo; public class Passport extends Base { public int getUnusedVisaPages() { return 1; } }");
        var inherited = find("import demo.Passport; rule R when Passport(isExpired()) then end", "isExpired", "()");
        assertThat(inherited).hasSize(1); assertName(parent, inherited.get(0), "isExpired");
        var property = find("import demo.Passport; rule R when Passport(unusedVisaPages > 0) then end", "unusedVisaPages", " >");
        assertThat(property).hasSize(1); assertName(child, property.get(0), "getUnusedVisaPages");
    }
    @Test void unknownReceiversCommentsStringsAndMissingMembersStayUnresolved() throws Exception {
        source("Passport", "package demo; public class Passport { public boolean isExpired() {return false;} }");
        for (String body : List.of("other.isExpired()", "\"isExpired()\" == \"x\"", "/* isExpired() */ true", "missing()")) {
            String drl = "import demo.Passport; rule R when Passport(" + body + ") then end";
            assertThat(find(drl, body.contains("missing") ? "missing" : "isExpired", "()")).isEmpty();
        }
    }
    @Test void inheritedInterfaceMethodAndEncodedSourceUri() throws Exception {
        module = Files.createDirectories(module.resolve("Module з пробілами"));
        Path contract = source("Contract", "package demo; public interface Contract { boolean isExpired(); }");
        source("Passport", "package demo; public abstract class Passport implements Contract {}");
        var result = find("import demo.Passport; rule R when Passport(isExpired()) then end", "isExpired", "()");
        assertThat(result).hasSize(1); assertName(contract, result.get(0), "isExpired");
        assertThat(result.get(0).getUri()).contains("%20").doesNotContain(" ");
    }
    @Test void classesWithoutSourceDoNotReturnInventedMethodTargets() throws Exception {
        Path java = source("Passport", "package demo; public class Passport { public boolean isExpired() {return false;} }");
        Files.delete(java);
        assertThat(find("import demo.Passport; rule R when Passport(isExpired()) then end", "isExpired", "()")).isEmpty();
    }
    @Test void enumConstantsResolveInPatternsAndConsequences() throws Exception {
        Path java = source("Validation", """
                package demo;
                public enum Validation {
                    UNKNOWN, PASSED(1) { public int code() { return 1; } }, FAILED;
                    Validation() {} Validation(int code) {}
                }
                """);
        source("Passport", "package demo; public class Passport {}");
        String drl = "import demo.Passport; import demo.Validation;\nrule R when $p: Passport(validation == Validation.UNKNOWN) then $p.setValidation(Validation.PASSED); end";
        for (String name : List.of("UNKNOWN", "PASSED")) {
            var result = find(drl, name, ")");
            assertThat(result).hasSize(1); assertName(java, result.get(0), name);
        }
        assertThat(find(drl.replace("UNKNOWN", "MISSING"), "MISSING", ")")).isEmpty();
    }
    @Test void interfaceConstantsAndInheritedConstantsResolve() throws Exception {
        Path java = source("Limits", "package demo; public interface Limits { int MIN = 0, MAX = 10; }");
        source("Passport", "package demo; public class Passport implements Limits {}");
        for (String type : List.of("Limits", "Passport")) {
            String drl = "import demo." + type + "; rule R when then int value = " + type + ".MAX; end";
            var result = find(drl, "MAX", ";");
            assertThat(result).hasSize(1); assertName(java, result.get(0), "MAX");
        }
    }
    @Test void classConstantsResolveAndEnumBodiesDoNotLeakFields() throws Exception {
        Path java = source("Limits", "package demo; public class Limits { public static final int MAX = 10; }");
        var result = find("import demo.Limits; rule R when then int value = Limits.MAX; end", "MAX", ";");
        assertThat(result).hasSize(1); assertName(java, result.get(0), "MAX");
        source("Validation", "package demo; public enum Validation { UNKNOWN { public final int DECOY = 1; }; }");
        assertThat(find("import demo.Validation; rule R when then int value = Validation.DECOY; end", "DECOY", ";")).isEmpty();
    }
    @Test void ooPathUsesInheritedRuleUnitDataSourceType() throws Exception {
        Path unit = source("BaseUnit", "package demo; public class BaseUnit { public DataStream<Passport> getPassports() {return null;} }");
        source("PassportRuleUnit", "package demo; public class PassportRuleUnit extends BaseUnit {}");
        Path passport = source("Passport", "package demo; public class Passport { public boolean isExpired() {return false;} public int getUnusedVisaPages() {return 1;} }");
        String drl = "package demo\r\nunit PassportRuleUnit;\r\nrule R when\r\n /passports[ !isExpired(), unusedVisaPages > 0 ]\r\nthen end";
        var property = find(drl, "unusedVisaPages", " >");
        assertThat(property).hasSize(1); assertName(passport, property.get(0), "getUnusedVisaPages");
        var method = find(drl, "isExpired", "()");
        assertThat(method).hasSize(1); assertName(passport, method.get(0), "isExpired");
        var root = find(drl, "passports", "[");
        assertThat(root).hasSize(1); assertName(unit, root.get(0), "getPassports");
    }
    @Test void ooPathResolvesPackageBeforeAmbiguousUnitNamesAndJavaImports() throws Exception {
        source("PassportRuleUnit", "package demo; public class PassportRuleUnit { public java.util.List<Passport> getPassports() {return null;} }");
        source("Passport", "package demo; public class Passport { public int getUnusedVisaPages() {return 1;} }");
        Path output = outputs;
        Path java = module.resolve("src/main/java/other/PassportRuleUnit.java");
        Files.createDirectories(java.getParent());
        Files.writeString(java, "package other; public class PassportRuleUnit {}");
        Files.createDirectories(output.resolve("other")); Files.write(output.resolve("other/PassportRuleUnit.class"), new byte[0]);
        var result = find("package demo; unit PassportRuleUnit; rule R when /passports[unusedVisaPages > 0] then end", "unusedVisaPages", " >");
        assertThat(result).hasSize(1); assertName(module.resolve("src/main/java/demo/Passport.java"), result.get(0), "getUnusedVisaPages");
    }
    @Test void ooPathNestedSegmentsUseTheirOwnElementType() throws Exception {
        source("PassportRuleUnit", "package demo; public class PassportRuleUnit { public java.util.List<Passport> passports; }");
        Path passport = source("Passport", "package demo; public class Passport { public java.util.List<Visa> getVisas() {return null;} public boolean isExpired() {return false;} }");
        Path visa = source("Visa", "package demo; public class Visa { public boolean isExpired() {return false;} }");
        String drl = "package demo; unit PassportRuleUnit; rule R when /passports[isExpired()]/visas[isExpired() == false] then end";
        var first = find(drl, "isExpired", "()]");
        assertThat(first).hasSize(1); assertName(passport, first.get(0), "isExpired");
        var second = find(drl, "isExpired", "() ==");
        assertThat(second).hasSize(1); assertName(visa, second.get(0), "isExpired");
    }
    @Test void ooPathExplicitThisAndOtherJavaReceiversKeepTheirTypes() throws Exception {
        source("PassportRuleUnit", "package demo; public class PassportRuleUnit { public Passport[] passports; }");
        Path passport = source("Passport", "package demo; public class Passport { public boolean isExpired() {return false;} }");
        Path validation = source("Validation", "package demo; public enum Validation { UNKNOWN }");
        String drl = "package demo; import demo.Validation; unit PassportRuleUnit; rule R when /passports[this.isExpired(), Validation.UNKNOWN != null] then end";
        // The unit declaration precedes imports in valid DRL.
        drl = drl.replace("import demo.Validation; unit PassportRuleUnit;", "unit PassportRuleUnit; import demo.Validation;");
        var method = find(drl, "isExpired", "()");
        assertThat(method).hasSize(1); assertName(passport, method.get(0), "isExpired");
        var constant = find(drl, "UNKNOWN", " !=");
        assertThat(constant).hasSize(1); assertName(validation, constant.get(0), "UNKNOWN");
    }
    @Test void ooPathUnknownRootsRawDataSourcesAndNonCodeStayUnresolved() throws Exception {
        source("PassportRuleUnit", "package demo; public class PassportRuleUnit { public DataStream getPassports() {return null;} }");
        source("Passport", "package demo; public class Passport { public boolean isExpired() {return false;} }");
        for (String path : List.of("/missing[isExpired()]", "/passports[isExpired()]", "/* /passports[isExpired()] */", "String( this == \"/passports[isExpired()]\" )")) {
            assertThat(find("package demo; unit PassportRuleUnit; rule R when " + path + " then end", "isExpired", "()")).isEmpty();
        }
    }
    @Test void unitDeclarationPrefersItsPackageOverDuplicateClassNames() throws Exception {
        Path unit = source("PassportRuleUnit", "package demo; public class PassportRuleUnit {}");
        Files.createDirectories(outputs.resolve("other"));
        Files.write(outputs.resolve("other/PassportRuleUnit.class"), new byte[0]);
        Path other = module.resolve("src/main/java/other/PassportRuleUnit.java");
        Files.createDirectories(other.getParent());
        Files.writeString(other, "package other; public class PassportRuleUnit {}");
        String drl = "package demo\r\nunit PassportRuleUnit;\r\nrule R when then end";
        var result = find(drl, "PassportRuleUnit", ";");
        assertThat(result).hasSize(1); assertName(unit, result.get(0), "PassportRuleUnit");
    }
    @Test void qualifiedAndImportedUnitDeclarationsResolve() throws Exception {
        Path unit = source("PassportRuleUnit", "package demo; public class PassportRuleUnit {}");
        for (String drl : List.of("package other; unit demo.PassportRuleUnit; rule R when then end",
                "package other; unit PassportRuleUnit; import demo.PassportRuleUnit; rule R when then end")) {
            var result = find(drl, "PassportRuleUnit", ";");
            assertThat(result).hasSize(1); assertName(unit, result.get(0), "PassportRuleUnit");
        }
    }
    @Test void unitDeclarationsDoNotResolveToDrlDeclaredTypes() throws Exception {
        Path unit = source("PassportRuleUnit", "package demo; public class PassportRuleUnit {}");
        String drl = "package demo; unit PassportRuleUnit; declare PassportRuleUnit value: int end rule R when then end";
        var result = find(drl, "PassportRuleUnit", ";");
        assertThat(result).hasSize(1); assertName(unit, result.get(0), "PassportRuleUnit");
        Files.delete(unit);
        assertThat(find(drl, "PassportRuleUnit", ";")).isEmpty();
    }
    @Test void unknownUnitAndUnitNamesInNonCodeRemainUnresolved() throws Exception {
        source("PassportRuleUnit", "package demo; public class PassportRuleUnit {}");
        for (String drl : List.of("package demo; unit MissingRuleUnit; rule R when then end",
                "package demo; // unit MissingRuleUnit;\nrule R when then end",
                "package demo; rule R when then String s = \"unit MissingRuleUnit;\"; end")) {
            assertThat(find(drl, "MissingRuleUnit", ";")).isEmpty();
        }
    }
    @Test void ooPathBoundFieldsUseReceiverRatherThanCurrentPattern() throws Exception {
        source("Unit", "package demo; public class Unit { public java.util.List<Passport> passports; public java.util.List<Visa> visas; }");
        Path passport = source("Passport", "package demo; public class Passport { public String getPassportNumber() {return null;} public Address address; }");
        Path visa = source("Visa", "package demo; public class Visa { public String passportNumber; }");
        Path address = source("Address", "package demo; public class Address { public String city; }");
        String drl = "package demo; unit Unit; rule R when $p: /passports $v: /visas[passportNumber == $p.passportNumber, $p.address.city == null] then String s = $p.passportNumber; end";
        var field = find(drl, "passportNumber", ", $p");
        assertThat(field).hasSize(1); assertName(passport, field.get(0), "getPassportNumber");
        var implicit = find(drl, "passportNumber", " ==");
        assertThat(implicit).hasSize(1); assertName(visa, implicit.get(0), "passportNumber");
        var nested = find(drl, "city", " ==");
        assertThat(nested).hasSize(1); assertName(address, nested.get(0), "city");
        var rhs = find(drl, "passportNumber", ";");
        assertThat(rhs).hasSize(1); assertName(passport, rhs.get(0), "getPassportNumber");
    }
    @Test void ooPathReceiverBindingsRespectRuleAndConditionalScope() throws Exception {
        source("Unit", "package demo; public class Unit { public Passport[] passports; public Visa[] visas; }");
        Path passport = source("Passport", "package demo; public class Passport { public int number; }");
        Path visa = source("Visa", "package demo; public class Visa { public int number; }");
        String drl = "package demo; unit Unit; rule A when $p: /passports then int n = $p.number; end rule B when $p: /visas then int n = $p.number + 1; end";
        var first = find(drl, "number", ";");
        assertThat(first).hasSize(1); assertName(passport, first.get(0), "number");
        var second = find(drl, "number", " +");
        assertThat(second).hasSize(1); assertName(visa, second.get(0), "number");
        for (String lhs : List.of("", "not($p: /passports)", "exists($p: /passports)")) {
            assertThat(find("package demo; unit Unit; rule R when " + lhs + " then int n = $p.number; end", "number", ";")).isEmpty();
        }
    }

    @Test void nestedOoPathBindingUsesTerminalTypeAndInheritedAccessors() throws Exception {
        source("Unit", "package demo; public class Unit { public java.util.List<Passport> passports; }");
        source("Passport", "package demo; public class Passport { public java.util.List<Visa> visas; public int number; }");
        source("Visa", "package demo; public class Visa extends Base {}");
        Path base = source("Base", "package demo; public class Base { public int getNumber() {return 1;} public boolean valid() {return true;} public int n; }");
        String drl = "package demo;\r\nunit Unit;\r\nrule R when visa: /passports/visas then int n = visa /* comment */ .number; boolean b = visa.valid(); int x = visa.n; end";
        var field = find(drl, "number", ";");
        assertThat(field).hasSize(1); assertName(base, field.get(0), "getNumber");
        var method = find(drl, "valid", "()");
        assertThat(method).hasSize(1); assertName(base, method.get(0), "valid");
        var single = find(drl, "n", ";");
        assertThat(single).hasSize(1); assertName(base, single.get(0), "n");
    }
    @Test void boundPropertyChainsDoNotIterateCollectionsOrInventUnknownMembers() throws Exception {
        source("Unit", "package demo; public class Unit { public Passport[] passports; }");
        source("Passport", "package demo; public class Passport { public java.util.List<Visa> visas; }");
        source("Visa", "package demo; public class Visa { public int number; }");
        for (String expression : List.of("$p.visas.number", "$p.missing.number", "$unknown.number")) {
            assertThat(find("package demo; unit Unit; rule R when $p: /passports then int n = " + expression + "; end", "number", ";")).isEmpty();
        }
        assertThat(find("package demo; unit Unit; rule R when $p: /passports[ $later.number > 0 ] $later: /passports then end", "number", " >")).isEmpty();
    }

    @Test void ooPathImplicitPropertyAndThisReceiversResolveMethods() throws Exception {
        source("Unit", "package demo; public class Unit { public Visa[] visas; }");
        source("Visa", "package demo; public class Visa { public Date getVisitEndDate() {return null;} }");
        Path date = source("Date", "package demo; public class Date { public Date plusMonths(int n) {return this;} public Date plusMonths(long n) {return this;} }");
        for (String receiver : List.of("visitEndDate", "this.visitEndDate")) {
            var result = find("package demo; unit Unit; rule R when /visas[" + receiver + ".plusMonths(6) != null] then end", "plusMonths", "(6)");
            assertThat(result).hasSize(2);
            for (var location : result) assertName(date, location, "plusMonths");
        }
    }
    @Test void ooPathDateMethodsResolveToJdkSources() throws Exception {
        source("Unit", "package demo; public class Unit { public Visa[] visas; }");
        source("Visa", "package demo; import java.time.LocalDate; public class Visa { public LocalDate getVisitEndDate() {return null;} }");
        String drl = "package demo; unit Unit; rule R when $v: /visas[visitEndDate.plusMonths(6) != null, $v.visitEndDate.isBefore(visitEndDate)] then end";
        for (String method : List.of("plusMonths", "isBefore")) {
            var result = find(drl, method, method.equals("plusMonths") ? "(6)" : "(visitEndDate)");
            assertThat(result).isNotEmpty();
            for (var location : result) assertName(Path.of(java.net.URI.create(location.getUri())), location, method);
            assertThat(result.get(0).getUri()).endsWith("/java/time/LocalDate.java");
        }
    }

    void archive(Path jar, String entry, String text) throws Exception {
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new java.util.zip.ZipEntry(entry));
            zip.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
    @Test void ruleUnitDataSourceMethodsNavigateToMatchingDependencySources() throws Exception {
        source("Unit", "package demo; import library.Stream; public class Unit { public Stream<Visa> getVisas() {return null;} }");
        source("Visa", "package demo; public class Visa {}");
        Path jar = module.resolve("stream-1.jar"), sources = module.resolve("stream-1-sources.jar");
        archive(jar, "library/Stream.class", "");
        archive(sources, "library/Stream.java", "package library; public interface Stream<T> { void append(T value); }");
        Set<Path> classpath = Set.of(outputs, jar);
        for (String receiver : List.of("visas", "this.visas")) {
            String drl = "package demo; unit Unit; rule R when then " + receiver + ".append(null); end";
            int offset = drl.indexOf("append") + 1;
            var result = DRLDefinitionHelper.findDefinitions(module.resolve("rule.drl").toUri().toString(), drl,
                    new Position(0, offset), ClassIndex.build(classpath), classpath);
            assertThat(result).hasSize(1);
            assertName(Path.of(java.net.URI.create(result.get(0).getUri())), result.get(0), "append");
        }
        String shadowed = "package demo; unit Unit; rule R when then Object visas = null; visas.append(null); end";
        assertThat(DRLDefinitionHelper.findDefinitions(module.resolve("rule.drl").toUri().toString(), shadowed,
                new Position(0, shadowed.indexOf("append") + 1), ClassIndex.build(classpath), classpath)).isEmpty();
        Files.delete(sources);
        assertThat(JavaSourceLocator.locateMembers("library.Stream", "append", true, classpath)).isEmpty();
    }
    @Test void dependencySourcesRequireExactResolvedArtifactAndBinaryClass() throws Exception {
        Path jar = module.resolve("stream-1.jar"), sources = module.resolve("stream-2-sources.jar");
        archive(jar, "library/Stream.class", "");
        archive(sources, "library/Stream.java", "package library; public interface Stream {void append();}");
        assertThat(DependencySourceArchive.locate("library.Stream", Set.of(jar))).isNull();
        Path matching = module.resolve("stream-1-sources.jar");
        Files.move(sources, matching);
        assertThat(DependencySourceArchive.locate("library.Stream", Set.of(jar))).isNotNull();
        archive(jar, "library/Other.class", "");
        assertThat(DependencySourceArchive.locate("library.Stream", Set.of(jar))).isNull();
        Files.writeString(jar, "broken zip");
        assertThat(DependencySourceArchive.locate("library.Stream", Set.of(jar))).isNull();
    }

}
