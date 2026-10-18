package com.panqj.rag.web;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 网页版 RAG 的核心服务。
 *
 * 它和命令行版 Step3RagQA 做的是同一件事，只是换成了"常驻内存 + HTTP 接口"的形态：
 *
 *   ① 加载文档 → ② 切分 → ③ 向量化入库（InMemoryEmbeddingStore）
 *   ④ 提问时向量化问题，按余弦相似度召回 Top-N
 *   ⑤ 片段 + 问题拼进 Prompt，让模型「只依据资料回答」
 *
 * 相比命令行版多了两件事：
 *   - 向量库落盘（data/vector-store.json），重启服务不丢知识库
 *   - 支持运行时往知识库里加内容（粘贴文本 / 上传 .md .txt 文件）
 */
@org.springframework.stereotype.Service
public class RagService {

    private static final int MAX_SEGMENT_SIZE_CHARS = 300;
    private static final int OVERLAP_SIZE_CHARS = 30;
    private static final int MAX_RESULTS = 3;
    private static final double MIN_SCORE = 0.4;

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final EmbeddingModel embeddingModel;
    private final InMemoryEmbeddingStore<TextSegment> store;

    /** 已入库的文档名（只用于前端展示） */
    private final Set<String> docNames = ConcurrentHashMap.newKeySet();

    private final Path storeFile = Paths.get("data/vector-store.json");
    private final Path indexFile = Paths.get("data/documents.txt");

    /** 一条被召回的资料片段 */
    public record Citation(String source, double score, String text) {}

    /** 一次检索的结果：拼好的 Prompt + 命中的片段 */
    public record Retrieval(String prompt, List<Citation> citations) {}

    /** 知识库概况 */
    public record Stats(int documents, int segments) {}

    public RagService() throws IOException, URISyntaxException {
        String baseUrl = com.panqj.rag.Config.get("AI_BASE_URL", "https://api.siliconflow.cn/v1");
        String apiKey = com.panqj.rag.Config.required("AI_API_KEY");
        String chatModelName = com.panqj.rag.Config.get("AI_CHAT_MODEL", "Qwen/Qwen2.5-7B-Instruct");
        String embedModelName = com.panqj.rag.Config.get("AI_EMBED_MODEL", "BAAI/bge-m3");

        this.chatModel = OpenAiChatModel.builder()
                .baseUrl(baseUrl).apiKey(apiKey).modelName(chatModelName)
                .temperature(0.2)          // RAG 场景压低温漂，让模型忠实资料，别自由发挥
                .timeout(Duration.ofSeconds(90))
                .build();

        this.streamingChatModel = OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl).apiKey(apiKey).modelName(chatModelName)
                .temperature(0.2)
                .timeout(Duration.ofSeconds(90))
                .build();

        this.embeddingModel = OpenAiEmbeddingModel.builder()
                .baseUrl(baseUrl).apiKey(apiKey).modelName(embedModelName)
                // 不要写 .dimensions()：硅基流动的 bge-m3 不接受这个字段，会报 20015
                .timeout(Duration.ofSeconds(60))
                .build();

        // ---------- 知识库初始化 ----------
        Files.createDirectories(storeFile.getParent());
        if (Files.exists(storeFile) && Files.size(storeFile) > 0) {
            this.store = InMemoryEmbeddingStore.fromFile(storeFile);
            if (Files.exists(indexFile)) {
                Files.readAllLines(indexFile, StandardCharsets.UTF_8).stream()
                        .filter(l -> !l.isBlank())
                        .forEach(docNames::add);
            }
            System.out.println("[RAG] 已从磁盘恢复知识库，片段数：" + store.size());
        } else {
            this.store = new InMemoryEmbeddingStore<>();
            int n = loadBuiltinDocs();
            System.out.println("[RAG] 首次启动，已从 docs 目录导入 " + n + " 个片段");
        }
    }

    /** 加载 classpath 下 /docs 目录里的 .md，作为开箱即用的知识库 */
    private int loadBuiltinDocs() throws IOException, URISyntaxException {
        var url = RagService.class.getResource("/docs");
        if (url == null) return 0;
        Path docsDir = Paths.get(url.toURI());
        int total = 0;
        try (Stream<Path> files = Files.list(docsDir)) {
            List<Path> mdFiles = files.filter(p -> p.toString().endsWith(".md")).sorted().toList();
            for (Path p : mdFiles) {
                try {
                    total += ingest(p.getFileName().toString(), Files.readString(p, StandardCharsets.UTF_8));
                } catch (RuntimeException e) {
                    // 密钥无效或网络不通时不要阻断启动，知识库留空，网页里仍可手动添加
                    System.err.println("[RAG] 导入 " + p.getFileName() + " 失败（密钥或网络问题）：" + e.getMessage());
                }
            }
        }
        return total;
    }

    /** 把一段文本切片、向量化、入库，返回切出的片段数 */
    public synchronized int ingest(String docName, String text) {
        if (text == null || text.isBlank()) return 0;

        Document doc = Document.from(text, Metadata.from("name", docName));
        DocumentSplitter splitter = DocumentSplitters.recursive(MAX_SEGMENT_SIZE_CHARS, OVERLAP_SIZE_CHARS);
        List<TextSegment> segments = splitter.split(doc);

        List<Embedding> embeddings = new ArrayList<>(segments.size());
        for (TextSegment s : segments) {
            embeddings.add(embeddingModel.embed(s.text()).content());
        }
        List<String> ids = store.addAll(embeddings, segments);

        docNames.add(docName);
        persistIndex();
        System.out.println("[RAG] 入库：" + docName + " → " + segments.size() + " 个片段");
        return segments.size();
    }

    /** 上传文件入库（只支持 .md / .txt，UTF-8） */
    public int ingestFile(String filename, byte[] content) {
        if (filename == null || content == null || content.length == 0) return 0;
        String lower = filename.toLowerCase();
        if (!lower.endsWith(".md") && !lower.endsWith(".txt")) {
            throw new IllegalArgumentException("只支持 .md / .txt 文件");
        }
        return ingest(filename, new String(content, StandardCharsets.UTF_8));
    }

    /** 检索：返回拼好的 Prompt 和命中的片段 */
    public Retrieval retrieve(String question) {
        Embedding questionEmbedding = embeddingModel.embed(question).content();
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(questionEmbedding)
                .maxResults(MAX_RESULTS)
                .minScore(MIN_SCORE)
                .build();
        EmbeddingSearchResult<TextSegment> result = store.search(request);
        List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>(result.matches());
        // 注意：这里不能用方法引用 EmbeddingMatch::score，泛型推断不出来，要写显式 lambda（和命令行版一致）
        matches.sort(Comparator.comparingDouble((EmbeddingMatch<TextSegment> m) -> m.score()).reversed());

        List<Citation> citations = new ArrayList<>();
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < matches.size(); i++) {
            EmbeddingMatch<TextSegment> m = matches.get(i);
            String source = m.embedded().metadata().getString("name");
            if (source == null) source = "未知来源";
            citations.add(new Citation(source, m.score(), m.embedded().text().trim()));
            context.append("【资料 ").append(i + 1).append("】\n")
                   .append(m.embedded().text().trim())
                   .append("\n\n");
        }

        String prompt = """
                你是一个严谨的技术助教。请只依据下面提供的资料回答用户的问题。

                要求：
                1. 只能使用资料中的信息，不要凭自己的知识补充；
                2. 如果资料里没有答案，直接回答「资料中没有相关内容」，不要编；
                3. 回答用简洁的中文，控制在 200 字以内；
                4. 如果引用了资料，在句末用（资料 N）标注。

                ===== 资料开始 =====
                %s
                ===== 资料结束 =====

                用户问题：%s
                """.formatted(context, question);

        return new Retrieval(prompt, citations);
    }

    /** 流式回答：每来一个 token 回调一次 onToken，出错回调 onError，结束回调 onDone */
    public void streamAnswer(String prompt, Consumer<String> onToken, Consumer<Throwable> onError, Runnable onDone) {
        streamingChatModel.chat(prompt, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
                onToken.accept(partial);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                onDone.run();
            }

            @Override
            public void onError(Throwable error) {
                onError.accept(error);
            }
        });
    }

    /** 阻塞式回答（给命令行调试用） */
    public String answer(String prompt) {
        return chatModel.chat(prompt);
    }

    public Stats stats() {
        return new Stats(docNames.size(), store.size());
    }

    public Set<String> documentNames() {
        return new LinkedHashSet<>(docNames);
    }

    /** 清空知识库，并重新导入 docs 目录 */
    public synchronized void reset() throws IOException {
        store.removeAll();
        docNames.clear();
        try {
            loadBuiltinDocs();
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
    }

    private void persistIndex() {
        try {
            Files.write(indexFile, new ArrayList<>(docNames), StandardCharsets.UTF_8);
            store.serializeToFile(storeFile);
        } catch (IOException e) {
            // 落盘失败不影响使用，只是重启后会丢
            System.err.println("[RAG] 知识库落盘失败：" + e.getMessage());
        }
    }
}
