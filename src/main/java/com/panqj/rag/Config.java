package com.panqj.rag;

/**
 * 统一读取配置。优先读系统环境变量，其次读 JVM -D 参数，最后用默认值。
 * 这样 API Key 不用硬编码进源码里（避免误传 GitHub 泄露）。
 */
public class Config {

    public static String get(String key) {
        return get(key, null);
    }

    public static String get(String key, String defaultValue) {
        String v = System.getenv(key);
        if (v != null && !v.isBlank()) {
            return v.trim();
        }
        v = System.getProperty(key);
        if (v != null && !v.isBlank()) {
            return v.trim();
        }
        return defaultValue;
    }

    /** 必填项，缺失时给出明确提示而不是抛空指针 */
    public static String required(String key) {
        String v = get(key);
        if (v == null) {
            throw new IllegalStateException(
                "缺少环境变量 " + key + "。\n" +
                "  Git Bash 里执行：  export " + key + "=你的密钥\n" +
                "  Windows CMD 里执行： set " + key + "=你的密钥"
            );
        }
        return v;
    }
}
