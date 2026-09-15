package com.monitor.server.domain;

import com.monitor.server.common.ApiException;

import java.util.Arrays;
import java.util.List;

/**
 * 业务线枚举。{@code boards} 决定切换到该业务线后前端侧边栏展示哪些视图，
 * 前端不做业务线分支逻辑（接口文档 §04）。
 */
public enum BizLine {

    ALL("all", "全平台总览", "全平台", "#6558d3", "globe",
            List.of("sentinel", "business", "quality", "service", "risk", "tech")),
    DRIVER("driver", "司机端", "司机端", "#3587e7", "car",
            List.of("sentinel", "business", "quality", "tech")),
    TRANSFER("transfer", "转单端", "转单端", "#e0923f", "repeat",
            List.of("sentinel", "business", "tech")),
    CARPOOL("carpool", "顺风车", "顺风车", "#25a579", "users",
            List.of("sentinel", "business", "quality", "risk")),
    DESIGNATED("designated", "代驾", "代驾", "#c56ad0", "wine",
            List.of("sentinel", "business", "quality")),
    AIRPORT("airport", "接送机", "接送机", "#dc5a58", "plane",
            List.of("sentinel", "business", "quality"));

    private final String id;
    private final String label;
    private final String shortName;
    private final String color;
    private final String icon;
    private final List<String> boards;

    BizLine(String id, String label, String shortName, String color, String icon, List<String> boards) {
        this.id = id;
        this.label = label;
        this.shortName = shortName;
        this.color = color;
        this.icon = icon;
        this.boards = boards;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public String shortName() {
        return shortName;
    }

    public String color() {
        return color;
    }

    public String icon() {
        return icon;
    }

    public List<String> boards() {
        return boards;
    }

    public boolean isAll() {
        return this == ALL;
    }

    public static BizLine of(String raw) {
        if (raw == null || raw.isBlank()) {
            return ALL;
        }
        return Arrays.stream(values())
                .filter(b -> b.id.equalsIgnoreCase(raw))
                .findFirst()
                .orElseThrow(() -> ApiException.invalidParam("未知 biz_line: " + raw));
    }

    /** 实际业务线（不含 ALL），用于遍历各线卡片。 */
    public static List<BizLine> concrete() {
        return Arrays.stream(values()).filter(b -> b != ALL).toList();
    }
}
