package com.panqj.rag.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * RAG 助手的 HTTP 接口。
 *
 * 接口一览（前端 index.html 全部在用）：
 *   GET  /api/stats       知识库概况（几篇文档、几个片段）
 *   GET  /api/documents   已入库的文档名
 *   POST /api/ingest      粘贴文本入库        （form: name, text）
 *   POST /api/upload      上传 .md/.txt 入库  （multipart: file）
 *   POST /api/chat        SSE 流式问答        （form: question）
 *   POST /api/clear       清空并重建知识库
 */
@RestController
public class RagController {

    private final RagService rag;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RagController(RagService rag) {
        this.rag = rag;
    }

    @GetMapping("/api/stats")
    public Map<String, Object> stats() {
        RagService.Stats s = rag.stats();
        return Map.of("documents", s.documents(), "segments", s.segments());
    }

    @GetMapping("/api/documents")
    public List<String> documents() {
        return List.copyOf(rag.documentNames());
    }

    @PostMapping("/api/ingest")
    public Map<String, Object> ingest(@RequestParam String name,
                                      @RequestParam String text) {
        try {
            int n = rag.ingest(name.isBlank() ? "未命名文档" : name, text);
            return Map.of("ok", true, "segments", n, "stats", rag.stats());
        } catch (Exception e) {
            // 密钥无效 / 网络不通时，把原因带回前端，别只甩一个 500
            return Map.of("ok", false, "message", "入库失败（密钥或网络问题）：" + e.getMessage());
        }
    }

    @PostMapping("/api/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) {
        try {
            int n = rag.ingestFile(file.getOriginalFilename(), file.getBytes());
            return Map.of("ok", true, "segments", n, "stats", rag.stats());
        } catch (IllegalArgumentException e) {
            return Map.of("ok", false, "message", e.getMessage());
        } catch (Exception e) {
            return Map.of("ok", false, "message", "上传失败（密钥或网络问题）：" + e.getMessage());
        }
    }

    @PostMapping("/api/clear")
    public Map<String, Object> clear() {
        try {
            rag.reset();
            return Map.of("ok", true, "stats", rag.stats());
        } catch (Exception e) {
            return Map.of("ok", false, "message", "清空失败：" + e.getMessage());
        }
    }

    /**
     * 流式问答（SSE）。
     *
     * 事件顺序：
     *   event: citations  → 先告诉前端检索到了哪些片段（带相似度）
     *   event: token      → 一个字一个字往外蹦
     *   event: done       → 结束
     */
    @PostMapping(value = "/api/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestParam String question) {
        // 0 = 不超时，由前端控制
        SseEmitter emitter = new SseEmitter(0L);

        new Thread(() -> {
            try {
                if (question == null || question.isBlank()) {
                    emitter.send(SseEmitter.event().name("error").data("问题不能为空"));
                    emitter.complete();
                    return;
                }

                // ① 检索（这一步是阻塞的，可能要点时间）
                RagService.Retrieval retrieval = rag.retrieve(question);
                emitter.send(SseEmitter.event()
                        .name("citations")
                        .data(objectMapper.writeValueAsString(retrieval.citations())));

                if (retrieval.citations().isEmpty()) {
                    emitter.send(SseEmitter.event().name("token")
                            .data("知识库里没有找到相关内容（相似度都低于阈值）。换个问法，或者先往知识库里加点资料。"));
                    emitter.send(SseEmitter.event().name("done").data(""));
                    emitter.complete();
                    return;
                }

                // ② 流式生成
                rag.streamAnswer(
                        retrieval.prompt(),
                        token -> {
                            try {
                                emitter.send(SseEmitter.event().name("token").data(token));
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        },
                        error -> {
                            try {
                                emitter.send(SseEmitter.event().name("error")
                                        .data("生成失败：" + error.getMessage()));
                            } catch (Exception ignored) {
                            } finally {
                                emitter.complete();
                            }
                        },
                        () -> {
                            try {
                                emitter.send(SseEmitter.event().name("done").data(""));
                            } catch (Exception ignored) {
                            } finally {
                                emitter.complete();
                            }
                        });
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data("服务异常：" + e.getMessage()));
                } catch (Exception ignored) {
                } finally {
                    emitter.complete();
                }
            }
        }, "rag-chat-thread").start();

        return emitter;
    }
}
