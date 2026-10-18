package com.panqj.rag;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.time.Duration;

/**
 * 第 1 步：验证链路通不通 —— 直接和大模型说一句话。
 *
 * 这一步要搞明白的事：
 *   1. baseUrl + apiKey + modelName 三件套构成了「调用任何 OpenAI 兼容大模型」的全部入参
 *   2. 所谓「接入大模型」本质上就是一次 HTTP POST，框架帮你封装了请求体
 *   3. temperature 控制随机性：问答场景要低（0.1~0.3），生成场景可以高
 */
public class Step1HelloChat {

    public static void main(String[] args) {
        String baseUrl = Config.get("AI_BASE_URL", "https://api.siliconflow.cn/v1");
        String apiKey  = Config.required("AI_API_KEY");
        String model   = Config.get("AI_CHAT_MODEL", "Qwen/Qwen2.5-7B-Instruct");

        System.out.println("==============================================");
        System.out.println(" baseUrl : " + baseUrl);
        System.out.println(" model   : " + model);
        System.out.println(" key     : " + maskKey(apiKey));
        System.out.println("==============================================");

        ChatModel chatModel = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .temperature(0.3)          // 问答场景压低随机性，让答案更稳定
                .timeout(Duration.ofSeconds(90))
                .logRequests(true)         // 跑通后建议关掉
                .logResponses(true)
                .build();

        String question = "请用两句话解释：什么是向量数据库？它对 Java 后端程序员有什么用？";
        System.out.println("[提问] " + question);
        System.out.println();

        long start = System.currentTimeMillis();
        String answer = chatModel.chat(question);
        long cost = System.currentTimeMillis() - start;

        System.out.println("[回答] " + answer);
        System.out.println();
        System.out.println("耗时：" + cost + " ms");
    }

    private static String maskKey(String key) {
        if (key.length() <= 8) {
            return "****";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
}
