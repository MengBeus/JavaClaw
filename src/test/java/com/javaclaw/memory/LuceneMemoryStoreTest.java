package com.javaclaw.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LuceneMemoryStoreTest {

    @TempDir Path tempDir;
    private LuceneMemoryStore store;

    @BeforeEach
    void setUp() throws Exception {
        // Stub embedding service returns null → pure BM25 search
        var embedding = new EmbeddingService("http://localhost:0", "", "test") {
            @Override public float[] embed(String text) { return null; }
        };
        store = new LuceneMemoryStore(embedding, tempDir.toString());
    }

    @AfterEach
    void tearDown() { store.close(); }

    @Test
    void storeAndRecallByKeyword() {
        store.store("Java 虚拟线程是 JDK21 的新特性", Map.of("source", "doc"));
        var results = store.recall("虚拟线程", 5);
        assertEquals(1, results.size());
        assertTrue(results.get(0).content().contains("虚拟线程"));
    }

    @Test
    void metadataIsPreserved() {
        store.store("test content", Map.of("session", "s1", "tag", "important"));
        var results = store.recall("test", 5);
        assertEquals(1, results.size());
        assertEquals("s1", results.get(0).metadata().get("session"));
        assertEquals("important", results.get(0).metadata().get("tag"));
    }

    @Test
    void forgetRemovesDocument() {
        store.store("to be forgotten", Map.of());
        var results = store.recall("forgotten", 5);
        assertEquals(1, results.size());
        store.forget(results.get(0).id());
        var after = store.recall("forgotten", 5);
        assertTrue(after.isEmpty());
    }

    @Test
    void recallEmptyIndexReturnsEmpty() {
        var results = store.recall("anything", 5);
        assertTrue(results.isEmpty());
    }
}
