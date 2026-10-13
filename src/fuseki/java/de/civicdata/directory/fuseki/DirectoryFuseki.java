package de.civicdata.directory.fuseki;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.apache.jena.atlas.lib.IRILib;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.fuseki.server.DataService;
import org.apache.jena.fuseki.server.Operation;
import org.apache.jena.fuseki.system.FusekiLogging;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.ARQ;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.system.Txn;
import org.apache.jena.tdb2.DatabaseMgr;
import org.apache.jena.tdb2.params.StoreParams;
import org.apache.jena.tdb2.sys.TDBInternal;
import org.eclipse.jetty.util.component.LifeCycle;

public final class DirectoryFuseki {
    static {
        // Avoid precision loss from TDB2's compact inline numeric encoding.
        System.setProperty("org.apache.jena.tdb.store.enableInlineLiterals", "false");
    }
    private static final List<String> ROOTS = List.of("config", "data", "webapp/content", "webapp/exporters");
    private static final List<String> EXCLUDED = List.of("data/ingest/raw", "data/ingest/lifted",
            "data/pipeline/extracted", "data/pipeline/preparation");

    public static void main(String[] args) throws IOException {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException("Usage: java -jar directory-fuseki.jar SNAPSHOT_DIRECTORY [PORT]");
        }
        FusekiLogging.setLogging();
        Path snapshot = Path.of(args[0]);
        Path database = Path.of(System.getenv().getOrDefault("DIRECTORY_FUSEKI_DATABASE",
                snapshot.resolve("data/.fuseki").toString()));
        var server = serve(snapshot, database, args.length == 2 ? Integer.parseInt(args[1]) : 3030);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.start().join();
    }

    /** Load directory.ttl as the default graph and other selected Turtle files as urn:directory:<path>. */
    public static FusekiServer serve(Path snapshot, int port) throws IOException {
        return serve(snapshot, snapshot.resolve("data/.fuseki"), port);
    }

    public static FusekiServer serve(Path snapshot, Path database, int port) throws IOException {
        var files = new ArrayList<Path>();
        for (String root : ROOTS) collect(snapshot, Path.of(root), files);
        for (String required : List.of("data/directory.ttl", "config/federation.ttl")) {
            if (!files.contains(Path.of(required)) || Files.size(snapshot.resolve(required)) == 0) {
                throw new IOException("Missing or empty " + required + "; run the pipeline first.");
            }
        }
        String fingerprint = fingerprint(snapshot, files);
        Files.createDirectories(database);
        Path marker = database.resolve("snapshot.sha256");
        var dataset = DatabaseMgr.connectDatasetGraph(database.toString(), StoreParams.getSmallStoreParams());
        dataset.getContext().set(ARQ.queryTimeout, "30000");
        dataset.getContext().set(ARQ.httpServiceAllowed, false);
        try {
            if (!Files.exists(marker) || !Files.readString(marker).equals(fingerprint)) {
                // Replace all graphs in one transaction: failed imports never become queryable.
                Txn.executeWrite(dataset, () -> {
                    dataset.clear();
                    for (Path relative : files) {
                        String path = relative.toString().replace('\\', '/');
                        System.out.println("TDB2: loading " + path);
                        var graph = path.equals("data/directory.ttl") ? dataset.getDefaultGraph()
                                : dataset.getGraph(NodeFactory.createURI("urn:directory:" + IRILib.encodeUriPath(path)));
                        RDFDataMgr.read(graph, snapshot.resolve(relative).toUri().toString());
                    }
                });
                Files.writeString(marker, fingerprint);
                System.out.println("TDB2: snapshot imported");
            } else {
                System.out.println("TDB2: reusing imported snapshot");
            }
            var server = FusekiServer.create().port(port).loopback(false).enableCors(true)
                    .add("/directory", DataService.newBuilder(dataset).addEndpoint(Operation.Query, "sparql"))
                    .build();
            server.getJettyServer().addEventListener(new LifeCycle.Listener() {
                @Override public void lifeCycleStopped(LifeCycle lifecycle) { TDBInternal.expel(dataset); }
            });
            return server;
        } catch (IOException | RuntimeException error) {
            TDBInternal.expel(dataset);
            throw error;
        }
    }

    private static String fingerprint(Path snapshot, List<Path> files) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
        for (Path relative : files) {
            Path file = snapshot.resolve(relative);
            digest.update((relative.toString().replace('\\', '/') + "\0" + Files.size(file) + "\0")
                    .getBytes(StandardCharsets.UTF_8));
            try (var input = new DigestInputStream(Files.newInputStream(file), digest)) {
                input.transferTo(OutputStream.nullOutputStream());
            }
        }
        return HexFormat.of().formatHex(digest.digest());
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
