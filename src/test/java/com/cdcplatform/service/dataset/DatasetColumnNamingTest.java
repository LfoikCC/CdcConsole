package com.cdcplatform.service.dataset;

import com.cdcplatform.model.dataset.DatasetColumn;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 字段注释 → 展示名的挑选规则：只改安全、唯一、确实变了的那些。 */
class DatasetColumnNamingTest {

    private static DatasetColumn column(int ordinal, String name, String original) {
        return new DatasetColumn(ordinal, name, "VARCHAR", true, original);
    }

    @Test
    void takesUniqueCommentsAndSkipsDuplicatedOrUnchangedOnes() {
        List<DatasetColumn> columns = List.of(
                column(1, "DETAIL_ID", "DETAIL_ID"),
                column(2, "MAIN_ID", "MAIN_ID"),
                column(3, "REMARK", "REMARK"),
                column(4, "订单号", "ORDER_NO"),
                column(5, "CUSTOMER", "CUSTOMER"),
                column(6, "CUSTOMER_NAME", "CUSTOMER_NAME"),
                column(7, "金额", "AMOUNT"),
                column(8, "OTHER", "OTHER"),
                column(9, "状态", "STATUS"),
                column(10, "EXTRA", "EXTRA"));
        Map<String, String> comments = Map.of(
                "DETAIL_ID", "明细主键",
                "MAIN_ID", "备用字段",
                "REMARK", "备用字段",      // 注释重复：两条都跳过
                "ORDER_NO", "订单号",       // 和现有名字一样：不用改
                "CUSTOMER", "客户名称",
                "CUSTOMER_NAME", "客户名称", // 重复：两条都跳过
                "AMOUNT", "小计",           // 金额这名字腾出来了，可以给别人用
                "OTHER", "金额",
                "STATUS", " ",
                "EXTRA", "状态");            // 撞上没改名的字段「状态」：跳过

        Map<Integer, String> planned = DatasetService.commentNames(columns, comments);

        assertThat(planned).containsExactly(
                Map.entry(1, "明细主键"),
                Map.entry(7, "小计"),
                Map.entry(8, "金额"));
    }

    @Test
    void ignoresBlankComments() {
        var columns = List.of(column(1, "A", "A"), column(2, "B", "B"));
        var planned = DatasetService.commentNames(columns, Map.of("A", "   ", "B", " "));
        assertThat(planned).isEmpty();
    }
}
