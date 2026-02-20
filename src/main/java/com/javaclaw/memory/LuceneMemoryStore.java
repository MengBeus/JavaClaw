package com.javaclaw.memory;

import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class LuceneMemoryStore implements MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(LuceneMemoryStore.class);

    private final EmbeddingService embeddingService;
    private final HybridSearcher hybridSearcher = new HybridSearcher();
    private final FSDirectory directory;
    private final SmartChineseAnalyzer analyzer = new SmartChineseAnalyzer();
    private final IndexWriter writer;
    private int vectorDimension = -1;

    public LuceneMemoryStore(EmbeddingService embeddingService, String indexPath) throws IOException {
        this.embeddingService = embeddingService;
        var path = Path.of(indexPath);
        Files.createDirectories(path);
        this.directory = FSDirectory.open(path);
        var config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        this.writer = new IndexWriter(directory, config);
    }

    @Override
    public void store(String content, Map<String, Object> metadata) {
        try {
            var id = UUID.randomUUID().toString();
            var doc = new Document();
            doc.add(new StringField("id", id, Field.Store.YES));
            doc.add(new TextField("content", content, Field.Store.YES));

            var vec = embeddingService.embed(content);
            if (vec != null) {
                if (vectorDimension < 0) vectorDimension = vec.length;
                doc.add(new KnnFloatVectorField("embedding", vec));
            }

            writer.addDocument(doc);
            writer.commit();
        } catch (Exception e) {
            log.error("Failed to store memory", e);
        }
    }

    @Override
    public List<MemoryResult> recall(String query, int topK) {
        try {
            if (!DirectoryReader.indexExists(directory)) return List.of();
            try (var reader = DirectoryReader.open(directory)) {
                var searcher = new IndexSearcher(reader);
                var queryVec = embeddingService.embed(query);
                return hybridSearcher.search(searcher, query, queryVec, topK);
            }
        } catch (Exception e) {
            log.error("Failed to recall memory", e);
            return List.of();
        }
    }

    @Override
    public void forget(String memoryId) {
        try {
            writer.deleteDocuments(new Term("id", memoryId));
            writer.commit();
        } catch (Exception e) {
            log.error("Failed to forget memory", e);
        }
    }

    public void close() {
        try {
            writer.close();
            directory.close();
            analyzer.close();
        } catch (IOException e) {
            log.error("Failed to close memory store", e);
        }
    }
}
