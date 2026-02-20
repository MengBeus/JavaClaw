package com.javaclaw.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

public class DoctorCommand {

    private static final Logger log = LoggerFactory.getLogger(DoctorCommand.class);

    private final DataSource dataSource;
    private final String embeddingBaseUrl;

    public DoctorCommand(DataSource dataSource, String embeddingBaseUrl) {
        this.dataSource = dataSource;
        this.embeddingBaseUrl = embeddingBaseUrl;
    }

    public String run() {
        var results = new ArrayList<String>();
        results.add(checkDatabase());
        results.add(checkEmbeddingEndpoint());
        results.add(checkLuceneIndex());
        results.add(checkJavaVersion());
        return String.join("\n", results);
    }

    private String checkDatabase() {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("SELECT 1");
             var rs = ps.executeQuery()) {
            return "[OK] PostgreSQL connection";
        } catch (Exception e) {
            return "[FAIL] PostgreSQL: " + e.getMessage();
        }
    }

    private String checkEmbeddingEndpoint() {
        try {
            var client = java.net.http.HttpClient.newHttpClient();
            var req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(embeddingBaseUrl))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .GET().build();
            var resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() < 500
                    ? "[OK] Embedding endpoint reachable"
                    : "[FAIL] Embedding endpoint: HTTP " + resp.statusCode();
        } catch (Exception e) {
            return "[FAIL] Embedding endpoint: " + e.getMessage();
        }
    }

    private String checkLuceneIndex() {
        var indexPath = System.getProperty("user.home") + "/.javaclaw/index";
        var path = java.nio.file.Path.of(indexPath);
        return java.nio.file.Files.isDirectory(path)
                ? "[OK] Lucene index directory exists"
                : "[WARN] Lucene index directory not found (will be created on first store)";
    }

    private String checkJavaVersion() {
        var ver = Runtime.version().feature();
        return ver >= 21
                ? "[OK] Java " + ver
                : "[WARN] Java " + ver + " (21+ recommended)";
    }
}
