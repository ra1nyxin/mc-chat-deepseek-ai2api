# mc-chat-deepseek-ai2api

让 Paper 服务器中的所有玩家直接通过聊天栏使用本机 AI2API 的 DeepSeek 模型。玩家发送以 `ai` 开头的消息即可提问，例如 `ai最近 GitHub 有什么热门仓库`、`aimc 马鞍怎么合成`。AI 的回答会以 `<deepseek>` 的聊天前缀广播给在线玩家。

## 特性

- 所有玩家都可使用，不设置每日额度或每玩家配额。
- 所有玩家和 AI 共用近期聊天上下文；每次请求默认附带最近 500 条记录。
- 单消费者队列依次处理请求，网络请求不会占用服务器主线程；默认最多容纳 256 个等待或执行中的请求，避免异常刷请求耗尽内存。
- 默认模型为 `deepseek-thinking-search`，默认上下文窗口预算为 512k，最大生成 8192 tokens。
- 模型的推理字段默认不发往游戏聊天，可在配置中开启。
- 回复会先转为 Minecraft 可读的纯文本，再从第一行开始每秒发送一行；同一时间只播报一段 AI 回复，避免多位玩家的回答交错。
- 管理员可执行 `/mc-chat-deepseek-ai2api reload` 安全重载配置；配置不合法时保留当前生效配置。

## 详细通信流程

```mermaid
flowchart TB
    classDef player fill:#e8f1ff,stroke:#376996,color:#102a43
    classDef paper fill:#eef7ef,stroke:#4d7c55,color:#1c3b25
    classDef queue fill:#fff4dc,stroke:#a66c12,color:#4d3000
    classDef network fill:#fbeaf0,stroke:#a4506a,color:#4b1225
    classDef output fill:#f1ebff,stroke:#7058a3,color:#2e1b52

    Player[玩家]:::player -->|发送聊天消息| Event[AsyncChatEvent]:::paper
    Event --> Plain[Adventure Component 转纯文本<br/>并去除首尾空白]:::paper
    Event -->|Paper 原生流程<br/>原始 ai问题 不会被取消| GameChat[游戏聊天栏]:::output
    Plain --> Trigger{消息是否以<br/>trigger-prefix 开头且有内容}:::paper

    subgraph Ingress[输入记录与主线程切换]
        direction TB
        Trigger -->|否| NormalTask[调度到 Paper 主线程<br/>记录普通玩家聊天]:::paper
        Trigger -->|是| Extract[移除前缀得到提问内容<br/>保留完整原始消息]:::paper
        Extract --> AiTask[调度到 Paper 主线程<br/>记录原始 ai消息]:::paper
        Death[玩家死亡事件<br/>仅记录服务器实际展示的死亡消息]:::paper --> History
        Advancement[玩家完成进度事件<br/>记录公告或进度名]:::paper --> History
        NormalTask --> History
        AiTask --> History
        History[(共享历史双端队列<br/>最多保留 500 条<br/>玩家消息、死亡、进度、AI 回复)]:::queue
    end

    subgraph Admission[AI 请求准入]
        direction TB
        AiTask --> Settings[读取当前配置快照<br/>模型、超时、上下文、输出限制]:::paper
        Settings --> Key{api-key 是否已配置}:::paper
        Key -->|否| MissingKey[仅通知提问玩家<br/>管理员尚未配置密钥]:::output
        Key -->|是| Capacity{等待和执行中的请求<br/>是否小于 max-queue-size}:::queue
        Capacity -->|否| QueueFull[仅通知提问玩家<br/>请求队列已满]:::output
        Capacity -->|是| Enqueue[请求入队<br/>保存玩家 UUID、问题、配置快照<br/>以及运行时服务器信息]:::queue
        Enqueue --> Position{前方是否已有请求}:::queue
        Position -->|是| Waiting[仅通知提问玩家<br/>显示前方等待数量]:::output
        Position -->|否| Wake[唤醒 Worker]:::queue
        Waiting --> Wake
    end

    subgraph WorkerFlow[单消费者虚拟线程]
        direction TB
        Wake --> Lock[取得 queueLock<br/>等待并取出队首请求]:::queue
        Lock --> Context[复制共享历史<br/>取配置指定的最近 N 条<br/>按字符预算从最旧记录开始裁剪]:::queue
        Context --> Prompt[组装 OpenAI messages<br/>系统提示和 Paper 版本说明<br/>运行时服务端、Java、在线人数<br/>历史记录和当前问题]:::paper
        Prompt --> Request[构造 JSON 请求<br/>model、max_tokens、stream false<br/>Bearer 密钥]:::network
        Request --> Http[Java HttpClient<br/>强制 HTTP/1.1<br/>按配置超时]:::network
    end

    Http -->|POST OpenAI Chat Completions| AI2API[本机 AI2API<br/>127.0.0.1:3000<br/>v1/chat/completions]:::network
    AI2API -->|模型调用与联网搜索| DeepSeek[DeepSeek 模型]
    DeepSeek -->|回答| AI2API
    AI2API -->|JSON 响应| Status{HTTP 状态是否为 2xx}:::network
    Status -->|否| ApiError[记录截断后的错误日志<br/>仅通知提问玩家]:::output
    Status -->|是| Parse[解析 choices 0 message<br/>content 和 reasoning_content]:::network
    Parse --> Reasoning{send-reasoning-to-chat<br/>是否开启}:::paper
    Reasoning -->|是| Combine[合并推理和最终回答]:::paper
    Reasoning -->|否| Final[只使用最终回答]:::paper
    Combine --> Render
    Final --> Render

    subgraph Output[安全输出与队列顺序]
        direction TB
        Render[Markdown 转 Minecraft 纯文本<br/>处理标题、强调、链接、引用、代码块和空行<br/>按 max-response-characters 截断]:::output
        Render --> SaveReply[主线程先记录完整 AI 回复到共享历史]:::queue
        SaveReply --> Split[按 max-chat-line-characters 分行<br/>每行保留 deepseek 前缀空间]:::output
        Split --> Timer[Paper 定时任务<br/>首行下一 tick，后续每 20 tick 一行]:::output
        Timer --> Broadcast[广播每一行<br/>deepseek 前缀]:::output
        Broadcast --> GameChat
        Timer --> Done{是否已发送最后一行}:::output
        Done -->|否| Timer
        Done -->|是| Complete[完成 Future<br/>Worker 才处理下一个请求]:::queue
        ApiError --> Complete
    end

    class DeepSeek network
```

## 请求时序

```mermaid
sequenceDiagram
    participant P as 玩家
    participant E as AsyncChatEvent
    participant M as Paper 主线程
    participant H as 共享历史
    participant Q as 单消费者队列
    participant W as 虚拟线程 Worker
    participant A as 本机 AI2API
    participant D as DeepSeek
    participant C as 游戏聊天栏

    P->>E: 发送 ai问题
    E-->>C: Paper 正常广播原始 ai问题
    E->>M: runTask 记录和提交请求
    M->>H: 保存完整原始消息
    M->>Q: 配置和容量校验后入队
    Q-->>W: 唤醒并交付队首请求
    W->>H: 复制和裁剪共享历史
    W->>A: HTTP/1.1 POST OpenAI messages
    A->>D: 调用模型，需要时联网搜索
    D-->>A: 最终回答和可选推理
    A-->>W: JSON choices 0 message
    W->>M: 主线程处理纯文本和分行
    M->>H: 保存 AI 完整回答
    loop 第一行下一 tick，之后每秒一行
        M-->>C: 广播 deepseek 单行消息
    end
    M-->>W: 最后一行发送完成
    W->>Q: 继续处理下一条请求
```

玩家的原始 `ai...` 消息不会被插件隐藏。队列一次只执行一条请求，并等待当前回答全部逐行发完后才开始下一条，因此共享上下文与聊天输出不会被并发回答打乱。

## 环境与兼容性

本项目按 Paper `26.2.build.87-stable` 和 Java 25 编译，使用 Paper 的 `AsyncChatEvent` 监听聊天，因此目标环境为 Paper 26.2 或提供该事件的兼容实现。它不以 Spigot 为目标，也不保证旧版 Paper、Spigot 或 Purpur 可直接运行。

每次调用都向模型附带当前服务端实现、Minecraft 版本标识、Java 版本和在线人数。默认系统提示特别说明 **Paper 26.2 是真实存在的当前版本标识，不是 Minecraft 1.26，也不是幻觉或笔误**，以减少模型对新版本信息的误判。

## 安装

将构建出的 JAR 放入服务端 `plugins/` 目录，首次启动后编辑：

`plugins/mc-chat-deepseek-ai2api/config.yml`

至少填写 `api-key`。默认接口地址为本机 AI2API：

`http://127.0.0.1:3000/v1/chat/completions`

其余字段均在配置文件中带有中文注释，包括可用模型、上下文、队列、超时和聊天输出长度。修改后执行：

```text
/mc-chat-deepseek-ai2api reload
```

该命令需要 `mc-chat-deepseek-ai2api.admin` 权限，默认仅 OP 具备。

## AI2API 请求格式

插件使用 OpenAI Chat Completions 兼容格式，向以下站点发送 `POST` 请求：

`http://127.0.0.1:3000/v1/chat/completions`

独立测试可使用：

```bash
curl http://127.0.0.1:3000/v1/chat/completions \
  -H "Authorization: Bearer $AI2API_KEY" \
  -H "Content-Type: application/json" \
  --data '{"model":"deepseek-thinking-search","messages":[{"role":"user","content":"你好"}]}'
```

仓库配置中的密钥仅为临时公开测试用途。使用自己的密钥时，请只保存在服务器本地配置或环境变量中，不要提交到代码仓库或公开文档。

## 构建

需要 Java 25 和 Maven：

```bash
mvn clean package
```

成品位于 `target/mc-chat-deepseek-ai2api-1.0.0.jar`。JAR 已内置并重定位 Gson，不会与其他插件的 Gson 版本冲突。
