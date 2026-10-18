package com.panqj.rag.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 网页版 RAG 助手入口。
 *
 * 启动后访问：http://localhost:8080
 *
 * 注意：启动前必须设置 AI_API_KEY，否则构造 RagService 时就会失败：
 *   export AI_API_KEY=sk-你的密钥
 */
@SpringBootApplication
public class RagWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagWebApplication.class, args);
        System.out.println();
        System.out.println("=====================================================");
        System.out.println("  RAG 助手已启动 → http://localhost:8080");
        System.out.println("  按 Ctrl+C 停止");
        System.out.println("=====================================================");
    }
}
