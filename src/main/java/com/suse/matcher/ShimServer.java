package com.suse.matcher;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Minimal HTTP shim that wraps the matcher JAR for use as a compose service.
 *
 * Endpoints:
 *   GET  /health  -  200 "ok"
 *   POST /match   -  accepts a JsonInput body, shells to a fresh JVM running
 *                    the matcher JAR, returns output.json on success or a JSON
 *                    error envelope on failure.
 *
 * A fresh JVM per request preserves the Drools.ID_MAP static-state isolation
 * invariant required for correct multi-tenant behaviour.
 *
 * Invoke: java -cp matcher.jar com.suse.matcher.ShimServer
 * The JAR path is resolved from the system property "matcher.jar"
 * (default: /matcher/matcher.jar, matching the Docker WORKDIR layout).
 */
public class ShimServer {

    private static final int    PORT = 8080;
    private static final String JAR  =
        System.getProperty("matcher.jar", "/matcher/matcher.jar");

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        server.createContext("/health", exchange -> {
            byte[] body = "ok\n".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        server.createContext("/match", ShimServer::handleMatch);

        server.setExecutor(null); // single-threaded - one blocking JVM call per request
        server.start();
        System.out.println("matcher-shim listening on :" + PORT + " (jar=" + JAR + ")");
    }

    private static void handleMatch(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        byte[] input  = exchange.getRequestBody().readAllBytes();
        Path   outDir = Files.createTempDirectory("matcher-");

        try {
            ProcessBuilder pb = new ProcessBuilder(
                "java", "-server", "-Xmx2G",
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "-jar", JAR, "-o", outDir.toString()
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            try (OutputStream stdin = proc.getOutputStream()) {
                stdin.write(input);
            }

            String procOutput = new String(proc.getInputStream().readAllBytes());
            int    exitCode   = proc.waitFor();

            Path outputJson = outDir.resolve("output.json");
            if (exitCode == 0 && Files.exists(outputJson)) {
                byte[] result = Files.readAllBytes(outputJson);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, result.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(result);
                }
            } else {
                jsonError(exchange, 500, exitCode, procOutput);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            jsonError(exchange, 500, -1, "interrupted");
        } finally {
            exchange.close();
            deleteDir(outDir);
        }
    }

    private static void jsonError(HttpExchange exchange, int status,
                                  int exitCode, String detail) throws IOException {
        String body = "{\"error\":\"matcher failed\","
            + "\"exit_code\":" + exitCode + ","
            + "\"detail\":" + jsonStr(detail.lines().limit(20)
                                          .reduce("", (a, b) -> a + "\n" + b).trim())
            + "}";
        byte[] bytes = body.getBytes();
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String jsonStr(String s) {
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "")
                       .replace("\t", "\\t") + "\"";
    }

    private static void deleteDir(Path dir) {
        try {
            Files.walk(dir)
                 .sorted(Comparator.reverseOrder())
                 .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }
}
