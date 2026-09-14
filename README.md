# Directory API

A Spring Boot API and a separate Apache Jena Fuseki process, packaged in one image.

- `/directory.ttl` streams the deployment's local Turtle snapshot.
- `/swagger-ui.html` opens Swagger UI, including **Try it out**.
- `/v3/api-docs` serves the OpenAPI description (also `/v3/api-docs.yaml`).
- Fuseki serves `/directory/sparql` on port 3030: SELECT/ASK results and CONSTRUCT/DESCRIBE RDF.

## Configure an instance

Copy [compose.example.yml](compose.example.yml) and [Dockerfile.example](Dockerfile.example)
into your instance repository as `compose.yml` and `Dockerfile`. Set
`DIRECTORY_SOURCE_URL`, the local image name and `SPRING_APPLICATION_NAME` for your instance.

The instance build takes the published API image and downloads the Turtle file
into `/app/directory.ttl`. Failed or empty downloads fail the build. Compose starts
API and Fuseki containers from that same image. Fuseki loads the bundled Turtle
into memory at startup. Rebuild to refresh both; restarting reloads the same snapshot.

The API only reads the local file, streaming it without loading it all into memory.
A missing or unreadable snapshot returns 503. Fuseki exposes read-only queries with
a 30-second timeout; outbound SERVICE calls and updates are disabled. The API
container can reach it at `http://fuseki:3030/directory/sparql`.
Additional Turtle files and named graphs can be declared in `fuseki.ttl` without Java code.

## Local development

Requires JDK 25 and a pipeline output file:

```sh
./gradlew build
java -jar build/libs/directory-api.jar --directory.api.file=/absolute/path/to/directory.ttl
```

Open `http://localhost:8080/swagger-ui.html`. The default file is `./directory.ttl`;
`DIRECTORY_API_FILE` can override it in a container.

To run Fuseki without Docker, place `directory.ttl` in this directory and start it
in its own terminal (after `./gradlew build`):

```sh
java -cp build/fuseki/fuseki-server.jar org.apache.jena.fuseki.main.cmds.FusekiMainCmd --config=fuseki.ttl
```

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
