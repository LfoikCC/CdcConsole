package com.cdcplatform.model.bi;

import java.util.Map;

/**
 * 看板上的一张卡片。
 *
 * <p>布局用 12 列网格的格数（{@code x/y/w/h}）而不是像素：改行高不会让已存的布局失效。
 * {@code config} 是各图表类型自己的字段（维度 / 度量 / 筛选 / 排序 / 文本），
 * 在仓储层就解析成 Map，控制器与前端都不再二次解析。
 */
public record BiCard(Long id, String type, String title, Long datasetId,
                     int x, int y, int w, int h, Map<String, Object> config) {
}
