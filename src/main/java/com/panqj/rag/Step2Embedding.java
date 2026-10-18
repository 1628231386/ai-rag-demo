package com.panqj.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.time.Duration;
import java.util.List;

/**
 * 第 2 步：Embedding（向量化）—— RAG 的核心原理，必须亲手看一遍。
 *
 * 这一步要想明白的事：
 *   1. 一段文字被模型变成一个固定长度的数字数组（比如 512 维），这就是「向量」
 *   2. 语义相近的两句话，它们的向量在空间里也挨得近（余弦相似度接近 1）
 *   3. 所以「找相关资料」这个动作，本质是「向量之间比距离」，不是关键词匹配
 *      —— 这就是为什么用户问「怎么防止缓存穿透」能检索到讲布隆过滤器的段落，
 *         尽管两个词一个字都不重合
 */
public class Step2Embedding {

    public static void main(String[] args) {
        String baseUrl = Config.get("AI_BASE_URL", "https://api.siliconflow.cn/v1");
        String apiKey  = Config.required("AI_API_KEY");
        String model   = Config.get("AI_EMBED_MODEL", "BAAI/bge-m3");

        EmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .timeout(Duration.ofSeconds(60))
                .build();

        List<String> texts = List.of(
                "Redis 缓存穿透是指查询一个根本不存在的数据，缓存和数据库都查不到。",
                "解决缓存穿透通常用布隆过滤器，或者把查不到的空值也缓存起来。",
                "今天的西安天气很好，适合出门散步。"
        );

        System.out.println("==============================================");
        System.out.println(" Embedding 模型：" + model);
        System.out.println("==============================================");

        Embedding[] vectors = new Embedding[texts.size()];
        for (int i = 0; i < texts.size(); i++) {
            Response<Embedding> resp = embeddingModel.embed(texts.get(i));
            vectors[i] = resp.content();
            float[] v = vectors[i].vector();
            System.out.printf("文本 %d → 向量维度 %d，前 5 个值：[%.4f, %.4f, %.4f, %.4f, %.4f]%n",
                    i + 1, v.length, v[0], v[1], v[2], v[3], v[4]);
            System.out.println("       原文：" + texts.get(i));
            System.out.println();
        }

        System.out.println("---------- 余弦相似度对比 ----------");
        System.out.printf("「缓存穿透问题」 vs 「布隆过滤器解法」 = %.4f  ← 语义相关%n",
                cosineSimilarity(vectors[0], vectors[1]));
        System.out.printf("「缓存穿透问题」 vs 「西安天气」       = %.4f  ← 完全无关%n",
                cosineSimilarity(vectors[0], vectors[2]));
        System.out.println();
        System.out.println("结论：语义相关的句子相似度明显高于无关句子。");
        System.out.println("      RAG 检索就是靠这个数值排序的，不是靠关键词。");
    }

    /** 余弦相似度：两个向量夹角的余弦值，范围 [-1,1]，越接近 1 表示方向越一致 */
    public static double cosineSimilarity(Embedding a, Embedding b) {
        float[] x = a.vector();
        float[] y = b.vector();
        double dot = 0.0, normX = 0.0, normY = 0.0;
        for (int i = 0; i < x.length; i++) {
            dot   += x[i] * y[i];
            normX += x[i] * x[i];
            normY += y[i] * y[i];
        }
        return dot / (Math.sqrt(normX) * Math.sqrt(normY));
    }
}
