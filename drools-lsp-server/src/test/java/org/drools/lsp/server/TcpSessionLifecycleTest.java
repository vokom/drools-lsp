package org.drools.lsp.server;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class TcpSessionLifecycleTest {
    @TempDir Path root;
    ServerSocket listener;
    ExecutorService executor;
    Future<?> acceptLoop;

    @BeforeEach void start() throws Exception {
        listener = new ServerSocket(0, 20, InetAddress.getLoopbackAddress());
        executor = Executors.newSingleThreadExecutor();
        acceptLoop = executor.submit(() -> { DroolsLspTCPLauncher.serve(listener); return null; });
    }
    @AfterEach void stop() throws Exception {
        listener.close();
        acceptLoop.get(5, TimeUnit.SECONDS);
        executor.shutdownNow();
    }
    class Client implements AutoCloseable {
        final Socket socket;
        final LanguageServer server;
        final Future<?> listening;
        Client(Path workspace) throws Exception {
            socket = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
            LanguageClient client = new LanguageClient() {
                public void telemetryEvent(Object value) {}
                public void publishDiagnostics(PublishDiagnosticsParams params) {}
                public void showMessage(MessageParams params) {}
                public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) { return CompletableFuture.completedFuture(null); }
                public void logMessage(MessageParams params) {}
            };
            var launcher = LSPLauncher.createClientLauncher(client, socket.getInputStream(), socket.getOutputStream());
            server = launcher.getRemoteProxy();
            listening = launcher.startListening();
            var params = new InitializeParams();
            params.setRootUri(workspace.toUri().toString());
            params.setCapabilities(new ClientCapabilities());
            assertThat(server.initialize(params).get(5, TimeUnit.SECONDS).getCapabilities().getCompletionProvider()).isNotNull();
            server.initialized(new InitializedParams());
        }
        void open(String name) {
            server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem("file:///shared.drl", "drools", 1, "rule \"" + name + "\"\nwhen\nthen\nend\n")));
        }
        List<String> names() throws Exception {
            return server.getTextDocumentService().documentSymbol(new DocumentSymbolParams(new TextDocumentIdentifier("file:///shared.drl")))
                .get(5, TimeUnit.SECONDS).stream().map(e -> e.getRight().getName()).toList();
        }
        public void close() throws Exception { socket.close(); listening.cancel(true); }
    }
    @Test void sequentialReconnectsLeaveListenerAlive() throws Exception {
        for (int i = 0; i < 3; i++) {
            try (var client = new Client(root)) {
                client.open("session" + i);
                assertThat(client.names()).contains("session" + i);
                client.server.shutdown().get(5, TimeUnit.SECONDS);
                client.server.exit();
            }
        }
        assertThat(acceptLoop.isDone()).isFalse();
    }
    @Test void simultaneousBuffersAndFailureIsolation() throws Exception {
        try (var first = new Client(Files.createDirectory(root.resolve("one")));
             var second = new Client(Files.createDirectory(root.resolve("two")))) {
            first.open("first"); second.open("second");
            assertThat(first.names()).contains("first").doesNotContain("second");
            assertThat(second.names()).contains("second").doesNotContain("first");
            first.server.shutdown().get(5, TimeUnit.SECONDS);
            first.server.exit();
            assertThat(second.names()).contains("second");
            try (Socket malformed = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort())) {
                malformed.getOutputStream().write("Content-Length: 1\r\n\r\n{".getBytes(StandardCharsets.UTF_8));
            }
            try (var abrupt = new Client(root)) { abrupt.open("abrupt"); }
            assertThat(second.names()).contains("second");
        }
    }
}
