# isud-sdk-java

优标宝开放 API 的 Java SDK。**零第三方运行时依赖**（JDK 11+ 原生 `HttpClient` + 内置极简 JSON 解析器），契约驱动。

## 依赖

- JDK 11 或更高（推荐 17/21）。运行时无需任何第三方 jar。

Maven 项目：

```xml
<dependency>
  <groupId>cn.isud</groupId>
  <artifactId>isud-sdk-java</artifactId>
  <version>1.0.0</version>
</dependency>
```

## 三步跑通（Time-to-Hello-World < 3 分钟）

```bash
export ISUD_API_KEY=你的密钥   # 控制台领取，注册即送调用次数
```

```java
import cn.isud.sdk.Client;
import cn.isud.sdk.Models.TrademarkSearchResult;

public class Demo {
    public static void main(String[] args) {
        try (Client client = Client.builder().build()) {
            TrademarkSearchResult res = client.searchTrademarks("CN", "优标宝");
            System.out.println(Client.formatSearchResult(res));
        }
    }
}
```

```
检索: "优标宝"  法域=CN
结论: 存在 1 件高近似商标，建议调整
命中 3 条 | 高险 1 / 中险 1 / 低险 1
```

客户端构造：`Client.builder().apiKey(...).baseURL(...).timeout(...).maxRetries(3).build()`；不传 apiKey 时自动读环境变量 `ISUD_API_KEY`。

## 设计

见 [`SDK-DESIGN.md`](SDK-DESIGN.md)：信封解析、6 类异常映射、Retry-After 退避、幂等键、扣点回读、分页——与 Python/TypeScript/Go 版本逐条对齐。

- **生成层** `Models.java` / `Json.java`：从 `openapi.yaml` 映射，标注 GENERATED，勿手改。
- **体验层** `Client.java` `SdkErrors.java` `Retry.java`：手写，实现全部 SDK 体验。

## 9 个端点

| 方法 | HTTP |
|---|---|
| `searchTrademarks` | POST /ip/trademark/search |
| `searchDesigns` | POST /ip/design/search |
| `listTrademarks` | GET /ip/trademark/list |
| `listReports` | GET /ip/report/list |
| `getWorkbenchStats` | GET /ip/workbench/stats |
| `getUsage` | GET /ip/usage |
| `queryRequestLog` | GET /ip/requests |
| `getMcpHealth` | GET /mcp/health |
| `callMcp` | POST /mcp |

## 示例项目

- `examples/ConflictCheck.java` — 近似商标排查
- `examples/BatchScreen.java` — 批量风险筛查（幂等键 + 退避 + 导出）

## 测试

```bash
mvn test                       # JUnit（需联网拉 junit）
# 或纯 javac 自包含测试（零依赖）：
javac -d /tmp/t $(find src/main/java -name '*.java') src/test/java/cn/isud/sdk/ContractSelfTest.java
java -cp /tmp/t cn.isud.sdk.ContractSelfTest
```

契约测试用注入的 `Transport` mock HTTP 响应，不依赖真实 key、不扣点。

## 模型层同步

`openapi.yaml` 更新后，重新映射 `Models.java` 的字段（保持 GENERATED 标注）。开放枚举用 `String`，不封闭成枚举常量。

## License

MIT
