package org.drools.lsp.server;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.tools.ToolProvider;
import org.eclipse.lsp4j.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class EarlyMemberCompletionTest {
    @TempDir Path root;
    @Test void memberCompletionDoesNotWaitForDependencyResolution() throws Exception {
        Path output = Files.createDirectories(root.resolve("target/classes"));
        Path source = root.resolve("Passport.java");
        Files.writeString(source, "package demo; public class Passport { public int getUnusedVisaPages() { return 1; } }");
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", output.toString(), source.toString())).isZero();
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        try (var server = new DroolsLspServer(code -> {}, path -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException e) { cancelled.countDown(); Thread.currentThread().interrupt(); }
            return Set.of(output);
        })) {
            var init = new InitializeParams(); init.setRootUri(root.toUri().toString());
            server.initialize(init).get(5, TimeUnit.SECONDS);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            String uri = root.resolve("test.drl").toUri().toString();
            server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "drools", 1,
                "import demo.Passport;\nrule Test when\n Passport( un )\nthen\nend\n")));
            var items = server.getTextDocumentService().getCompletionItems(new CompletionParams(new TextDocumentIdentifier(uri), new Position(2, 13)));
            assertThat(items.stream().map(CompletionItem::getLabel)).contains("unusedVisaPages");
            server.close();
            assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
