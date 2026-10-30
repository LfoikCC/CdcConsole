package com.cdcplatform.service.dev;

import com.cdcplatform.service.dev.DevTempTableStore.ColumnMeta;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DevExpressionValidatorTest {

    private static final List<ColumnMeta> UPSTREAM = List.of(
            new ColumnMeta(1, "id", "BIGINT"),
            new ColumnMeta(2, "amount", "DECIMAL(38,10)"),
            new ColumnMeta(3, "status", "VARCHAR"));

    @Test
    void translatesPlainArithmetic() {
        assertThat(DevExpressionValidator.translate("amount * 1.13", UPSTREAM))
                .isEqualTo("up.\"C2\" * 1.13");
    }

    @Test
    void translatesFunctionCall() {
        assertThat(DevExpressionValidator.translate("ROUND(amount * 1.13, 2)", UPSTREAM))
                .isEqualTo("ROUND(up.\"C2\" * 1.13, 2)");
    }

    @Test
    void translatesStringLiteralAndConstants() {
        assertThat(DevExpressionValidator.translate("COALESCE(status, 'unknown')", UPSTREAM))
                .isEqualTo("COALESCE(up.\"C3\", 'unknown')");
        assertThat(DevExpressionValidator.translate("NULLIF(amount, 0)", UPSTREAM))
                .isEqualTo("NULLIF(up.\"C2\", 0)");
    }

    @Test
    void matchesChineseLabelWithSpace() {
        List<ColumnMeta> columns = List.of(
                new ColumnMeta(1, "订单 金额", "DECIMAL(38,10)"),
                new ColumnMeta(2, "单价", "DECIMAL(38,10)"));

        assertThat(DevExpressionValidator.translate("订单 金额 * 单价", columns))
                .isEqualTo("up.\"C1\" * up.\"C2\"");
    }

    @Test
    void prefersLongestLabelPrefix() {
        // `订单` 是 `订单 金额` 的前缀；必须优先匹配更长的那个，否则会被截成两个 token
        List<ColumnMeta> columns = List.of(
                new ColumnMeta(1, "订单", "VARCHAR"),
                new ColumnMeta(2, "订单 金额", "DECIMAL(38,10)"));

        assertThat(DevExpressionValidator.translate("订单 金额", columns)).isEqualTo("up.\"C2\"");
        assertThat(DevExpressionValidator.translate("订单", columns)).isEqualTo("up.\"C1\"");
    }

    @Test
    void distinguishesColumnFromSameNamedFunction() {
        // 列名恰好叫 upper：不带括号是列，带括号是函数。
        // 注意 SUM/COUNT 这类聚合函数不在标量白名单里——聚合只能用在「分组聚合」算子内。
        List<ColumnMeta> columns = List.of(
                new ColumnMeta(1, "upper", "VARCHAR"),
                new ColumnMeta(2, "status", "VARCHAR"));

        assertThat(DevExpressionValidator.translate("upper", columns)).isEqualTo("up.\"C1\"");
        assertThat(DevExpressionValidator.translate("UPPER(status)", columns)).isEqualTo("UPPER(up.\"C2\")");
    }

    @Test
    void rejectsAggregateFunctionInScalarExpression() {
        assertThatThrownBy(() -> DevExpressionValidator.translate("SUM(amount)", UPSTREAM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("既不是上游列");
    }

    @Test
    void rejectsSemicolon() {
        assertThatThrownBy(() -> DevExpressionValidator.checkSyntax("1; DROP TABLE x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("分号");
    }

    @Test
    void rejectsComment() {
        assertThatThrownBy(() -> DevExpressionValidator.checkSyntax("amount -- comment"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("注释");
        assertThatThrownBy(() -> DevExpressionValidator.checkSyntax("amount /* x */ + 1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("注释");
    }

    @Test
    void rejectsSubqueryKeyword() {
        assertThatThrownBy(() -> DevExpressionValidator.checkSyntax("(SELECT 1)"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("语句关键字");
        assertThatThrownBy(() -> DevExpressionValidator.checkSyntax("CASE WHEN 1 THEN 2 END"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("语句关键字");
    }

    @Test
    void rejectsUnknownIdentifierAndListsColumns() {
        assertThatThrownBy(() -> DevExpressionValidator.translate("not_there + 1", UPSTREAM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not_there")
                .hasMessageContaining("id")
                .hasMessageContaining("amount");
    }

    @Test
    void rejectsUnbalancedParenthesis() {
        assertThatThrownBy(() -> DevExpressionValidator.checkSyntax("ROUND(amount, 2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("括号不匹配");
        assertThatThrownBy(() -> DevExpressionValidator.checkSyntax("amount)"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("括号不匹配");
    }

    @Test
    void rejectsEmptyExpression() {
        assertThatThrownBy(() -> DevExpressionValidator.checkSyntax("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
    }

    @Test
    void rejectsIllegalCharacter() {
        assertThatThrownBy(() -> DevExpressionValidator.translate("amount @ 2", UPSTREAM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@");
    }

    @Test
    void rejectsFunctionWithoutParenthesis() {
        assertThatThrownBy(() -> DevExpressionValidator.translate("ROUND", UPSTREAM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少括号");
    }
}
