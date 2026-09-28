# Spring Request Tracing

一套贯穿**同步请求 → 异步任务 → 出站调用**的请求追踪与可观测能力，保证一次请求从进入到结束的链路结论稳定、可复现。

## 追踪标识的生成与传递规则

追踪标识（traceId）是每次请求的唯一 ID，通过 HTTP 头 `X-Trace-Id` 跨进程传递。

1. **生成**：请求进入时由 `TraceIdFilter`（最高优先级 servlet 过滤器）处理——
   - 调用方带了 `X-Trace-Id` 且非空、长度 ≤ 128：沿用该值（支持上游串联）；
   - 否则生成新的 UUID。
2. **绑定**：traceId 存入 `TraceContext`（`ThreadLocal`）并写入 SLF4J MDC（key=`traceId`），
   同时回写到响应头 `X-Trace-Id`，调用方总能拿到本次请求的标识。
3. **同步链路**：同一线程内通过 `TraceContext.currentTraceId()` 读取；日志格式
   （`application.properties` 的 `logging.pattern.console`）中每行都带 `[traceId=...]`。
4. **异步链路**：`AsyncConfig` 的线程池装配了 `TraceTaskDecorator`，任务提交时捕获
   当前 traceId，在 worker 线程执行前重新绑定（含 MDC），执行后在 `finally` 中恢复/清理，
   池化线程不会把旧标识带进下一个任务。
5. **出站链路**：`RestTemplate` 装配 `TraceClientHttpRequestInterceptor`，自动把当前
   traceId 写入出站请求的 `X-Trace-Id` 头（下游已有该头则不覆盖），下游服务可接续同一链路。

## 并发隔离与内存安全

- traceId 存于 `ThreadLocal`，并发请求各自持有独立副本，互不串号（有并发测试验证）。
- `TraceIdFilter` 在 `finally` 中执行 `TraceContext.clear()` + `MDC.remove()`，
  请求结束（无论成功、异常还是超时）即释放，标识不会在池化线程或内存中堆积。

## 失败链路上的标识保留

- 过滤器在进入业务前就已绑定 traceId 并写入响应头，**任何一层抛错都不会丢失标识**。
- 业务异常由 `TraceExceptionHandler` 统一捕获：错误日志带 traceId，错误响应体也回显
  `traceId` 字段，可凭它回查这次请求经过的所有关键节点日志（按 `traceId=xxx` grep 即可）。
- 异步任务内部异常被捕获时同样先记录带 traceId 的日志，标识不断链。
- 过滤器对 5xx 响应与抛出的异常都计入失败指标，指标统计在 `finally` 中完成。

## 健康与指标端点

| 端点        | 内容                                                         |
|-------------|--------------------------------------------------------------|
| `GET /health`  | `status` 与当前在途请求数 `inFlight`                        |
| `GET /metrics` | `totalRequests`（累计）、`failedRequests`（失败）、`inFlightRequests`（在途） |

计数基于 `AtomicLong`，增减在过滤器的 try/finally 中成对出现：持续并发下数值精确，
请求排空后 `inFlightRequests` 必然归零，不会无界增长。

## 演示端点

| 端点 | 说明 |
|------|------|
| `GET /api/sync`          | 纯同步路径，回显 traceId |
| `GET /api/async`         | 同步 → `@Async` 一跳，回显两侧 traceId |
| `GET /api/async-failure` | 异步任务内异常被捕获，标识仍保留 |
| `GET /api/outbound?url=` | 出站调用并转发 traceId，回显下游收到的标识 |
| `GET /api/fail`          | 抛异常，错误响应体与响应头均带 traceId |

## 本地验证

```bash
# 跑全部测试（含并发、异步、异常、指标一致性场景）
mvn test

# 手动启动（默认随机端口，可用 --server.port=8080 固定）
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8080

# 手动验证链路
curl -i -H 'X-Trace-Id: demo-123' http://localhost:8080/api/async
curl -i -H 'X-Trace-Id: demo-123' http://localhost:8080/api/fail
curl -s "http://localhost:8080/api/outbound?url=http://localhost:8080/api/sync"
curl -s http://localhost:8080/metrics

# 用 traceId 回查这次请求经过的关键节点
grep 'traceId=demo-123' <应用日志>
```

## 测试覆盖

| 测试类 | 覆盖点 |
|--------|--------|
| `TraceContextTest`        | ID 唯一性、清理不残留、多线程不串号 |
| `ConcurrentTracingIT`     | 64 路并发请求标识互不交叉，同步/异步节点一致；无头时服务端生成新 ID |
| `AsyncAndFailureTracingIT`| 异步失败、同步异常、出站调用三条路径标识保留 |
| `MetricsConsistencyIT`    | 5 轮 × 40 并发混合成功/失败，累计/失败计数精确，在途归零 |

测试日志中均打印请求标识与关键节点，便于定位。
