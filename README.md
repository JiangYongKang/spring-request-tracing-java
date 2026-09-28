# Spring Request Tracing (Java Bootstrap)

一套贯穿 **同步请求 / 异步任务 / 出站调用** 的请求追踪与可观测能力。
目标：一次请求从进入到结束，无论走哪条路径、在哪一层失败，都能用同一个
追踪标识（trace id）稳定、可复现地回查它经过的关键节点。

## 能力一览

| 需求 | 实现 |
| --- | --- |
| 每个请求唯一标识并绑定本次处理 | `TraceFilter` 在 REQUEST 入口生成/接收 id，`TraceContext` 用 `ThreadLocal` 绑定，SLF4J `MDC` 让每行日志带 `traceId` |
| 同步 / 异步 / 出站同一标识 | 异步线程池 `TaskDecorator` 显式交接；出站 `RestClient` 拦截器写 `X-Trace-Id` 请求头 |
| 并发不串号、不污染 | 使用非继承型 `ThreadLocal`，线程池任务执行后强制 `unbind`；注册表以每请求唯一的内部 `requestId` 为键，与可跨跳共享的 `traceId` 解耦 |
| 标识不无限堆积 | 完成态追踪保存在**定容 FIFO**（默认 200，`tracing.recent-capacity`），满了淘汰最旧；在途表在成功/异常/超时/异步完成的每条路径上都移除条目 |
| 异常 / 超时 / 中途失败不丢标识 | 过滤器 `finally`、`@RestControllerAdvice`、Servlet `AsyncListener`（`onComplete/onError/onTimeout`）多处记录；收尾幂等，不会漏记或重复计数 |
| 健康与指标对外可查 | Actuator：`/actuator/health`、`/actuator/metrics`；业务口径：`/observability/metrics`、`/observability/traces/{id}` |
| 指标在并发下一致 | 计数用 `AtomicLong`，在途用并发 Map 且完成即移除；观测端点自身不计入业务指标 |

## 关键概念

- **traceId（对外追踪标识）**：32 位小写十六进制（去横线的 UUID）。随
  `X-Trace-Id` 请求头 / 响应头传播，同一调用链（含被调服务、出站调用、异步
  任务）共享。允许上游传入，但必须匹配 `[A-Za-z0-9._-]{8,64}` 才被采纳，
  否则忽略并重新生成，防止脏数据注入日志。
- **requestId（内部请求标识）**：每个入站请求一个独立 UUID，仅用作注册表
  在途条目的键。这样出站调用打到自身、或下游带着同一 traceId 回调时，嵌套
  请求不会覆盖外层请求的在途状态。
- **TraceState / TraceEvent**：一次请求的可变状态与不可变关键节点
  （`request-start`、`controller-*`、`async-started`、`async-step-*`、
  `async-timeout`、`outbound-start/end/error`、`request-complete/failed` 等）。
  事件列表为 `CopyOnWriteArrayList`，工作线程追加与查询线程读取互不干扰。

## 标识生成与传递规则

```
入站请求
  └─ TraceFilter(REQUEST)
       traceId = 合法的 X-Trace-Id 请求头 ? 采用 : 新生成 UUID(32 hex)
       TraceState(traceId, requestId) -> registry.start()
       TraceContext.bind(...) + MDC.put("traceId", traceId)
       response.setHeader("X-Trace-Id", traceId)
同步处理：   同一线程，MDC / ThreadLocal 全程可用
异步处理：   @Async 方法提交时由 traceTaskDecorator 抓取 TraceState 快照，
             工作线程执行前 bind、finally 中 unbind；CompletableFuture 完成后
             容器 ASYNC 再分派时过滤器重新 bind，同一 id 不断线
出站调用：   TraceClientHttpRequestInterceptor 读取当前 traceId 写入
             X-Trace-Id 请求头，并记录 outbound-* 节点
请求结束：   同步 finally / AsyncListener.onComplete 统一 finish()
```

线程绑定刻意使用**非继承型** ThreadLocal + 显式交接，而不是
`InheritableThreadLocal`：线程池中的线程会被复用，隐式继承会让上一个任务的
标识泄漏到下一个任务。`runWith/callWith` 在 `finally` 中恢复/清空绑定，
即使任务抛异常也不会留下残痕。

## 失败链路上如何保留标识

1. **控制器抛异常（同步）**：异常冒泡到过滤器时被 `catch` 记录
   `filter-error`；同时 `@RestControllerAdvice` 在 id 仍绑定时记录
   `controller-error`；`finally` 解绑，随后统一 `finish()`，响应头仍带 id。
2. **异步任务失败**：工作线程上的 id 来自装饰器；`onError` 记录
   `async-error`，`onComplete` 收尾，失败计入失败数。
3. **异步超时**：`onTimeout` 记录 `async-timeout` 并打日志，容器随后完成
   请求，`onComplete` 仍会把追踪归档（超时阈值由
   `spring.mvc.async.request-timeout` 配置，默认 2000ms）。
4. **出站调用失败**：拦截器 `catch` 到 IOException/RuntimeException 时记录
   `outbound-error` 再原样抛出，链路可见失败节点。
5. **收尾幂等**：`TraceRegistry.complete` 以在途表移除是否成功为准，错误 +
   完成回调同时到达也只计数一次。

观测路径（`/observability/**`、`/actuator/**`）同样被追踪，但**不计入**
业务指标，避免“查询指标”这个动作本身改变指标。

## 端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/demo/sync` | 同步示例 |
| GET | `/demo/async` | 异步示例（工作线程同 id） |
| GET | `/demo/async-error` | 异步任务抛异常 |
| GET | `/demo/async-timeout?millis=5000` | 异步超时 |
| GET | `/demo/error` | 同步抛异常 |
| GET | `/demo/outbound?url=...` | 出站调用；不带 url 时回调自身 `/demo/sync` |
| GET | `/observability/metrics` | `totalRequests` / `failedRequests` / `inFlight` |
| GET | `/observability/traces` | 最近保留的 trace id 列表（有界） |
| GET | `/observability/traces/{traceId}` | 某次请求经过的全部关键节点 |
| GET | `/actuator/health` | 健康检查 |
| GET | `/actuator/metrics` | Micrometer 指标索引（含 HTTP、线程池等） |

## 本地验证

```bash
# 运行全部测试（21 个，含并发/异步/异常/超时/出站端到端用例）
mvn test

# 启动应用（默认随机端口；可用 --server.port 固定）
mvn spring-boot:run
# 或
mvn package -DskipTests
java -jar target/spring-request-tracing-java-1.0-SNAPSHOT.jar --server.port=18091

# 另开终端
curl -i http://localhost:18091/demo/sync                       # 看响应头 X-Trace-Id
curl -i -H 'X-Trace-Id: my-caller-id-1234' \
     http://localhost:18091/demo/sync                          # 上游 id 被采纳
curl -s http://localhost:18091/demo/async                     # 异步同 id
curl -s -i http://localhost:18091/demo/error                  # 500 且带 id
curl -s http://localhost:18091/demo/outbound                  # 嵌套请求同 id
curl -s http://localhost:18091/observability/metrics          # 累计/在途计数
curl -s http://localhost:18091/observability/traces/<上面拿到的id> | jq .
curl -s 'http://localhost:18091/demo/async-timeout?millis=5000'  # 2s 后超时
```

控制台日志每行均带 trace id，便于按 id 串联一次请求：

```
11:13:26.333 INFO [trace-async-1] traceId=09e5...9e52f DemoAsyncService - async step begin ...
11:13:26.403 INFO [http-nio-...-2]  traceId=09e5...9e52f TraceFilter     - request finished status=200 nodes=7
```

## 测试如何证明“不串号、不丢标识、不失真”

- `TraceIdsTest`：万次生成无重复；上游 id 合法则采纳、非法则拒绝。
- `TraceContextTest`：bind/unbind 与 MDC 联动；抛异常后恢复外层绑定；
  线程池任务结束后线程上无残留 id。
- `TraceRegistryTest`：计数与失败数正确；FIFO 定容淘汰；16 线程 × 500
  请求并发起止后总数/失败数精确一致、在途归零；重复完成只算一次。
- `RequestTracingIntegrationTest`（真实 Tomcat + HTTP）：
  - 同步/异步/异步异常/同步异常/出站/超时全链路断言 id 与节点；
  - 40 并发 × 25 轮共 1000 个混合请求：每个外层请求 id 唯一、响应体与响应头
    一致、失败与总数增量精确、在途归零、归档不超过容量；
  - 出站调用嵌套请求携带同一个 id。

## 配置项

| 属性 | 默认值 | 说明 |
| --- | --- | --- |
| `server.port` | `0`（随机端口） | 监听端口 |
| `spring.mvc.async.request-timeout` | `2000` | 异步请求超时（毫秒） |
| `tracing.recent-capacity` | `200` | 保留的已完成追踪数量上限（FIFO） |
