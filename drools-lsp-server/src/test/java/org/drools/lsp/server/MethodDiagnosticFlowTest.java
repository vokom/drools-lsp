package org.drools.lsp.server;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.eclipse.lsp4j.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
class MethodDiagnosticFlowTest {
 @TempDir Path root;
 @Test void editsClearMethodDiagnosticThroughDocumentService() throws Exception {
  Path output=Files.createDirectories(root.resolve("target/classes"));
  Path java=root.resolve("Passport.java");
  Files.writeString(java,"package demo; public class Passport { public boolean isExpired() {return false;} }");
  assertThat(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",output.toString(),java.toString())).isZero();
  try(var server=new DroolsLspServer(code -> {})) {
   server.setClasspathEntriesForTest(Set.of(output));server.rebuildClassIndex();
   String uri=root.resolve("test.drl").toUri().toString();
   String invalid="import demo.Passport; rule R when Passport(is2Expired()) then end";
   var docs=server.getTextDocumentService();
   docs.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri,"drools",1,invalid)));
   assertThat(docs.validate(uri)).anyMatch(d -> "drools-methods".equals(d.getSource()) && d.getSeverity()==DiagnosticSeverity.Error);
   docs.didChange(new DidChangeTextDocumentParams(new VersionedTextDocumentIdentifier(uri,2),List.of(new TextDocumentContentChangeEvent(invalid.replace("is2Expired","isExpired")))));
   assertThat(docs.validate(uri)).noneMatch(d -> "drools-methods".equals(d.getSource()));
  }
 }
}
