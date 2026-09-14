package de.civicdata.directory.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CollectionsTests {
    @TempDir static Path work;
    private static Process fuseki;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @LocalServerPort int port;

    @DynamicPropertySource
    static void startFuseki(DynamicPropertyRegistry properties) throws Exception {
        Files.writeString(work.resolve("federation.ttl"), """
                @prefix cdp: <https://civic-data.de/pipeline#> .
                @prefix ex: <https://example.org/> .
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
                ex:directory a cdp:Federation ; cdp:hasTargetSchema ex:books, ex:writers, ex:empty .
                ex:books a cdp:TargetSchema ; cdp:targetClass ex:Book ; rdfs:label "Bücher"@de, "Books"@en ;
                  cdp:hasTargetField ex:nameField, ex:otherNameField, ex:numberField, ex:writerField, ex:noteField .
                ex:writers a cdp:TargetSchema ; cdp:targetClass ex:Writer ; cdp:hiddenByDefault true ; cdp:hasTargetField ex:nameField .
                ex:empty a cdp:TargetSchema ; cdp:targetClass ex:Unused .
                ex:nameField cdp:targetPredicate ex:name .
                ex:otherNameField cdp:targetPredicate <https://other.example/name> .
                ex:numberField cdp:targetPredicate ex:number .
                ex:writerField cdp:targetPredicate ex:writer .
                ex:noteField cdp:targetPredicate ex:note .
                ex:mapping cdp:toTarget ex:books ; cdp:hasRelationship [cdp:toTargetField ex:writerField ; cdp:toTargetSchema ex:writers] .
                """);
        Files.writeString(work.resolve("directory.ttl"), """
                @prefix ex: <https://example.org/> .
                ex:a a ex:Book ; ex:name "Hello"@en, "Hallo"@de, "Plain" ; ex:number 7 ;
                  ex:writer ex:author ; <https://other.example/name> "Other name" ; ex:note [ex:name "Blank node"] .
                ex:b a ex:Book .
                <https://other.example/a> a ex:Book ; ex:name "Same local ID, different IRI" .
                ex:author a ex:Writer ; ex:name "Ada" .
                """);
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        var jar = Path.of("build/fuseki/fuseki-server.jar").toAbsolutePath().toString();
        var config = Path.of("fuseki.ttl").toAbsolutePath().toString();
        fuseki = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", jar, "org.apache.jena.fuseki.main.cmds.FusekiMainCmd", "--config=" + config, "--port=" + port)
                .directory(work.toFile()).redirectErrorStream(true).redirectOutput(work.resolve("fuseki.log").toFile()).start();
        String endpoint = "http://localhost:" + port + "/directory/sparql";
        try (var client = HttpClient.newHttpClient()) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            boolean ready = false;
            while (fuseki.isAlive() && System.nanoTime() < deadline) {
                try {
                    ready = client.send(HttpRequest.newBuilder(URI.create(endpoint + "?query=ASK%7B%7D"))
                            .timeout(Duration.ofSeconds(1)).build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
                    if (ready) break;
                } catch (java.io.IOException ignored) {}
                Thread.sleep(100);
            }
            assertThat(ready).withFailMessage(Files.readString(work.resolve("fuseki.log"))).isTrue();
        } catch (Exception | AssertionError error) {
            stopFuseki();
            throw error;
        }
        properties.add("directory.api.sparql-url", () -> endpoint);
        properties.add("directory.api.file", () -> work.resolve("directory.ttl").toString());
    }

    @AfterAll
    static void stopFuseki() throws Exception {
        if (fuseki != null) {
            fuseki.destroy();
            if (!fuseki.waitFor(5, TimeUnit.SECONDS)) fuseki.destroyForcibly().waitFor();
        }
    }

    @Test
    void discoversSchemasFieldsAndRelationshipsWithoutSosuseConfiguration() throws Exception {
        var all = body(get("/collections"));
        assertThat(all.path("collections").size()).isEqualTo(3);
        var books = body(get("/collections/books"));
        assertThat(books.path("type").asString()).isEqualTo("https://example.org/Book");
        assertThat(books.path("labels").size()).isEqualTo(2);
        assertThat(books.path("fields").toString()).contains("https://example.org/name", "https://other.example/name", "writers");
        assertThat(get("/collections/writers").statusCode()).isEqualTo(200); // hiddenByDefault is a UI setting.
        assertThat(body(get("/collections/empty/items")).path("items").isEmpty()).isTrue();
        var docs = body(get("/v3/api-docs")).path("paths");
        assertThat(docs.has("/collections/{collection}/items/{id}")).isTrue();
        assertThat(docs.has("/collections/{collection}/items")).isTrue();
        var responses = docs.path("/collections/{collection}/items").path("get").path("responses");
        assertThat(responses.has("200")).isTrue();
        assertThat(responses.has("400")).isTrue();
        assertThat(responses.has("503")).isTrue();
    }

    @Test
    void pagesEntitiesWithoutSplittingValuesAndFollowsRelationshipLinks() throws Exception {
        var page = body(get("/collections/books/items?limit=2"));
        assertThat(page.path("items").size()).isEqualTo(2);
        var item = page.path("items").get(0);
        assertThat(item.path("iri").asString()).isEqualTo("https://example.org/a");
        var properties = item.path("properties");
        assertThat(properties.path("https://example.org/name").size()).isEqualTo(3);
        assertThat(properties.path("https://example.org/name").toString()).contains("\"language\":\"de\"", "\"language\":\"en\"");
        assertThat(properties.path("https://other.example/name").get(0).path("value").asString()).isEqualTo("Other name");
        assertThat(properties.path("number").get(0).path("datatype").asString()).endsWith("#integer");
        assertThat(properties.path("note").get(0).has("blankNode")).isTrue();
        assertThat(page.path("items").get(1).path("properties").isEmpty()).isTrue();
        assertThat(body(get(item.path("href").asString()))).isEqualTo(item);
        var writer = body(get(properties.path("writer").get(0).path("links").get(0).path("href").asString()));
        assertThat(writer.path("properties").path("name").get(0).path("value").asString()).isEqualTo("Ada");
        var next = body(get(page.path("next").asString()));
        assertThat(next.path("items").size()).isEqualTo(1);
        assertThat(next.path("items").get(0).path("id").asString()).isNotEqualTo(item.path("id").asString());
        assertThat(next.has("next")).isFalse();
        assertThat(body(get("/collections/books/items?offset=3")).path("items").isEmpty()).isTrue();
    }

    @Test
    void rejectsInvalidRequestsAndKeepsLookupsWithinTheirCollection() throws Exception {
        for (String query : new String[]{"limit=0", "limit=101", "offset=-1", "limit=invalid"}) {
            var error = get("/collections/books/items?" + query);
            assertThat(error.statusCode()).isEqualTo(400);
            assertThat(error.headers().firstValue("content-type").orElse("")).startsWith("application/problem+json");
        }
        assertThat(get("/collections/missing/items").statusCode()).isEqualTo(404);
        assertThat(get("/collections/books/items/not-an-id!").statusCode()).isEqualTo(400);
        String writerId = Base64.getUrlEncoder().withoutPadding().encodeToString("https://example.org/author".getBytes(StandardCharsets.UTF_8));
        assertThat(get("/collections/books/items/" + writerId).statusCode()).isEqualTo(404);
        String absentId = Base64.getUrlEncoder().withoutPadding().encodeToString("urn:missing".getBytes(StandardCharsets.UTF_8));
        assertThat(get("/collections/books/items/" + absentId).statusCode()).isEqualTo(404);
        try (var socket = new ServerSocket(0)) {
            int unusedPort = socket.getLocalPort();
            socket.close();
            var unavailable = new SparqlClient("http://localhost:" + unusedPort + "/sparql");
            assertThatThrownBy(() -> unavailable.select("SELECT * WHERE {?s ?p ?o}"))
                    .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private JsonNode body(HttpResponse<String> response) {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }
}
