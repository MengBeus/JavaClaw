package com.javaclaw.memory;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;

import java.util.*;

public class HybridSearcher {

    private static final int RRF_K = 60;

    public List<MemoryResult> search(IndexSearcher searcher, String query,
                                     float[] queryVector, int topK) throws Exception {
        var vectorRanks = new LinkedHashMap<String, Integer>();
        var bm25Ranks = new LinkedHashMap<String, Integer>();
        var docMap = new HashMap<String, Document>();

        // Vector search
        if (queryVector != null) {
            var knnQuery = new KnnFloatVectorQuery("embedding", queryVector, topK * 2);
            var knnHits = searcher.search(knnQuery, topK * 2);
            int rank = 1;
            for (var hit : knnHits.scoreDocs) {
                var doc = searcher.storedFields().document(hit.doc);
                var id = doc.get("id");
                vectorRanks.put(id, rank++);
                docMap.put(id, doc);
            }
        }

        // BM25 keyword search
        try (var analyzer = new SmartChineseAnalyzer()) {
            var parser = new QueryParser("content", analyzer);
            var parsed = parser.parse(QueryParser.escape(query));
            var bm25Hits = searcher.search(parsed, topK * 2);
            int rank = 1;
            for (var hit : bm25Hits.scoreDocs) {
                var doc = searcher.storedFields().document(hit.doc);
                var id = doc.get("id");
                bm25Ranks.put(id, rank++);
                docMap.put(id, doc);
            }
        }

        // RRF fusion
        var allIds = new HashSet<>(vectorRanks.keySet());
        allIds.addAll(bm25Ranks.keySet());

        var scored = new ArrayList<MemoryResult>();
        for (var id : allIds) {
            double score = 0;
            if (vectorRanks.containsKey(id)) {
                score += 1.0 / (RRF_K + vectorRanks.get(id));
            }
            if (bm25Ranks.containsKey(id)) {
                score += 1.0 / (RRF_K + bm25Ranks.get(id));
            }
            var doc = docMap.get(id);
            scored.add(new MemoryResult(id, doc.get("content"), score, Map.of()));
        }

        scored.sort(Comparator.comparingDouble(MemoryResult::score).reversed());
        return scored.subList(0, Math.min(topK, scored.size()));
    }
}
