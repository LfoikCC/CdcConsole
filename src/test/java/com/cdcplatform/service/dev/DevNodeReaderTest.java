package com.cdcplatform.service.dev;

import com.cdcplatform.model.Types.DatabaseType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DevNodeReaderTest {

    @Test
    void quotesHyphenatedSchemaInFromClause() {
        // 库名带连字符时 MySQL 会把它当成减法，直接报语法错
        assertThat(DevNodeReader.quoteQualifiedNames("SELECT * FROM CDC-SOURCE.Orders", DatabaseType.MYSQL))
                .isEqualTo("SELECT * FROM `CDC-SOURCE`.Orders");
    }

    @Test
    void quotesEveryQualifiedNameInJoins() {
        String sql = "select * from CDC-SOURCE.Orders o join CDC-TARGET.Orders t on o.id = t.id";
        assertThat(DevNodeReader.quoteQualifiedNames(sql, DatabaseType.MYSQL))
                .isEqualTo("select * from `CDC-SOURCE`.Orders o join `CDC-TARGET`.Orders t on o.id = t.id");
    }

    @Test
    void leavesAlreadyQuotedAndPlainNamesAlone() {
        assertThat(DevNodeReader.quoteQualifiedNames("SELECT * FROM `CDC-SOURCE`.`Orders`", DatabaseType.MYSQL))
                .isEqualTo("SELECT * FROM `CDC-SOURCE`.`Orders`");
        assertThat(DevNodeReader.quoteQualifiedNames("SELECT * FROM orders", DatabaseType.MYSQL))
                .isEqualTo("SELECT * FROM orders");
        assertThat(DevNodeReader.quoteQualifiedNames("SELECT * FROM (SELECT 1) x", DatabaseType.MYSQL))
                .isEqualTo("SELECT * FROM (SELECT 1) x");
    }

    @Test
    void doesNotTouchStringLiterals() {
        String sql = "SELECT * FROM orders WHERE note = 'from a-b.c'";
        assertThat(DevNodeReader.quoteQualifiedNames(sql, DatabaseType.MYSQL)).isEqualTo(sql);
    }

    @Test
    void usesBracketsForSqlServer() {
        assertThat(DevNodeReader.quoteQualifiedNames("SELECT * FROM CDC-SOURCE.Orders", DatabaseType.SQLSERVER))
                .isEqualTo("SELECT * FROM [CDC-SOURCE].Orders");
    }
}
