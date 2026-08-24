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

## 详细请求时序

```mermaid
sequenceDiagram
    autonumber
    participant P as 玩家
    participant E as AsyncChatEvent
    participant M as Paper 主线程
    participant H as 共享历史
    participant Q as 单消费者队列
    participant W as 虚拟线程 Worker
    participant A as 本机 AI2API
    participant D as DeepSeek
    participant C as 游戏聊天栏

    Note over E,H: 死亡消息和进度公告也会独立写入共享历史，最多保留 500 条
    P->>E: 发送聊天消息
    E->>E: Component 转纯文本并去除首尾空白
    E-->>C: Paper 正常广播玩家原始消息，不隐藏 ai问题

    alt 普通聊天
        E->>M: runTask 记录普通消息
        M->>H: 追加玩家原始消息，超出 500 条时移除最旧记录
    else 只有 ai 前缀而没有提问正文
        E->>E: 不创建 AI 请求
    else ai 前缀加有效提问
        E->>E: 去除 trigger-prefix，得到问题内容
        E->>M: runTask 记录原始 ai消息并提交请求
        M->>H: 追加完整 ai消息，超出 500 条时移除最旧记录
        M->>M: 读取当前配置快照和运行时服务端信息
        alt api-key 为空
            M-->>P: 私聊提示管理员尚未配置密钥
        else api-key 已配置
            M->>Q: 取得 queueLock 并检查 pendingRequestCount
            alt 请求数达到 max-queue-size
                Q-->>M: 队列已满
                M-->>P: 私聊提示稍后再试
            else 队列仍有容量
                M->>Q: 保存 UUID、问题、配置快照和服务器信息，pending 加一
                M->>W: notifyAll 唤醒单个 Worker
                opt 前方已有等待或执行中的请求
                    M-->>P: 私聊显示前方等待数量
                end

                W->>Q: 等待队列非空后取出队首请求
                W->>H: 复制当前历史，取最近 history-message-count 条
                W->>W: 按上下文字符预算从最旧记录开始裁剪
                W->>W: 组合系统提示、Paper 26.2 说明、Java 版本、在线人数、历史和当前问题
                W->>A: HTTP/1.1 POST v1/chat/completions，Bearer 密钥，stream false
                Note right of A: 配置的 request-timeout-seconds 生效
                A->>D: 调用指定模型，必要时使用联网搜索
                D-->>A: 最终回答和可选 reasoning_content
                A-->>W: JSON HTTP 响应

                alt 网络异常、超时或非 2xx
                    W->>W: 记录截断后的错误日志
                    W->>M: runTask 通知提问玩家
                    M-->>P: 私聊连接或接口错误
                else JSON 无法解析或没有可显示内容
                    W->>M: runTask 通知提问玩家
                    M-->>P: 私聊返回内容不可用
                else 成功解析 choices 0 message
                    alt send-reasoning-to-chat 已开启且存在推理内容
                        W->>W: 合并推理和最终回答
                    else 默认设置
                        W->>W: 只保留最终回答
                    end
                    W->>W: 清理 Markdown 标题、强调、链接、引用、代码块和空行
                    W->>W: 按 max-response-characters 截断
                    W->>M: runTask 保存回复并启动逐行发送
                    M->>H: 追加完整 AI 回复，超出 500 条时移除最旧记录
                    M->>M: 按 max-chat-line-characters 分行并预留 deepseek 前缀
                    loop 首行在下一 tick，之后每 20 tick 一行
                        M-->>C: 广播一行 deepseek 消息
                    end
                    M-->>W: 最后一行发出后完成 Future
                end

                W->>Q: pendingRequestCount 减一，再等待下一条队首请求
            end
        end
    end
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
