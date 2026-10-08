package com.nada;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = App.start(port);
        System.out.println("Listening on http://localhost:" + server.getAddress().getPort());
    }
}
