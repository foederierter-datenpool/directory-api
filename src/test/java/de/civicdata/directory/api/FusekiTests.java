package de.civicdata.directory.api;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class FusekiTests {
    @TempDir
    Path work;

    @Test
    void servesLocalSnapshotWithReadOnlySparql() throws Exception {
        var jar = Path.of("build/fuseki/fuseki-server.jar").toAbsolutePath().toString();
        var config = Path.of("fuseki.ttl").toAbsolutePath().toString();
        Files.writeString(work.resolve("directory.ttl"), "<urn:service> <urn:name> \"Beratung\" .\n");

        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        var endpoint = "http://localhost:" + port + "/directory/sparql";
        var server = start("fuseki.log", "-cp", jar, "org.apache.jena.fuseki.main.cmds.FusekiMainCmd", "--config=" + config, "--port=" + port);
        try (var client = HttpClient.newHttpClient()) {
            var ask = HttpRequest.newBuilder(URI.create(endpoint + "?query=ASK%7B%7D"))
                    .timeout(Duration.ofSeconds(2)).build();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            boolean ready = false;
            while (server.isAlive() && System.nanoTime() < deadline) {
                try {
                    ready = client.send(ask, HttpResponse.BodyHandlers.ofString()).statusCode() == 200;
                    if (ready) break;
                } catch (IOException ignored) {
                    // The server is still starting.
                }
                Thread.sleep(100);
            }
            assertThat(ready).withFailMessage(Files.readString(work.resolve("fuseki.log"))).isTrue();

            var select = query(client, endpoint, "SELECT ?name WHERE { <urn:service> <urn:name> ?name }", "application/sparql-results+json");
            assertThat(select.statusCode()).isEqualTo(200);
            assertThat(select.body()).contains("Beratung");
            assertThat(select.headers().firstValue("access-control-allow-origin")).contains("https://example.org");
            var construct = query(client, endpoint, "CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }", "text/turtle");
            assertThat(construct.statusCode()).isEqualTo(200);
            assertThat(construct.headers().firstValue("content-type").orElse("")).startsWith("text/turtle");
            assertThat(construct.body()).contains("Beratung");

            var update = HttpRequest.newBuilder(URI.create(endpoint)).header("Content-Type", "application/sparql-update")
                    .POST(HttpRequest.BodyPublishers.ofString("DELETE WHERE { ?s ?p ?o }")).build();
            assertThat(client.send(update, HttpResponse.BodyHandlers.ofString()).statusCode()).isBetween(400, 499);
            assertThat(query(client, endpoint, "SELECT * WHERE {?s ?p ?o}", "application/sparql-results+json").body()).contains("Beratung");
            var service = query(client, endpoint, "SELECT * WHERE { SERVICE <http://localhost:1/sparql> {?s ?p ?o} }", "application/sparql-results+json");
            assertThat(service.statusCode()).isEqualTo(422);
            assertThat(service.body()).contains("SERVICE execution disabled");
        } finally {
            server.destroy();
            if (!server.waitFor(5, TimeUnit.SECONDS)) server.destroyForcibly().waitFor();
        }
    }

    private Process start(String log, String... arguments) throws IOException {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString()));
        command.addAll(List.of(arguments));
        return new ProcessBuilder(command).directory(work.toFile()).redirectErrorStream(true)
                .redirectOutput(work.resolve(log).toFile()).start();
    }

    private HttpResponse<String> query(HttpClient client, String endpoint, String query, String accept) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(endpoint + "?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)))
                .header("Accept", accept).header("Origin", "https://example.org").timeout(Duration.ofSeconds(5)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
