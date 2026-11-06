package com.cdcplatform.service.bi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 看板筛选器 → 卡片查询条件。
 *
 * <p>看板筛选器存的是控件配置（类型、取值、区间），卡片查询要的是
 * `{column, op, value}`。这一层负责翻译，规则和前端 biFilters.js 的
 * filterConditions 一一对应——两边都改才算改完。
 *
 * <p>没填完的筛选器一律翻译成空：刚添加、值还空着的时候不能把卡片筛成空表。
 */
public final class DashboardFilters {

    private DashboardFilters() {
    }

    /** 某个筛选器是否作用到这张卡：同数据集，且落在作用范围内。 */
    public static boolean appliesTo(Map<String, Object> filter, Long datasetId, Long cardId) {
        if (filter == null) return false;
        if (!Objects.equals(asLong(filter.get("datasetId")), datasetId)) return false;
        if (!"cards".equals(String.valueOf(filter.get("scope")))) return true;
        if (cardId == null) return false;
        if (!(filter.get("cardIds") instanceof List<?> ids)) return false;
        for (Object id : ids) {
            if (Objects.equals(asLong(id), cardId)) return true;
        }
        return false;
    }

    /** 全部命中这张卡的条件，按筛选器顺序展开。 */
    public static List<Map<String, Object>> conditions(List<Map<String, Object>> filters, Long datasetId, Long cardId) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (filters == null) return result;
        for (Map<String, Object> filter : filters) {
            if (!appliesTo(filter, datasetId, cardId)) continue;
            result.addAll(conditionsOf(filter));
        }
        return result;
    }

    /** 一个筛选器 → 若干条条件。 */
    public static List<Map<String, Object>> conditionsOf(Map<String, Object> filter) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (filter == null) return result;
        String type = String.valueOf(filter.get("type"));
        String column = text(filter.get("column"));
        switch (type) {
            case "select" -> {
                if (column.isEmpty()) return result;
                List<Object> values = new ArrayList<>();
                if (filter.get("values") instanceof List<?> list) {
                    for (Object item : list) {
                        if (item != null && !String.valueOf(item).isEmpty()) values.add(String.valueOf(item));
                    }
                }
                if (values.isEmpty()) return result;
                // 单选只取第一个：界面上只显示一个值时，查询不能按好几个过滤
                if (Boolean.FALSE.equals(filter.get("multiple")) && values.size() > 1) {
                    values = values.subList(0, 1);
                }
                result.add(condition(column, Boolean.TRUE.equals(filter.get("exclude")) ? "NOT IN" : "IN", values));
            }
            case "condition" -> {
                if (column.isEmpty()) return result;
                Map<String, Object> single = conditionOf(column, text(filter.get("op")), text(filter.get("keyword")));
                if (single != null) result.add(single);
            }
            case "range", "date" -> {
                if (column.isEmpty()) return result;
                Map<String, Object> single = rangeOf(column, text(filter.get("valueOp")), filter.get("from"), filter.get("to"));
                if (single != null) result.add(single);
            }
            case "combo" -> {
                if (filter.get("conditions") instanceof List<?> list) {
                    for (Object item : list) {
                        if (!(item instanceof Map<?, ?> map)) continue;
                        Map<String, Object> single = conditionOf(
                                text(map.get("column")), text(map.get("op")), text(map.get("value")));
                        if (single != null) result.add(single);
                    }
                }
            }
            default -> { }
        }
        return result;
    }

    /** 条件筛选器：关键词为空时不参与过滤；模糊匹配统一转成 LIKE + 通配符。 */
    static Map<String, Object> conditionOf(String column, String op, String keyword) {
        if (column == null || column.isEmpty() || keyword == null || keyword.isBlank()) return null;
        return switch (op) {
            case "CONTAINS" -> condition(column, "LIKE", "%" + keyword + "%");
            case "NOT_CONTAINS" -> condition(column, "NOT LIKE", "%" + keyword + "%");
            case "STARTS" -> condition(column, "LIKE", keyword + "%");
            case "ENDS" -> condition(column, "LIKE", "%" + keyword);
            case "<>" -> condition(column, "<>", keyword);
            default -> condition(column, "=", keyword);
        };
    }

    /** 区间类：区间给两个值（null 表示那一端不限），其余给一个。 */
    static Map<String, Object> rangeOf(String column, String op, Object from, Object to) {
        if (column == null || column.isEmpty()) return null;
        String start = blank(from) ? null : String.valueOf(from);
        String end = blank(to) ? null : String.valueOf(to);
        if ("IS NULL".equals(op) || "IS NOT NULL".equals(op)) return condition(column, op, null);
        if ("BETWEEN".equals(op)) {
            if (start == null && end == null) return null;
            // 只有一端也要占两个位置：查询层按 get(0)/get(1) 读起止
            List<Object> range = new ArrayList<>();
            range.add(start);
            range.add(end);
            return condition(column, "BETWEEN", range);
        }
        String single = start != null ? start : end;
        if (single == null) return null;
        return condition(column, op == null || op.isEmpty() ? "=" : op, single);
    }

    private static Map<String, Object> condition(String column, String op, Object value) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("column", column);
        item.put("op", op);
        item.put("value", value);
        return item;
    }

    private static boolean blank(Object value) {
        return value == null || String.valueOf(value).isBlank();
    }

    static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    static Long asLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
