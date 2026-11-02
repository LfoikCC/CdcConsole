package com.cdcplatform.model.dataset;

/** 数据集字段。{@code dataType} 是 H2 类型，直连数据集用于展示与数据流推导。 */
public record DatasetColumn(int ordinal, String name, String dataType, boolean nullable) {
}
