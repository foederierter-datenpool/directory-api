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
import java.util.Map;
import de.civicdata.directory.fuseki.DirectoryFuseki;
import org.apache.jena.fuseki.main.FusekiServer;
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
class DirectoryApiTests {
    @TempDir static Path work;
    private static FusekiServer fuseki;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @LocalServerPort int port;

    @DynamicPropertySource
    static void startFuseki(DynamicPropertyRegistry properties) throws Exception {
        Files.createDirectories(work.resolve("config"));
        Files.createDirectories(work.resolve("data"));
        Files.writeString(work.resolve("config/federation.ttl"), """
                @prefix cdp: <https://civic-data.de/pipeline#> .
                @prefix ex: <https://example.org/> .
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
                ex:directory a cdp:Federation ; cdp:hasTargetSchema ex:books, ex:writers, ex:publishers, ex:empty .
                ex:books a cdp:TargetSchema ; cdp:targetClass ex:Book ; rdfs:label "Bücher"@de, "Books"@en ;
                  cdp:hasTargetField ex:nameField, ex:otherNameField, ex:numberField, ex:writerField, ex:noteField .
                ex:writers a cdp:TargetSchema ; cdp:targetClass ex:Writer ; cdp:hiddenByDefault true ; cdp:hasTargetField ex:nameField .
                ex:publishers a cdp:TargetSchema ; cdp:targetClass ex:Publisher ; cdp:hasTargetField ex:nameField .
                ex:empty a cdp:TargetSchema ; cdp:targetClass ex:Unused .
                ex:nameField cdp:targetPredicate ex:name .
                ex:otherNameField cdp:targetPredicate <https://other.example/name> .
                ex:numberField cdp:targetPredicate ex:number .
                ex:writerField cdp:targetPredicate ex:writer .
                ex:noteField cdp:targetPredicate ex:note .
                ex:mapping cdp:toTarget ex:books ; cdp:hasRelationship [cdp:toTargetField ex:writerField ; cdp:toTargetSchema ex:writers],
                  [cdp:toTargetField ex:writerField ; cdp:toTargetSchema ex:publishers] .
                """);
        Files.writeString(work.resolve("data/directory.ttl"), """
                @prefix ex: <https://example.org/> .
                ex:a a ex:Book ; ex:name "Hello"@en, "Hallo"@de, "Plain" ; ex:number 7 ;
                  ex:writer ex:author, ex:press ; <https://other.example/name> "Other name" ; ex:note [ex:name "Blank node"] .
                ex:b a ex:Book .
                <https://other.example/a> a ex:Book ; ex:name "Same local ID, different IRI" .
                ex:author a ex:Writer ; ex:name "Ada" .
                ex:press a ex:Publisher ; ex:name "Small Press" .
                [] a ex:Book ; ex:name "Not addressable" .
                """);
        Files.writeString(work.resolve("data/provenance.ttl"), "<urn:artifact> a <https://example.org/Book> .");
        fuseki = DirectoryFuseki.serve(work, 0).start();
        String endpoint = "http://localhost:" + fuseki.getPort() + "/directory/sparql";
        properties.add("directory.api.sparql-url", () -> endpoint);
        properties.add("directory.api.file", () -> work.resolve("data/directory.ttl").toString());
    }

    @AfterAll
    static void stopFuseki() {
        if (fuseki != null) fuseki.stop();
    }

    @Test
    void discoversSchemasFieldsAndRelationshipsWithoutSosuseConfiguration() throws Exception {
        var all = body(get("/collections"));
        assertThat(all.path("collections").size()).isEqualTo(4);
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

    @Test
    void derivesAQueryOnlyGraphQlSchemaAndServesGraphiQl() throws Exception {
        var result = graphql(graphql.introspection.IntrospectionQuery.INTROSPECTION_QUERY, Map.of());
        assertThat(result.has("errors")).withFailMessage(result.toString()).isFalse();
        var schema = result.path("data").path("__schema");
        assertThat(schema.path("mutationType").isNull()).isTrue();
        assertThat(schema.path("subscriptionType").isNull()).isTrue();
        assertThat(schema.toString()).contains("Entity_books", "Entity_writers", "Targets_books_writer");
        var ui = get("/graphiql");
        assertThat(ui.statusCode()).isEqualTo(200);
        assertThat(ui.body()).contains("GraphiQL");
        String marker = "initialQuery: params.get(\"query\") || ";
        assertThat(ui.body()).contains(marker);
        String encoded = ui.body().substring(ui.body().indexOf(marker) + marker.length()).split(",\\n", 2)[0];
        String initialQuery = JSON.readValue(encoded, String.class);
        var example = graphql(initialQuery, Map.of());
        assertThat(example.has("errors")).withFailMessage(example.toString()).isFalse();
        assertThat(example.path("data").path("books").size()).isEqualTo(3);
        var queryLink = get("/graphiql?query=%7Bbooks%7Bid%7D%7D");
        assertThat(queryLink.statusCode()).isEqualTo(200);
        assertThat(queryLink.body()).isEqualTo(ui.body());
    }

    @Test
    void graphQlSupportsVariablesFragmentsAliasesAndCollidingPredicates() throws Exception {
        var fields = graphql("{ __type(name: \"Entity_books\") { fields { name description } } }", Map.of())
                .path("data").path("__type").path("fields");
        var names = new java.util.HashMap<String, String>();
        fields.forEach(f -> names.put(f.path("description").asString(), f.path("name").asString()));
        String name = names.get("https://example.org/name"), otherName = names.get("https://other.example/name");
        assertThat(name).isNotEqualTo(otherName);
        String query = """
                query Page($limit: Int!, $skip: Boolean!) {
                  first: books(limit: $limit) { ...Book number @skip(if: $skip) }
                  second: books(limit: 1, offset: 1) { ...Book }
                }
                fragment Book on Entity_books { id primaryName: %s otherName: %s }
                """.formatted(name, otherName);
        var result = graphql(query, Map.of("limit", 1, "skip", true));
        assertThat(result.has("errors")).withFailMessage(result.toString()).isFalse();
        var first = result.path("data").path("first").get(0);
        assertThat(first.path("id").asString()).isEqualTo("https://example.org/a");
        assertThat(first.path("primaryName").size()).isEqualTo(3);
        assertThat(first.path("otherName").get(0).asString()).isEqualTo("Other name");
        assertThat(first.has("number")).isFalse();
        assertThat(result.path("data").path("second").get(0).path("primaryName").isEmpty()).isTrue();
        var all = graphql("{ books { id number } }", Map.of()).path("data").path("books");
        assertThat(all.size()).isEqualTo(3); // Neither blank nodes nor named-graph-only entities leak in.
        assertThat(all.get(0).path("number").get(0).asString()).isEqualTo("7");
        var one = graphql("query($id: ID!) { books(id: $id) { id } }", Map.of("id", "https://other.example/a"));
        assertThat(one.path("data").path("books").size()).isEqualTo(1);
        assertThat(one.path("data").path("books").get(0).path("id").asString()).isEqualTo("https://other.example/a");
    }

    @Test
    void graphQlFollowsTypedRelationshipsAndUnionTargets() throws Exception {
        var result = graphql("""
                { books { id writer {
                  __typename ... on Entity_writers { name } ... on Entity_publishers { name }
                } } }
                """, Map.of());
        assertThat(result.has("errors")).withFailMessage(result.toString()).isFalse();
        var books = result.path("data").path("books");
        assertThat(books.get(0).path("writer").toString()).contains("Entity_writers", "Ada", "Entity_publishers", "Small Press");
        assertThat(books.get(1).path("writer").isEmpty()).isTrue();
    }

    @Test
    void graphQlRejectsInvalidQueriesPaginationInjectionAndExcessiveDepth() throws Exception {
        for (String query : new String[]{"{ books { unknown } }", "mutation { books { id } }",
                "{ books(limit: 101) { id } }", "{ books(offset: -1) { id } }"}) {
            assertThat(graphql(query, Map.of()).has("errors")).as(query).isTrue();
        }
        var injected = graphql("query($id: ID) { books(id: $id) { id } }",
                Map.of("id", "https://example.org/a> } UNION { ?s ?p ?o } #"));
        assertThat(injected.path("errors").toString()).contains("absolute IRI");
        var missing = graphql("{ books(id: \"urn:missing\") { id } }", Map.of());
        assertThat(missing.path("data").path("books").isEmpty()).isTrue();
        String deep = "{ __type(name: \"Entity_books\") { " + "ofType { ".repeat(25) + "name" + " }".repeat(27);
        assertThat(graphql(deep, Map.of()).path("errors").toString()).containsIgnoringCase("depth");
    }

    private JsonNode graphql(String query, Map<String, Object> variables) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/graphql"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("query", query, "variables", variables))))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).withFailMessage(response.body()).isIn(200, 400);
            return JSON.readTree(response.body());
        }
    }

    @Test
    void servesHelloAndHealthOverHttp() throws Exception {
        var hello = get("/hello");
        assertThat(hello.statusCode()).isEqualTo(200);
        assertThat(hello.headers().firstValue("content-type").orElse("")).startsWith("application/json");
        assertThat(hello.body()).isEqualTo("{\"message\":\"Hello world!\"}");
        var health = get("/actuator/health/readiness");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"");
        assertThat(get("/actuator/env").statusCode()).isEqualTo(404);
    }

    @Test
    void servesTheLocalSnapshotAndReportsMissingFiles() throws Exception {
        String turtle = Files.readString(work.resolve("data/directory.ttl"));
        var download = get("/directory.ttl");
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.headers().firstValue("content-type")).contains("text/turtle");
        assertThat(download.headers().firstValue("content-disposition")).contains("attachment; filename=\"directory.ttl\"");
        assertThat(download.body()).isEqualTo(turtle);

        Files.delete(work.resolve("data/directory.ttl"));
        try {
            var failure = get("/directory.ttl");
            assertThat(failure.statusCode()).isEqualTo(503);
            assertThat(failure.headers().firstValue("content-disposition").orElse("")).doesNotContain("attachment");
        } finally {
            Files.writeString(work.resolve("data/directory.ttl"), turtle);
        }
    }

    @Test
    void documentsTheDownloadAndServesSwaggerUi() throws Exception {
        var docs = get("/v3/api-docs");
        assertThat(docs.statusCode()).isEqualTo(200);
        assertThat(docs.body()).contains("\"/directory.ttl\"", "\"text/turtle\"", "\"binary\"", "\"503\"", "\"url\":\"/\"");
        assertThat(get("/v3/api-docs.yaml").statusCode()).isEqualTo(200);
        var redirect = get("/swagger-ui.html");
        assertThat(redirect.statusCode()).isEqualTo(302);
        var ui = get(redirect.headers().firstValue("location").orElseThrow());
        assertThat(ui.statusCode()).isEqualTo(200);
        assertThat(ui.body()).contains("swagger-ui-bundle.js");
        assertThat(get("/v3/api-docs/swagger-config").body()).contains("\"url\":\"/v3/api-docs\"");
    }

    private HttpResponse<String> get(String path) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port).resolve(path))
                    .timeout(Duration.ofSeconds(10)).header("Accept", path.equals("/directory.ttl") ? "text/turtle" : "*/*").build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private JsonNode body(HttpResponse<String> response) {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }
}
