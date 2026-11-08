package org.drools.lsp.server;

import java.net.*;
import java.nio.charset.StandardCharsets;
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

class SessionCleanupTest {
    @TempDir Path root;
    @Test void closeCancelsInitializationAndRebuildAndPreservesOtherIndex() throws Exception {
        try (var first = new DroolsLspServer(code -> {}); var second = new DroolsLspServer(code -> {})) {
            Path classes = Files.createDirectories(root.resolve("target/classes/example"));
            Files.write(classes.resolve("Keep.class"), new byte[0]);
            second.setClasspathEntriesForTest(Set.of(classes.getParent()));
            second.rebuildClassIndex();
            first.getWorkspaceService().didChangeWatchedFiles(new DidChangeWatchedFilesParams());
            var params = new InitializeParams(); params.setRootUri(root.toUri().toString());
            first.initialize(params).get();
            first.close(); first.close(); first.rebuildClassIndex();
            first.getWorkspaceService().didChangeWatchedFiles(new DidChangeWatchedFilesParams());
            assertThat(first.isClosed()).isTrue();
            assertThat(first.getClasspathEntries()).isEmpty();
            assertThat(first.getTextDocumentService().getClassIndexForTest().size()).isZero();
            assertThat(second.getTextDocumentService().getClassIndexForTest().getMatching("Keep")).contains("example.Keep");
        }
    }
    @Test void exitHandlerReceivesLspStatusAndNoArgStdioConstructorRemains() {
        AtomicInteger status = new AtomicInteger(-1);
        try (var normal = new DroolsLspServer(status::set)) {
            normal.shutdown(); normal.exit(); assertThat(status.get()).isZero();
        }
        try (var abnormal = new DroolsLspServer(status::set)) {
            abnormal.exit(); assertThat(status.get()).isEqualTo(1);
        }
        try (var stdio = new DroolsLspServer()) { assertThat(stdio.isClosed()).isFalse(); }
    }
    @Test void stdioProcessPreservesNormalAndAbnormalExitCodes() throws Exception {
        for (boolean shutdown : new boolean[] {true, false}) {
            Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Main.class.getName())
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try {
                java.io.OutputStream out = process.getOutputStream();
                if (shutdown) {
                    byte[] request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"shutdown\"}".getBytes(StandardCharsets.UTF_8);
                    out.write(("Content-Length: " + request.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(request); out.flush();
                    // Read the shutdown response before issuing exit: it must have completed cleanup.
                    var input = new java.io.BufferedInputStream(process.getInputStream());
                    int state = 0;
                    StringBuilder header = new StringBuilder();
                    while (state < 4) {
                        int ch = input.read();
                        if (ch < 0) throw new java.io.EOFException();
                        header.append((char) ch);
                        char expected = "\r\n\r\n".charAt(state);
                        state = ch == expected ? state + 1 : (ch == '\r' ? 1 : 0);
                    }
                    int length = Integer.parseInt(header.toString().split(":")[1].trim());
                    assertThat(input.readNBytes(length)).hasSize(length);
                }
                byte[] exit = "{\"jsonrpc\":\"2.0\",\"method\":\"exit\"}".getBytes(StandardCharsets.UTF_8);
                out.write(("Content-Length: " + exit.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(exit); out.flush();
                assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
                assertThat(process.exitValue()).isEqualTo(shutdown ? 0 : 1);
            } finally { process.destroyForcibly(); }
        }
    }
}
