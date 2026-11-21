// 示例2：批量风险筛查 —— 逐行读商标名 → 检索 → 汇总风险等级 → 写 CSV。
// 用法：export ISUD_API_KEY=... ; java BatchScreen marks.txt out.csv CN
import cn.isud.sdk.Client;
import cn.isud.sdk.Models.TrademarkSearchResult;
import cn.isud.sdk.SdkErrors.SdkError;
import java.nio.file.*;
import java.util.*;

public class BatchScreen {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("用法: java BatchScreen <输入.txt> <输出.csv> [法域]");
            System.exit(2);
        }
        Path in = Paths.get(args[0]), out = Paths.get(args[1]);
        String jurisdiction = args.length > 2 ? args[2] : "CN";
        try {
            List<String> marks = new ArrayList<>();
            for (String line : Files.readAllLines(in)) {
                String m = line.trim();
                if (!m.isEmpty() && !m.startsWith("#")) marks.add(m);
            }
            try (Client client = Client.builder().build()) {
                // 展示退避
                client.onRetry = (err, n, waitMs) ->
                        System.err.printf("  限流，第 %d 次重试，等待 %d ms ...%n", n, waitMs);
                List<String> lines = new ArrayList<>();
                lines.add("mark,riskLevel,total");
                for (int i = 0; i < marks.size(); i++) {
                    String mark = marks.get(i);
                    System.out.printf("[%d/%d] 筛查 %s ... ", i + 1, marks.size(), mark);
                    try {
                        TrademarkSearchResult res = client.searchTrademarks(jurisdiction, mark);
                        String level = res != null && res.highRisk > 0 ? "high"
                                : res != null && res.mediumRisk > 0 ? "medium" : "low";
                        int total = res != null ? res.total : 0;
                        System.out.printf("%s（%d 条）%n", level, total);
                        lines.add(mark + "," + level + "," + total);
                    } catch (SdkError e) {
                        System.out.println("失败");
                        lines.add(mark + ",error,0");
                    }
                }
                Files.write(out, lines);
                System.out.println("\n完成。结果已写入 " + out);
            }
        } catch (SdkError e) {
            System.err.println(e.friendly());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("✗ " + e.getMessage());
            System.exit(1);
        }
    }
}
