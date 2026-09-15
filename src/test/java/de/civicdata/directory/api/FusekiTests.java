package de.civicdata.directory.api;

import java.io.IOException;
import de.civicdata.directory.fuseki.DirectoryFuseki;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FusekiTests {
    @TempDir
    Path work;

    @Test
    void servesLocalSnapshotWithReadOnlySparql() throws Exception {
        Files.createDirectories(work.resolve("data"));
        Files.createDirectories(work.resolve("config"));
        Files.writeString(work.resolve("data/directory.ttl"), "<urn:service> <urn:name> \"Beratung\" .\n");
        Files.writeString(work.resolve("data/provenance.ttl"), """
                @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
                @prefix prov: <http://www.w3.org/ns/prov#> .
                [] rdf:reifies <<( <urn:service> <urn:name> "Beratung" )>> ;
                   prov:wasDerivedFrom <urn:source-record> .
                """);

        Files.writeString(work.resolve("config/federation.ttl"), "<urn:config> <urn:name> \"Configuration\" .\n");
        Files.createDirectories(work.resolve("data/ingest"));
        Files.writeString(work.resolve("data/ingest/ingest-log.ttl"), "<urn:ingest> <urn:name> \"Journal\" .");
        for (String excluded : List.of("data/ingest/raw", "data/ingest/lifted", "data/pipeline/extracted",
                "data/pipeline/preparation", "data/.hidden", "node_modules/example")) {
            Files.createDirectories(work.resolve(excluded));
            Files.writeString(work.resolve(excluded).resolve("ignored.ttl"), "Invalid Turtle: must not be loaded");
        }

        Files.writeString(work.resolve("data/name with space.ttl"), "<urn:space> <urn:name> \"Space\" .");
        Files.createSymbolicLink(work.resolve("data/ignored.ttl"), work.resolve("data/.hidden/ignored.ttl"));
        Files.createSymbolicLink(work.resolve("data/linked"), work.resolve("data/.hidden"));
        var server = DirectoryFuseki.serve(work, 0).start();
        var endpoint = "http://localhost:" + server.getPort() + "/directory/sparql";
        try (var client = HttpClient.newHttpClient()) {
            var select = query(client, endpoint, "SELECT ?name WHERE { <urn:service> <urn:name> ?name }", "application/sparql-results+json");
            assertThat(select.statusCode()).isEqualTo(200);
            assertThat(select.body()).contains("Beratung");
            var provenance = query(client, endpoint, """
                    PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
                    PREFIX prov: <http://www.w3.org/ns/prov#>
                    SELECT ?source WHERE {
                      <urn:service> <urn:name> ?value .
                      GRAPH <urn:directory:data/provenance.ttl> {
                        ?annotation rdf:reifies <<( <urn:service> <urn:name> ?value )>> ;
                                    prov:wasDerivedFrom ?source .
                      }
                    }
                    """, "application/sparql-results+json");
            assertThat(provenance.statusCode()).isEqualTo(200);
            assertThat(provenance.body()).contains("urn:source-record");
            assertThat(select.headers().firstValue("access-control-allow-origin")).contains("https://example.org");
            var construct = query(client, endpoint, "CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }", "text/turtle");
            assertThat(construct.statusCode()).isEqualTo(200);
            assertThat(construct.headers().firstValue("content-type").orElse("")).startsWith("text/turtle");
            assertThat(construct.body()).contains("Beratung");
            assertThat(construct.body()).doesNotContain("source-record", "reifies");
            var graphs = query(client, endpoint, "SELECT DISTINCT ?g WHERE { GRAPH ?g { ?s ?p ?o } }", "application/sparql-results+json");
            assertThat(graphs.body()).contains("urn:directory:config/federation.ttl", "urn:directory:data/provenance.ttl",
                    "urn:directory:data/ingest/ingest-log.ttl", "urn:directory:data/name%20with%20space.ttl");
            assertThat(graphs.body()).doesNotContain("directory.ttl", "ignored.ttl");

            var update = HttpRequest.newBuilder(URI.create(endpoint)).header("Content-Type", "application/sparql-update")
                    .POST(HttpRequest.BodyPublishers.ofString("DELETE WHERE { ?s ?p ?o }")).build();
            assertThat(client.send(update, HttpResponse.BodyHandlers.ofString()).statusCode()).isBetween(400, 499);
            assertThat(query(client, endpoint, "SELECT * WHERE {?s ?p ?o}", "application/sparql-results+json").body()).contains("Beratung");
            var service = query(client, endpoint, "SELECT * WHERE { SERVICE <http://localhost:1/sparql> {?s ?p ?o} }", "application/sparql-results+json");
            assertThat(service.statusCode()).isEqualTo(422);
            assertThat(service.body()).contains("SERVICE execution disabled");
        } finally {
            server.stop();
        }
    }

    @Test
    void rejectsMissingOrEmptyRequiredFilesAndInvalidTurtle() throws Exception {
        assertThatThrownBy(() -> DirectoryFuseki.serve(work, 0))
                .isInstanceOf(IOException.class).hasMessageContaining("Missing or empty data/directory.ttl");
        Files.createDirectories(work.resolve("data"));
        Files.createDirectories(work.resolve("config"));
        Files.writeString(work.resolve("data/directory.ttl"), "");
        assertThatThrownBy(() -> DirectoryFuseki.serve(work, 0))
                .isInstanceOf(IOException.class).hasMessageContaining("Missing or empty data/directory.ttl");
        Files.writeString(work.resolve("data/directory.ttl"), "<urn:s> <urn:p> <urn:o> .");
        assertThatThrownBy(() -> DirectoryFuseki.serve(work, 0))
                .isInstanceOf(IOException.class).hasMessageContaining("Missing or empty config/federation.ttl");
        Files.writeString(work.resolve("config/federation.ttl"), "# configuration");
        Files.writeString(work.resolve("data/broken.ttl"), "Invalid Turtle");
        assertThatThrownBy(() -> DirectoryFuseki.serve(work, 0))
                .isInstanceOf(org.apache.jena.riot.RiotException.class);
    }

    private HttpResponse<String> query(HttpClient client, String endpoint, String query, String accept) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(endpoint + "?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)))
                .header("Accept", accept).header("Origin", "https://example.org").timeout(Duration.ofSeconds(5)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
