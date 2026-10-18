#!/bin/bash
# RAG demo 启动脚本
#   用法： bash run.sh Step1HelloChat
#         bash run.sh Step2Embedding
#         bash run.sh Step3RagQA
#
# 跑 Step1/2/3 之前，先设置密钥（只需设一次，关掉窗口后失效）：
#   export AI_API_KEY=sk-xxxxxxxx
#   可选： export AI_CHAT_MODEL=Qwen/Qwen2.5-7B-Instruct
#         export AI_EMBED_MODEL=BAAI/bge-small-zh-v1.5

cd "$(dirname "$0")" || exit 1

CLASS_NAME=${1:-Step1HelloChat}
CP="target/classes;$(cat cp.txt)"

java \
  -cp "$CP" \
  -Dfile.encoding=UTF-8 \
  -Dsun.stdout.encoding=UTF-8 \
  -Dsun.stderr.encoding=UTF-8 \
  "com.panqj.rag.$CLASS_NAME"
