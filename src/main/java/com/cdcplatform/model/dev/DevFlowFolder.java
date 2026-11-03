package com.cdcplatform.model.dev;

/** 数据流文件夹。{@code parentId} 为 null 表示挂在根目录下。 */
public record DevFlowFolder(long id, String name, Long parentId) {
}
