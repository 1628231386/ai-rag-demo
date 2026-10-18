package com.panqj.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Scanner;

/**
 * 第 3 步：完整 RAG 链路，每一步都手写展开。
 *
 * 整条链路只有 5 步，面试照着说就行：
 *
 *   ① 加载文档   ② 切分成片段   ③ 片段向量化存入向量库
 *   ④ 把用户问题也向量化，去库里按余弦相似度召回最相关的 N 个片段
 *   ⑤ 把片段拼进 Prompt 一起丢给大模型，让它「只依据这些资料回答」
 *
 * 为什么要这样做 —— 因为大模型的知识有截止日期、也不知道你公司的内部资料。
 * RAG 做的事情就是：回答问题前，先把可能相关的资料翻出来塞给它。
 * 这也是企业里落地最多的大模型用法，比微调便宜得多。
 */
public class Step3RagQA {

    /** 每个片段的最大字符数。太大 → 塞进 prompt 的噪音多、token 贵；太小 → 语义被切断 */
    private static final int MAX_SEGMENT_SIZE_CHARS = 300;
    /** 相邻片段之间重叠的字符数，用来避免一句话正好被切成两半导致语义丢失 */
    private static final int OVERLAP_SIZE_CHARS = 30;

    public static void main(String[] args) {
        String baseUrl = Config.get("AI_BASE_URL", "https://api.siliconflow.cn/v1");
        String apiKey  = Config.required("AI_API_KEY");
        String chatModelName  = Config.get("AI_CHAT_MODEL", "Qwen/Qwen2.5-7B-Instruct");
        String embedModelName = Config.get("AI_EMBED_MODEL", "BAAI/bge-m3");

        ChatModel chatModel = OpenAiChatModel.builder()
                .baseUrl(baseUrl).apiKey(apiKey).modelName(chatModelName)
                .temperature(0.2)                       // RAG 场景要压得很低，让模型忠实于资料，别自由发挥
                .timeout(Duration.ofSeconds(90))
                .build();

        EmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
                .baseUrl(baseUrl).apiKey(apiKey).modelName(embedModelName)
                // 注意：这里不要写 .dimensions()
                //   硅基流动的 bge-m3 不接受请求体里的 dimensions 字段，写了会报 20015 参数错误，
                //   框架会直接用接口返回的 1024 维向量，效果一样。
                .timeout(Duration.ofSeconds(60))
                .build();

        System.out.println("==============================================");
        System.out.println(" ① 加载文档：docs 目录下的所有 .md 文件");
        System.out.println("==============================================");
        List<Document> documents = FileSystemDocumentLoader.loadDocuments(Path.of("src/main/resources/docs"));
        System.out.println("    共加载 " + documents.size() + " 篇文档");

        System.out.println();
        System.out.println("==============================================");
        System.out.println(" ② 切分：每片 " + MAX_SEGMENT_SIZE_CHARS + " 字，重叠 " + OVERLAP_SIZE_CHARS + " 字");
        System.out.println("==============================================");
        DocumentSplitter splitter = DocumentSplitters.recursive(MAX_SEGMENT_SIZE_CHARS, OVERLAP_SIZE_CHARS);
        List<TextSegment> segments = new ArrayList<>();
        for (Document doc : documents) {
            segments.addAll(splitter.split(doc));
        }
        System.out.println("    共切出 " + segments.size() + " 个片段");

        System.out.println();
        System.out.println("==============================================");
        System.out.println(" ③ 向量化入库（InMemoryEmbeddingStore）");
        System.out.println("==============================================");
        long t0 = System.currentTimeMillis();
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        for (TextSegment segment : segments) {
            Embedding embedding = embeddingModel.embed(segment.text()).content();
            store.add(embedding, segment);
        }
        System.out.println("    入库完成，耗时 " + (System.currentTimeMillis() - t0) + " ms");

        System.out.println();
        System.out.println("==============================================");
        System.out.println(" 知识库就绪，可以提问了（输入 exit 退出）");
        System.out.println("==============================================");

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("\n你的问题 > ");
                String question = scanner.nextLine().trim();
                if (question.isEmpty()) continue;
                if ("exit".equalsIgnoreCase(question) || "退出".equals(question)) break;

                long start = System.currentTimeMillis();

                System.out.println("  ④ 检索：把问题向量化，召回最相关的片段");
                Embedding questionEmbedding = embeddingModel.embed(question).content();
                EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                        .queryEmbedding(questionEmbedding)
                        .maxResults(3)          // 只取最相关的 3 段
                        .minScore(0.4)          // 相似度低于这个阈值的直接丢掉，避免噪音
                        .build();
                EmbeddingSearchResult<TextSegment> searchResult = store.search(searchRequest);
                List<EmbeddingMatch<TextSegment>> matches = searchResult.matches();

                if (matches.isEmpty()) {
                    System.out.println("     没有找到相关资料（相似度都低于阈值）。试试换个问法。");
                    continue;
                }

                List<EmbeddingMatch<TextSegment>> sorted = new ArrayList<>(matches);
                sorted.sort(Comparator.comparingDouble((EmbeddingMatch<TextSegment> m) -> m.score()).reversed());

                StringBuilder context = new StringBuilder();
                for (int i = 0; i < sorted.size(); i++) {
                    EmbeddingMatch<TextSegment> match = sorted.get(i);
                    System.out.printf("     片段 %d  相似度 %.4f%n", i + 1, match.score());
                    context.append("【资料 ").append(i + 1).append("】\n")
                           .append(match.embedded().text().trim())
                           .append("\n\n");
                }

                String prompt = """
                        你是一个严谨的技术助教。请只依据下面提供的资料回答用户的问题。

                        要求：
                        1. 只能使用资料中的信息，不要凭自己的知识补充；
                        2. 如果资料里没有答案，直接回答「资料中没有相关内容」，不要编；
                        3. 回答用简洁的中文，控制在 200 字以内。

                        ===== 资料开始 =====
                        %s
                        ===== 资料结束 =====

                        用户问题：%s
                        """.formatted(context.toString(), question);

                System.out.println("  ⑤ 生成：把资料 + 问题一起交给大模型");
                String answer = chatModel.chat(prompt);

                System.out.println();
                System.out.println("【回答】");
                System.out.println(answer);
                System.out.println("\n本轮总耗时：" + (System.currentTimeMillis() - start) + " ms");
            }
        }
        System.out.println("已退出。");
    }
}
