package cn.isud.sdk;

import java.util.List;
import java.util.Map;

/**
 * 数据模型 —— 从 openapi.yaml (OpenAPI 3.1, v1.0.0) 映射，标注 GENERATED，勿手改。
 * 用普通 POJO + Map 承载，开放枚举字段保持 String，便于前向兼容。
 */
public final class Models {
    private Models() {}

    /** 商标条目。 */
    public static final class TrademarkItem {
        public String markText, markType, ownerName, status, applicationNumber;
        public String registrationNumber, riskLevel, riskReason, externalId;
        public List<String> niceClasses;
        public long filingDate, registrationDate, expiryDate;

        public static TrademarkItem from(Map<String, Object> m) {
            TrademarkItem x = new TrademarkItem();
            x.markText = Json.str(m.get("markText"));
            x.markType = Json.str(m.get("markType"));
            x.ownerName = Json.str(m.get("ownerName"));
            x.status = Json.str(m.get("status"));
            x.applicationNumber = Json.str(m.get("applicationNumber"));
            x.registrationNumber = Json.str(m.get("registrationNumber"));
            x.riskLevel = Json.str(m.get("riskLevel"));
            x.riskReason = Json.str(m.get("riskReason"));
            x.externalId = Json.str(m.get("externalId"));
            Object nc = m.get("niceClasses");
            if (nc instanceof List) {
                java.util.ArrayList<String> l = new java.util.ArrayList<>();
                for (Object o : (List<?>) nc) l.add(Json.str(o));
                x.niceClasses = l;
            }
            x.filingDate = Json.asLong(m.get("filingDate"));
            x.registrationDate = Json.asLong(m.get("registrationDate"));
            x.expiryDate = Json.asLong(m.get("expiryDate"));
            return x;
        }
    }

    /** 检索可审计元数据。 */
    public static final class OpenSearchMeta {
        public String sortVersion, dataChannel, normalizer;
        public static OpenSearchMeta from(Map<String, Object> m) {
            OpenSearchMeta x = new OpenSearchMeta();
            x.sortVersion = Json.str(m.get("sortVersion"));
            x.dataChannel = Json.str(m.get("dataChannel"));
            x.normalizer = Json.str(m.get("normalizer"));
            return x;
        }
    }

    /** POST /ip/trademark/search 的 data 载荷。 */
    public static final class TrademarkSearchResult {
        public String jurisdictionCode, keyword, conclusion, source;
        public int total, page, size, totalPages, highRisk, mediumRisk, lowRisk, activeCount;
        public long elapsedMs, reportId;
        public OpenSearchMeta meta;
        public List<TrademarkItem> items;

        public static TrademarkSearchResult from(Map<String, Object> m) {
            TrademarkSearchResult x = new TrademarkSearchResult();
            x.jurisdictionCode = Json.str(m.get("jurisdictionCode"));
            x.keyword = Json.str(m.get("keyword"));
            x.conclusion = Json.str(m.get("conclusion"));
            x.source = Json.str(m.get("source"));
            x.total = Json.asInt(m.get("total"));
            x.page = Json.asInt(m.get("page"));
            x.size = Json.asInt(m.get("size"));
            x.totalPages = Json.asInt(m.get("totalPages"));
            x.highRisk = Json.asInt(m.get("highRisk"));
            x.mediumRisk = Json.asInt(m.get("mediumRisk"));
            x.lowRisk = Json.asInt(m.get("lowRisk"));
            x.activeCount = Json.asInt(m.get("activeCount"));
            x.elapsedMs = Json.asLong(m.get("elapsedMs"));
            x.reportId = Json.asLong(m.get("reportId"));
            Object metaObj = m.get("meta");
            if (metaObj instanceof Map) x.meta = OpenSearchMeta.from(Json.asObj(metaObj));
            Object itemsObj = m.get("items");
            if (itemsObj instanceof List) {
                java.util.ArrayList<TrademarkItem> l = new java.util.ArrayList<>();
                for (Object o : (List<?>) itemsObj)
                    if (o instanceof Map) l.add(TrademarkItem.from(Json.asObj(o)));
                x.items = l;
            }
            return x;
        }
    }

    /** 通用分页响应。 */
    public static final class Page {
        public int current, size, total;
        public List<Map<String, Object>> records;
        public static Page from(Map<String, Object> m) {
            Page p = new Page();
            p.current = Json.asInt(m.get("current"));
            p.size = Json.asInt(m.get("size"));
            p.total = Json.asInt(m.get("total"));
            Object recs = m.get("records");
            if (recs instanceof List) {
                java.util.ArrayList<Map<String, Object>> l = new java.util.ArrayList<>();
                for (Object o : (List<?>) recs) if (o instanceof Map) l.add(Json.asObj(o));
                p.records = l;
            }
            return p;
        }
    }

    /** 用量周期计数。 */
    public static final class UsagePeriod {
        public int calls, points;
        public static UsagePeriod from(Map<String, Object> m) {
            UsagePeriod u = new UsagePeriod();
            u.calls = Json.asInt(m.get("calls"));
            u.points = Json.asInt(m.get("points"));
            return u;
        }
    }

    /** 开放 API 用量。 */
    public static final class OpenApiUsage {
        public int balance, credits, creditsUsed, dailyLimit;
        public long periodResetAt;
        public UsagePeriod today, month;
        public static OpenApiUsage from(Map<String, Object> m) {
            OpenApiUsage u = new OpenApiUsage();
            u.balance = Json.asInt(m.get("balance"));
            u.credits = Json.asInt(m.get("credits"));
            u.creditsUsed = Json.asInt(m.get("creditsUsed"));
            u.dailyLimit = Json.asInt(m.get("dailyLimit"));
            u.periodResetAt = Json.asLong(m.get("periodResetAt"));
            Object t = m.get("today"); if (t instanceof Map) u.today = UsagePeriod.from(Json.asObj(t));
            Object mo = m.get("month"); if (mo instanceof Map) u.month = UsagePeriod.from(Json.asObj(mo));
            return u;
        }
    }

    /** MCP 健康裸响应。 */
    public static final class MCPHealth {
        public String status;
        public int toolCount;
        public static MCPHealth from(Map<String, Object> m) {
            MCPHealth h = new MCPHealth();
            h.status = Json.str(m.get("status"));
            h.toolCount = Json.asInt(m.get("toolCount"));
            return h;
        }
    }
}
