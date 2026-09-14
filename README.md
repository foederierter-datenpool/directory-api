# Directory API

A Spring Boot API and a separate Apache Jena Fuseki process, packaged in one image.

- `/directory.ttl` streams the deployment's local Turtle snapshot.
- `/collections` discovers target schemas; `/collections/{collection}` describes their fields and relationships.
- `/collections/{collection}/items?limit=50&offset=0` lists entities; follow each item's `href` to retrieve it.
- `/swagger-ui.html` opens Swagger UI, including **Try it out**.
- `/v3/api-docs` serves the OpenAPI description (also `/v3/api-docs.yaml`).
- Fuseki serves `/directory/sparql` on port 3030: SELECT/ASK results and CONSTRUCT/DESCRIBE RDF.

## Configure an instance

Copy [compose.example.yml](compose.example.yml) and [Dockerfile.example](Dockerfile.example)
into your instance repository as `compose.yml` and `Dockerfile`. Set
`DIRECTORY_SOURCE_URL`, `FEDERATION_SOURCE_URL`, the local image name and
`SPRING_APPLICATION_NAME` for your instance. Both source URLs should refer to the
same published pipeline snapshot.

The instance build takes the published API image and downloads the Turtle file
into `/app/directory.ttl` and its configuration into `/app/federation.ttl`.
Failed or empty downloads fail the build. Compose starts
API and Fuseki containers from that same image. Fuseki loads the bundled Turtle
into memory at startup. Rebuild to refresh both; restarting reloads the same snapshot.

The download streams the local file; collection calls query Fuseki over HTTP.
A missing or unreadable snapshot returns 503. Fuseki exposes read-only queries with
a 30-second timeout; outbound SERVICE calls and updates are disabled. The API
container can reach it at `http://fuseki:3030/directory/sparql`.
Additional Turtle files and named graphs can be declared in `fuseki.ttl` without Java code.

## Collection responses

The API reads the federation's `hasTargetSchema`, `targetClass`, `hasTargetField`
and mapping relationships from `federation.ttl`, loaded in the named graph
`urn:directory:config`. The default graph remains the directory data. Collection IDs
are the schema IRI's final segment (which must be unique); `hiddenByDefault` does not
hide a collection. No instance-specific Java classes or second schema definition are needed.

Entities contain `id`, the full `iri`, `collection`, `href`, and `properties`.
Property names use predicate final segments; colliding names use their full IRIs.
Collection metadata maps every property name back to its predicate. Only declared
target fields are returned; absent fields are omitted and all present fields are arrays.
Literals preserve their lexical `value`, `datatype` and `language`; resources have
an `iri` and links to matching collections. Blank nodes have snapshot-local IDs and
are not expanded. For example, a name and an address reference look like:

```json
{
  "name": [{"value": "Beratung", "language": "de"}],
  "address": [{"iri": "https://example.org/address/1", "links": [
    {"collection": "addresses", "href": "/collections/addresses/items/aHR0cHM6Ly9leGFtcGxlLm9yZy9hZGRyZXNzLzE"}
  ]}]
}
```

Only entities with IRIs are addressable. Follow returned item/relationship links: IDs encode full IRIs as URL-safe Base64,
so equal local names in different namespaces remain distinct. Pages use stable IRI
order, a `limit` of 1–100, a non-negative `offset`, and an optional `next` link.
Pagination is stable within a snapshot; rebuilding may change its contents.
Invalid parameters return 400, missing collections/items 404 and unavailable Fuseki
503, as Problem Details JSON. Domain-specific filters and additional REST formats
are not implemented yet; Swagger documents the available calls.

## Local development

Requires JDK 25 and a pipeline output file:

```sh
./gradlew build
java -jar build/libs/directory-api.jar --directory.api.file=/absolute/path/to/directory.ttl
```

Open `http://localhost:8080/swagger-ui.html`. The default file is `./directory.ttl`;
`DIRECTORY_API_FILE` can override it in a container.

To run Fuseki without Docker, place `directory.ttl` and `federation.ttl` in this directory and start it
in its own terminal (after `./gradlew build`):

```sh
java -cp build/fuseki/fuseki-server.jar org.apache.jena.fuseki.main.cmds.FusekiMainCmd --config=fuseki.ttl
```

The API defaults to `http://localhost:3030/directory/sparql`; the Compose example
sets `DIRECTORY_API_SPARQL_URL` to the internal `fuseki` hostname.

```sh
curl --get --data-urlencode 'query=SELECT (COUNT(*) AS ?triples) WHERE { ?s ?p ?o }' \
  -H 'Accept: application/sparql-results+json' http://localhost:3030/directory/sparql
```

## Image publication

Pushes to `main` build, test and publish the amd64 image to
`ghcr.io/foederierter-datenpool/directory-api`, tagged `main` and
`sha-<full-commit-sha>`. Pull requests test without publishing. The workflow uses
GitHub's automatic token. Make the container package **Public** after its first publication.

This generic image contains no instance data. Rebuild your instance after publishing
API changes or new pipeline output. To fix the API version, set the `API_IMAGE`
build argument to an image digest. The example allows 512 MiB per container; larger
datasets need measured resource budgets.
