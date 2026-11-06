package com.cdcplatform.model.dataset;

/**
 * 数据集字段。
 *
 * <p>{@code name} 是系统里展示、可改的别名；{@code originalName} 是数据源里的原始字段名
 * （库表列名 / Excel 表头），追加数据时靠它做匹配。{@code dataType} 是 H2 类型，
 * 直连数据集用于展示与数据流推导。
 */
public record DatasetColumn(int ordinal, String name, String dataType, boolean nullable, String originalName) {

    /** 没有别名时原始名就是字段名（库表、SQL 数据集都走这个构造）。 */
    public DatasetColumn(int ordinal, String name, String dataType, boolean nullable) {
        this(ordinal, name, dataType, nullable, name);
    }
}
