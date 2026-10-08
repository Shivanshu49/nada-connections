package com.nada;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

public final class App {
    private App() {
    }

    /** Starts the API on the given port; pass 0 to let the OS pick a free one (handy in tests). */
    public static HttpServer start(int port) throws IOException {
        PersonStore store = new PersonStore();
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new ApiHandler(store));
        // The default executor handles one request at a time; a pool lets requests run in
        // parallel, which is why PersonStore is thread-safe.
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        return server;
    }
}
