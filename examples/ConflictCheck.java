// 示例1：近似商标排查 —— 输入商标名 + 法域，返回风险分级与结论。
// 三步跑通：1. 填 key（export ISUD_API_KEY=...） 2. javac 3. java ConflictCheck 优标宝 CN
import cn.isud.sdk.Client;
import cn.isud.sdk.Models.TrademarkSearchResult;
import cn.isud.sdk.SdkErrors.SdkError;

public class ConflictCheck {
    public static void main(String[] args) {
        String mark = args.length > 0 ? args[0] : "优标宝";
        String jurisdiction = args.length > 1 ? args[1] : "CN";
        try (Client client = Client.builder().build()) {
            TrademarkSearchResult res = client.searchTrademarks(jurisdiction, mark);
            System.out.println(Client.formatSearchResult(res));
            if (client.lastMeta.creditsCharged != null)
                System.out.println("\n本次扣点 " + client.lastMeta.creditsCharged
                        + "，余额 " + client.lastMeta.creditsRemaining
                        + "（requestId=" + client.lastMeta.requestId + "）");
        } catch (SdkError e) {
            System.err.println(e.friendly());
            System.exit(1);
        }
    }
}
