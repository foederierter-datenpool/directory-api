package de.civicdata.directory.api;

import static de.civicdata.directory.api.CollectionsController.*;
import static graphql.Scalars.*;
import static graphql.schema.FieldCoordinates.coordinates;
import static graphql.schema.GraphQLArgument.newArgument;
import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static graphql.schema.GraphQLList.list;
import static graphql.schema.GraphQLNonNull.nonNull;
import static graphql.schema.GraphQLObjectType.newObject;
import static graphql.schema.GraphQLTypeReference.typeRef;

import graphql.GraphqlErrorBuilder;
import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.schema.GraphQLCodeRegistry;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.dataloader.DataLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.graphql.execution.BatchLoaderRegistry;
import org.springframework.graphql.execution.DataFetcherExceptionResolver;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.execution.GraphQlSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
class DirectoryGraphQl {
    record Entity(String collection, String id) {}

    @Bean
    RouterFunction<ServerResponse> graphiQl(GraphQlSource source) throws IOException {
        String query = "{\n" + source.schema().getQueryType().getFieldDefinitions().stream().map(field -> {
            var type = (GraphQLObjectType) GraphQLTypeUtil.unwrapAll(field.getType());
            var name = type.getFieldDefinition("name");
            String selection = name != null && GraphQLTypeUtil.unwrapAll(name.getType()) instanceof GraphQLScalarType
                    ? "id name" : "id";
            return "  " + field.getName() + "(limit: 3) { " + selection + " }";
        }).collect(Collectors.joining("\n")) + "\n}";
        // Reuse Spring's stock UI; only add the initial query, optionally supplied by a documentation link.
        String html = new ClassPathResource("graphiql/index.html").getContentAsString(StandardCharsets.UTF_8)
                .replace("fetcher: gqlFetcher,", "initialQuery: params.get(\"query\") || "
                        + JsonMapper.builder().build().writeValueAsString(query) + ",\n                fetcher: gqlFetcher,");
        return RouterFunctions.route().GET("/graphiql",
                request -> ServerResponse.ok().contentType(MediaType.TEXT_HTML).body(html)).build();
    }

    @Bean
    GraphQlSource graphQlSource(DirectoryCollections directory, BatchLoaderRegistry batches) {
        var collections = directory.collections();
        if (collections.isEmpty()) throw new IllegalStateException("No target schemas found in config/federation.ttl");
        var rootNames = names(collections.stream().map(CollectionInfo::iri).toList(), Set.of());
        var typeNames = new LinkedHashMap<String, String>();
        collections.forEach(c -> typeNames.put(c.id(), "Entity_" + rootNames.get(c.iri())));
        var code = GraphQLCodeRegistry.newCodeRegistry();
        var types = new LinkedHashSet<GraphQLType>();
        var query = newObject().name("Query");

        for (var collection : collections) {
            batches.forTypePair(String.class, Item.class).withName(collection.id())
                    .withOptions(options -> options.setMaxBatchSize(100))
                    .registerBatchLoader((ids, _) -> Flux.fromIterable(directory.readItems(collection, ids, collections)));
            String typeName = typeNames.get(collection.id());
            var object = newObject().name(typeName).description("RDF class " + collection.type());
            object.field(newFieldDefinition().name("id").description("Full entity IRI.").type(nonNull(GraphQLID)));
            code.dataFetcher(coordinates(typeName, "id"), (DataFetcher<?>) env -> ((Entity) env.getSource()).id());
            var fieldNames = names(collection.fields().stream().map(Field::predicate).toList(), Set.of("id"));

            for (var field : collection.fields()) {
                String fieldName = fieldNames.get(field.predicate());
                var targets = field.collections();
                var fieldType = targets.isEmpty() ? GraphQLString : typeRef(typeNames.get(targets.getFirst()));
                if (targets.size() > 1) {
                    String unionName = "Targets_" + rootNames.get(collection.iri()) + "_" + fieldName;
                    var union = GraphQLUnionType.newUnionType().name(unionName);
                    targets.forEach(t -> union.possibleType(typeRef(typeNames.get(t))));
                    types.add(union.build());
                    code.typeResolver(unionName, env -> env.getSchema().getObjectType(typeNames.get(((Entity) env.getObject()).collection())));
                    fieldType = typeRef(unionName);
                }
                object.field(newFieldDefinition().name(fieldName).description(field.predicate())
                        .type(nonNull(list(nonNull(fieldType)))));
                code.dataFetcher(coordinates(typeName, fieldName), (DataFetcher<?>) env -> {
                    Entity entity = env.getSource();
                    DataLoader<String, Item> loader = env.getDataLoader(collection.id());
                    return loader.load(entity.id()).thenApply(item -> {
                        var values = item.properties().getOrDefault(field.name(), List.of());
                        if (targets.isEmpty()) return values.stream().map(DirectoryGraphQl::lexicalValue).toList();
                        var linked = new ArrayList<Entity>();
                        for (var value : values) {
                            if (value.iri() == null || value.links() == null) continue;
                            targets.stream().filter(t -> value.links().stream().anyMatch(link -> link.collection().equals(t)))
                                    .findFirst().ifPresent(t -> linked.add(new Entity(t, value.iri())));
                        }
                        return linked;
                    });
                });
            }
            types.add(object.build());
            String root = rootNames.get(collection.iri());
            query.field(newFieldDefinition().name(root).description(collection.iri() + "; entities ordered by IRI.")
                    .type(nonNull(list(nonNull(typeRef(typeName)))))
                    .argument(newArgument().name("id").type(GraphQLID).description("Optionally select one full entity IRI."))
                    .argument(newArgument().name("limit").type(nonNull(GraphQLInt)).defaultValueProgrammatic(50))
                    .argument(newArgument().name("offset").type(nonNull(GraphQLInt)).defaultValueProgrammatic(0)));
            code.dataFetcher(coordinates("Query", root), (DataFetcher<?>) env -> {
                int limit = env.getArgument("limit"), offset = env.getArgument("offset");
                String id = env.getArgument("id");
                if (limit < 1 || limit > 100 || offset < 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be 1–100 and offset must be non-negative");
                }
                if (id != null) {
                    try {
                        if (!URI.create(id).isAbsolute()) throw new IllegalArgumentException();
                    } catch (IllegalArgumentException e) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id must be an absolute IRI");
                    }
                }
                return directory.entityIris(collection, id, limit, offset).stream().map(iri -> new Entity(collection.id(), iri)).toList();
            });
        }
        var schema = GraphQLSchema.newSchema().query(query.build()).additionalTypes(types).codeRegistry(code.build()).build();
        return GraphQlSource.builder(schema)
                // Allow the nested type wrappers in standard introspection, while bounding recursive queries.
                .instrumentation(List.of(new MaxQueryDepthInstrumentation(20)))
                .exceptionResolvers(List.of(DataFetcherExceptionResolver.forSingleError((exception, env) ->
                        exception instanceof ResponseStatusException error && error.getStatusCode().is4xxClientError()
                                ? GraphqlErrorBuilder.newError(env).errorType(ErrorType.BAD_REQUEST).message(error.getReason()).build() : null)))
                .build();
    }

    private static String lexicalValue(RdfValue value) {
        return value.value() != null ? value.value() : value.iri() != null ? value.iri() : "_:" + value.blankNode();
    }

    // The IRI-derived suffix makes colliding names deterministic, independent of configuration order.
    static Map<String, String> names(List<String> iris, Set<String> reserved) {
        var bases = new LinkedHashMap<String, String>();
        for (String iri : iris) {
            String base = iri.substring(Math.max(iri.lastIndexOf('#'), Math.max(iri.lastIndexOf('/'), iri.lastIndexOf(':'))) + 1)
                    .replaceAll("[^_0-9A-Za-z]", "_");
            if (!base.matches("[A-Za-z].*")) base = "field_" + base;
            bases.put(iri, base);
        }
        var result = new LinkedHashMap<String, String>();
        bases.forEach((iri, base) -> result.put(iri, reserved.contains(base) || bases.values().stream().filter(base::equals).count() > 1
                ? base + "_" + UUID.nameUUIDFromBytes(iri.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "") : base));
        return result;
    }
}
