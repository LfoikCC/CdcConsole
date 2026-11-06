package com.cdcplatform.model.bi;

/** 看板文件夹。{@code parentId} 为 null 表示挂在根目录下。 */
public record BiDashboardFolder(long id, String name, Long parentId) {
}
