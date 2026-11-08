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

package org.drools.lsp.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.lsp4j.launch.LSPLauncher;

/** Persistent TCP listener. Each socket owns an independent LSP session. */
public class DroolsLspTCPLauncher {
    private static final Logger logger = Logger.getLogger(DroolsLspTCPLauncher.class.getName());

    public static void main(String[] args) throws IOException {
        try (ServerSocket listener = new ServerSocket(9925)) {
            serve(listener);
        }
    }

    /** Runs until the owning caller closes the listener. Also usable with an ephemeral port. */
    public static void serve(ServerSocket listener) throws IOException {
        var sessions = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "drools-lsp-session");
            t.setDaemon(true);
            return t;
        });
        var sockets = java.util.concurrent.ConcurrentHashMap.<Socket>newKeySet();
        try {
            while (!listener.isClosed()) {
                Socket socket;
                try {
                    socket = listener.accept();
                } catch (java.net.SocketException e) {
                    if (listener.isClosed()) break;
                    throw e;
                }
                sockets.add(socket);
                sessions.submit(() -> {
                    try (socket; DroolsLspServer server = new DroolsLspServer(code -> closeSocket(socket))) {
                        var launcher = LSPLauncher.createServerLauncher(server,
                                socket.getInputStream(), socket.getOutputStream());
                        server.connect(launcher.getRemoteProxy());
                        launcher.startListening().get();
                    } catch (Exception e) {
                        logger.log(Level.FINE, "TCP session ended", e);
                        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    } finally {
                        sockets.remove(socket);
                    }
                });
            }
        } finally {
            sockets.forEach(DroolsLspTCPLauncher::closeSocket);
            sessions.shutdownNow();
        }
    }

    private static void closeSocket(Socket socket) {
        try { socket.close(); } catch (IOException e) {
            logger.log(Level.FINE, "Failed to close TCP socket", e);
        }
    }
}
