package de.civicdata.directory.api;

import static de.civicdata.directory.api.CollectionsController.*;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@Service
class DirectoryCollections {
    private final SparqlClient sparql;

    DirectoryCollections(SparqlClient sparql) {
        this.sparql = sparql;
    }

    List<CollectionInfo> collections() {
        var rows = sparql.select("""
                PREFIX cdp: <https://civic-data.de/pipeline#>
                PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                SELECT DISTINCT ?schema ?type ?label ?predicate ?target WHERE {
                  GRAPH <urn:directory:config> {
                    ?federation a cdp:Federation ; cdp:hasTargetSchema ?schema .
                    ?schema a cdp:TargetSchema ; cdp:targetClass ?type .
                    OPTIONAL { ?schema rdfs:label ?label }
                    OPTIONAL {
                      ?schema cdp:hasTargetField ?field . ?field cdp:targetPredicate ?predicate .
                      OPTIONAL {
                        ?mapping cdp:toTarget ?schema ; cdp:hasRelationship ?relationship .
                        ?relationship cdp:toTargetField ?field ; cdp:toTargetSchema ?target .
                      }
                    }
                  }
                } ORDER BY ?schema ?predicate ?label ?target
                """);
        var definitions = new LinkedHashMap<String, Definition>();
        for (var row : rows) {
            String iri = value(row, "schema"), type = value(row, "type");
            var definition = definitions.computeIfAbsent(iri, _ -> new Definition(iri, type));
            if (!definition.type.equals(type)) throw invalidConfig("A target schema must have one target class");
            if (row.has("label")) definition.labels.add(rdfValue(row.get("label"), null));
            if (row.has("predicate")) {
                var targets = definition.fields.computeIfAbsent(value(row, "predicate"), _ -> new LinkedHashSet<>());
                if (row.has("target")) targets.add(value(row, "target"));
            }
        }
        var result = new ArrayList<CollectionInfo>();
        var ids = new LinkedHashSet<String>();
        for (var definition : definitions.values()) {
            String id = localName(definition.iri);
            if (id.isEmpty() || !ids.add(id)) throw invalidConfig("Target schema names must be non-empty and unique: " + id);
            var fields = definition.fields.entrySet().stream().map(entry -> {
                String name = localName(entry.getKey());
                boolean collision = definition.fields.keySet().stream().filter(p -> localName(p).equals(name)).count() > 1;
                var targets = entry.getValue().stream().filter(definitions::containsKey).map(DirectoryCollections::localName).sorted().toList();
                return new Field(collision ? entry.getKey() : name, entry.getKey(), targets);
            }).toList();
            result.add(new CollectionInfo(id, definition.iri, definition.type, List.copyOf(definition.labels), fields,
                    path(id), path(id) + "/items"));
        }
        return result;
    }

    CollectionInfo collection(String id) {
        return find(collections(), id);
    }

    ItemPage items(String id, int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be 1–100 and offset must be non-negative");
        }
        var collections = collections();
        var collection = find(collections, id);
        var rows = sparql.select("SELECT DISTINCT ?entity WHERE { BIND(IRI(" + sparql.string(collection.type())
                + ") AS ?type) ?entity a ?type . FILTER(isIRI(?entity)) } ORDER BY STR(?entity) LIMIT "
                + (limit + 1) + " OFFSET " + offset);
        var iris = new ArrayList<String>();
        for (var row : rows) iris.add(value(row, "entity"));
        String next = iris.size() > limit ? collection.items() + "?limit=" + limit + "&offset=" + ((long) offset + limit) : null;
        return new ItemPage(readItems(collection, iris.subList(0, Math.min(iris.size(), limit)), collections), limit, offset, next);
    }

    Item item(String collectionId, String id) {
        String iri;
        try {
            iri = new String(Base64.getUrlDecoder().decode(id), StandardCharsets.UTF_8);
            if (!encode(iri).equals(id) || !URI.create(iri).isAbsolute()) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid item ID; follow the href returned by the API");
        }
        var collections = collections();
        var collection = find(collections, collectionId);
        var rows = sparql.select("SELECT ?entity WHERE { BIND(IRI(" + sparql.string(iri) + ") AS ?entity) "
                + "BIND(IRI(" + sparql.string(collection.type()) + ") AS ?type) ?entity a ?type } LIMIT 1");
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found in this collection");
        return readItems(collection, List.of(iri), collections).getFirst();
    }

    private List<Item> readItems(CollectionInfo collection, List<String> iris, List<CollectionInfo> collections) {
        var items = new LinkedHashMap<String, Map<String, Map<JsonNode, Set<Link>>>>();
        iris.forEach(iri -> items.put(iri, new TreeMap<>()));
        if (!iris.isEmpty() && !collection.fields().isEmpty()) {
            // Bind values as escaped literals, then convert to IRIs: no caller input becomes SPARQL syntax.
            String entities = iris.stream().map(sparql::string).collect(Collectors.joining(" "));
            String predicates = collection.fields().stream().map(f -> sparql.string(f.predicate())).collect(Collectors.joining(" "));
            var fields = collection.fields().stream().collect(Collectors.toMap(Field::predicate, Field::name));
            var rows = sparql.select("SELECT DISTINCT ?entity ?predicate ?value ?valueType WHERE { "
                    + "VALUES ?entityIri { " + entities + " } BIND(IRI(?entityIri) AS ?entity) "
                    + "VALUES ?predicateIri { " + predicates + " } BIND(IRI(?predicateIri) AS ?predicate) "
                    + "?entity ?predicate ?value . OPTIONAL { FILTER(isIRI(?value)) ?value a ?valueType } "
                    + "} ORDER BY ?entity ?predicate ?value ?valueType");
            for (var row : rows) {
                var terms = items.get(value(row, "entity")).computeIfAbsent(fields.get(value(row, "predicate")), _ -> new LinkedHashMap<>());
                var links = terms.computeIfAbsent(row.get("value"), _ -> new LinkedHashSet<>());
                if (row.has("valueType")) {
                    for (var target : collections) {
                        if (target.type().equals(value(row, "valueType"))) {
                            links.add(new Link(target.id(), target.items() + "/" + encode(value(row, "value"))));
                        }
                    }
                }
            }
        }
        return items.entrySet().stream().map(entry -> {
            var properties = new LinkedHashMap<String, List<RdfValue>>();
            entry.getValue().forEach((name, terms) -> properties.put(name, terms.entrySet().stream()
                    .map(term -> rdfValue(term.getKey(), term.getValue().isEmpty() ? null : List.copyOf(term.getValue()))).toList()));
            String id = encode(entry.getKey());
            return new Item(id, entry.getKey(), collection.id(), collection.items() + "/" + id, properties);
        }).toList();
    }

    private static RdfValue rdfValue(JsonNode term, List<Link> links) {
        String value = term.path("value").asString();
        return switch (term.path("type").asString()) {
            case "uri" -> new RdfValue(null, null, null, value, null, links);
            case "bnode" -> new RdfValue(null, null, null, null, value, null);
            default -> new RdfValue(value, term.has("datatype") ? term.get("datatype").asString() : null,
                    term.has("xml:lang") ? term.get("xml:lang").asString() : null, null, null, null);
        };
    }

    private static CollectionInfo find(List<CollectionInfo> collections, String id) {
        return collections.stream().filter(c -> c.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Collection not found"));
    }

    private static String value(JsonNode row, String name) { return row.path(name).path("value").asString(); }
    private static String encode(String iri) { return Base64.getUrlEncoder().withoutPadding().encodeToString(iri.getBytes(StandardCharsets.UTF_8)); }
    private static String path(String id) { return "/collections/" + URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static String localName(String iri) { return iri.substring(Math.max(iri.lastIndexOf('#'), Math.max(iri.lastIndexOf('/'), iri.lastIndexOf(':'))) + 1); }
    private static ResponseStatusException invalidConfig(String reason) { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, reason); }

    private static class Definition {
        final String iri;
        final String type;
        final Set<RdfValue> labels = new LinkedHashSet<>();
        final Map<String, Set<String>> fields = new TreeMap<>();
        Definition(String iri, String type) { this.iri = iri; this.type = type; }
    }
}
