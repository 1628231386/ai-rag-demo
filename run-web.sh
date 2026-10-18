#!/bin/bash
# 网页版 RAG 助手启动脚本
#
#   用法： bash run-web.sh
#
# 启动前先设密钥（关掉窗口后失效）：
cd "F:/WorkBuddy/项目/ai-rag-demo"
export AI_API_KEY=sk-ddpdabqjsruheolfjmhygffrjghqcinscksvzvvqbncaqaor

bash run-web.sh

#   export AI_API_KEY=sk-ddpdabqjsruheolfjmhygffrjghqcinscksvzvvqbncaqaor
#   可选： export AI_CHAT_MODEL=Qwen/Qwen2.5-7B-Instruct
#         export AI_EMBED_MODEL=BAAI/bge-m3
#
# 启动后浏览器访问： http://localhost:8080

cd "$(dirname "$0")" || exit 1

CP="target/classes;$(cat cp.txt)"

java \
  -cp "$CP" \
  -Dfile.encoding=UTF-8 \
  -Dsun.stdout.encoding=UTF-8 \
  -Dsun.stderr.encoding=UTF-8 \
  -Dserver.port=8080 \
  com.panqj.rag.web.RagWebApplication
