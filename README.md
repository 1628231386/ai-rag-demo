# AI RAG Demo —— 从零跑通一个 Java 版文档问答助手

技术栈：JDK 17 + Maven 3.9.9 + LangChain4j 1.19.0 + OpenAI 兼容协议

---

## 一、环境说明（本机踩过的坑）

| 项 | 情况 |
|---|---|
| JDK | `F:\实战班\jar17\openjdk-17 35_windows-x64_bin\jdk-17`（17 版本，够用） |
| Maven | 原本坏了，已重装到 `C:\Users\Lenovo\devtools\apache-maven-3.9.9` |
| 阿里云镜像 | 已配到 `C:\Users\Lenovo\.m2\settings.xml`，依赖下载速度从几分钟降到几十秒 |
| Maven 启动 | Git Bash 下的 `bin/mvn` 脚本有路径转换 bug，**必须用 `C:\Users\Lenovo\devtools\mvnw.sh` 代替** |

**为什么 Git Bash 里 `mvn` 会报 `ClassNotFoundException: Launcher`？**
因为 Maven 的 shell 启动脚本在处理带空格的 Windows 路径（本机的 JDK 偏偏装在有空格的 F 盘目录下）时，传给 JVM 的 classpath 会被截错。`mvnw.sh` 直接跳过那个脚本，手工调用 `org.codehaus.plexus.classworlds.launcher.Launcher`，绕开了这个问题。

---

## 二、拿到 API Key

推荐 **硅基流动 SiliconFlow**，一个账号同时提供对话模型和 Embedding 模型，而且都有免费额度：

1. 打开 <https://cloud.siliconflow.cn> 注册（手机号即可）
2. 左侧「API 密钥」→「新建 API 密钥」，复制那个 `sk-` 开头的值
3. 免费可用模型（2026-09 实测）：
   - 对话：`Qwen/Qwen2.5-7B-Instruct`
   - Embedding：`BAAI/bge-m3`，**1024 维**，中文效果极佳

> 注意一：**DeepSeek 官方不提供 Embedding 接口**，所以纯用 DeepSeek 做不了 RAG。选硅基流动是为了一步到位。
>
> 注意二：`BAAI/bge-small-zh-v1.5` 已下线，调用会报 `Model does not exist`。
>
> 注意三：**不要给 OpenAiEmbeddingModel 传 `.dimensions()`**。LangChain4j 会把它写进请求体，硅基流动不接受这个字段，会报 `20015 The parameter is invalid`。不传反而正常——框架直接用接口返回的 1024 维向量。

---

## 三、运行

```bash
cd F:/WorkBuddy/项目/ai-rag-demo

# 设密钥（每个新窗口要设一次）
export AI_API_KEY=sk-你的密钥

# ① 验证链路通不通
bash run.sh Step1HelloChat

# ② 看向量化到底在干什么
bash run.sh Step2Embedding

# ③ 完整 RAG 问答（会先建库，再进交互模式）
bash run.sh Step3RagQA
```

---

## 四、三个例子分别学什么

### Step1HelloChat —— 接入大模型
搞清楚 `baseUrl + apiKey + modelName` 三件套。所谓「接入大模型」本质就是一次 HTTP POST，框架帮你封装了请求体。`temperature` 控制随机性，问答场景要压到 0.2~0.3。

### Step2Embedding —— RAG 的核心原理
一段文字 → 一个 512 维的数字数组。语义相近的句子，向量在空间里也挨得近（余弦相似度接近 1）。
**这就是为什么用户问「怎么防止缓存穿透」能检索到讲布隆过滤器的段落**——两个词一个字都不重合，但语义相关。

### Step3RagQA —— 完整链路手写实现
```
① 加载文档 → ② 切分成片段 → ③ 片段向量化存入向量库
→ ④ 把问题也向量化，按余弦相似度召回最相关的 N 段
→ ⑤ 资料 + 问题一起塞进 Prompt，让模型「只依据资料回答」
```

关键参数：
- `MAX_SEGMENT_SIZE_CHARS = 300`：片段太大 → prompt 噪音多、token 贵；太小 → 语义被切断
- `OVERLAP_SIZE_CHARS = 30`：相邻片段重叠，避免一句话正好被切成两半
- `maxResults = 3`：只取最相关的 3 段
- `minScore = 0.4`：相似度低于阈值直接丢掉，避免噪音污染答案

---

## 五、为什么 RAG 是企业里最常见的大模型落地方式

因为大模型的知识有截止日期，也不知道你公司的内部资料。微调（Fine-tune）确实能注入知识，但成本高、迭代慢、还要准备大量标注数据。RAG 只是在回答问题前，先把可能相关的资料翻出来塞给它——**便宜、即时、可解释**，而且换资料库的当天就能生效。

这也是为什么 JD 里「关注大模型在后端架构中的应用」这类要求，实际落地 90% 是 RAG。

---

## 六、网页版（2026-10-18 升级）

同一个 RAG 内核，包了一层 Spring Boot + Spring MVC，可以在浏览器里用。

```bash
cd F:/WorkBuddy/项目/ai-rag-demo

# 编译（改了 pom，加了 spring-boot-starter-web 2.7.18）
bash "C:/Users/Lenovo/devtools/mvnw.sh" compile
bash "C:/Users/Lenovo/devtools/mvnw.sh" dependency:build-classpath -Dmdep.outputFile=cp.txt

# 启动（必须带真密钥）
export AI_API_KEY=sk-你的密钥
bash run-web.sh

# 浏览器打开
#   http://localhost:8080
```

### 网页版能做什么

| 功能 | 说明 |
|---|---|
| 粘贴文本入库 | 左侧文本框 → 加入知识库，自动切片 + 向量化 |
| 上传文件入库 | 支持 `.md` / `.txt`（UTF-8），PDF 会拒绝并提示 |
| 流式问答 | SSE 推送，答案一个字一个字往外蹦 |
| 引用溯源 | 每次回答前先展示命中的片段、来源文档、相似度分数 |
| 知识库持久化 | 存到 `data/vector-store.json`，重启不丢 |
| 清空重建 | 一键清空并重新导入 `docs/` 目录 |

### 网页版代码在哪

```
src/main/java/com/panqj/rag/web/
├── RagWebApplication.java   启动类（Spring Boot）
├── RagService.java          RAG 内核：切片/向量化/入库/检索/流式生成
└── RagController.java       HTTP 接口 + SSE 推送
src/main/resources/static/index.html   前端单页（原生 fetch 手搓，没引框架）
```

### HTTP 接口一览

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/api/stats` | 文档数、片段数 |
| GET | `/api/documents` | 已入库的文档名 |
| POST | `/api/ingest` | 粘贴文本入库（form: `name`, `text`） |
| POST | `/api/upload` | 上传文件入库（multipart: `file`） |
| POST | `/api/chat` | SSE 流式问答（form: `question`） |
| POST | `/api/clear` | 清空并重建知识库 |

`/api/chat` 的事件顺序：`citations`（命中片段）→ `token`（流式文本）→ `done`。

### 两处踩坑记录

1. **方法引用踩泛型**：`matches.sort(Comparator.comparingDouble(EmbeddingMatch::score)...)` 编译不过，类型推断不出来，必须写显式 lambda `(EmbeddingMatch<TextSegment> m) -> m.score()`。
2. **Git Bash 下 `mvn` 脚本有 bug**（JDK 路径带空格时 classpath 被截断），必须用 `C:/Users/Lenovo/devtools/mvnw.sh` 代替，原因见第一节。

### 简历上怎么写这条

别写"了解 RAG"，写这句（等你用真密钥跑通、并加过自己的资料之后）：

> 独立完成基于 Spring Boot + LangChain4j 的知识库问答系统：实现文档切片、向量化入库、相似度召回与 Prompt 拼装，前端通过 SSE 实现流式输出与引用溯源；向量库存档落盘，支持重启恢复。

