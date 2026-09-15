package de.civicdata.directory.fuseki;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.jena.atlas.lib.IRILib;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.fuseki.server.DataService;
import org.apache.jena.fuseki.server.Operation;
import org.apache.jena.fuseki.system.FusekiLogging;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.ARQ;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.apache.jena.system.Txn;

public final class DirectoryFuseki {
    private static final List<String> ROOTS = List.of("config", "data", "webapp/content", "webapp/exporters");
    private static final List<String> EXCLUDED = List.of("data/ingest/raw", "data/ingest/lifted",
            "data/pipeline/extracted", "data/pipeline/preparation");

    public static void main(String[] args) throws IOException {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException("Usage: java -jar directory-fuseki.jar SNAPSHOT_DIRECTORY [PORT]");
        }
        FusekiLogging.setLogging();
        var server = serve(Path.of(args[0]), args.length == 2 ? Integer.parseInt(args[1]) : 3030);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.start().join();
    }

    /** Load directory.ttl as the default graph and other selected Turtle files as urn:directory:<path>. */
    public static FusekiServer serve(Path snapshot, int port) throws IOException {
        var files = new ArrayList<Path>();
        for (String root : ROOTS) collect(snapshot, Path.of(root), files);
        for (String required : List.of("data/directory.ttl", "config/federation.ttl")) {
            if (!files.contains(Path.of(required)) || Files.size(snapshot.resolve(required)) == 0) {
                throw new IOException("Missing or empty " + required + "; run the pipeline first.");
            }
        }
        var dataset = DatasetGraphFactory.createTxnMem();
        dataset.getContext().set(ARQ.queryTimeout, "30000");
        dataset.getContext().set(ARQ.httpServiceAllowed, false);
        try {
            Txn.executeWrite(dataset, () -> {
                for (Path relative : files) {
                    String path = relative.toString().replace('\\', '/');
                    var graph = path.equals("data/directory.ttl") ? dataset.getDefaultGraph()
                            : dataset.getGraph(NodeFactory.createURI("urn:directory:" + IRILib.encodeUriPath(path)));
                    RDFDataMgr.read(graph, snapshot.resolve(relative).toUri().toString());
                }
            });
            return FusekiServer.create().port(port).loopback(false).enableCors(true)
                    .add("/directory", DataService.newBuilder(dataset).addEndpoint(Operation.Query, "sparql"))
                    .build();
        } catch (RuntimeException error) {
            dataset.close();
            throw error;
        }
    }

    private static void collect(Path snapshot, Path relative, List<Path> files) throws IOException {
        Path file = snapshot.resolve(relative);
        if (Files.isSymbolicLink(file) || relative.getFileName().toString().startsWith(".")
                || EXCLUDED.contains(relative.toString().replace('\\', '/'))) return;
        if (Files.isDirectory(file)) {
            try (var children = Files.list(file)) {
                for (Path child : children.sorted().toList()) collect(snapshot, snapshot.relativize(child), files);
            }
        } else if (Files.isRegularFile(file) && file.toString().endsWith(".ttl")) {
            files.add(relative);
        }
    }
}
