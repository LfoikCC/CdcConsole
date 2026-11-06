package com.cdcplatform.model.bi;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 数据看板：一个页面上摆若干张卡片。
 *
 * <p>{@code status} 只有 DRAFT / PUBLISHED 两种。未发布的看板只有管理员可见，
 * 发布后才对所有登录用户开放，所以发布不是装饰性的标记。
 */
public record BiDashboard(Long id, String name, String description, String status, int version,
                          String createdBy, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                          OffsetDateTime publishedAt, int cardCount,
                          /** 所属文件夹；null 表示根目录。左侧导航的目录树用它归类。 */
                          Long folderId,
                          /** 看板级筛选器：同数据集的卡片取数时自动带上，见 BiDashboardService#data。 */
                          List<Map<String, Object>> filters) {
}
