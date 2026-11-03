package com.cdcplatform;

import com.cdcplatform.model.bi.BiCard;
import com.cdcplatform.model.dataset.Dataset;
import com.cdcplatform.repository.BiDashboardRepository;
import com.cdcplatform.repository.DatasetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:bi-dashboard-test;DB_CLOSE_DELAY=-1",
        "cdc.cache.directory=target/test-bi-cache"
})
class BiDashboardRepositoryTest {

    @Autowired
    BiDashboardRepository dashboards;
    @Autowired
    DatasetRepository datasets;

    private static BiCard card(String type, String title, Long datasetId, int x, int w) {
        return new BiCard(null, type, title, datasetId, x, 0, w, 8,
                Map.of("dimensions", List.of(Map.of("column", "region")), "limit", 100));
    }

    @Test
    void createsDashboardAndReturnsCardsInOrder() {
        long id = dashboards.create("销售看板", "华东区日销", "admin");

        var saved = dashboards.replaceCards(id, List.of(
                card("bar", "各地区销售额", null, 0, 6),
                card("line", "趋势", null, 6, 6)));

        assertThat(saved).hasSize(2).allSatisfy(item -> assertThat(item.id()).isNotNull());
        var reloaded = dashboards.dashboard(id);
        assertThat(reloaded.name()).isEqualTo("销售看板");
        assertThat(reloaded.status()).isEqualTo("DRAFT");
        assertThat(reloaded.version()).isEqualTo(1);
        assertThat(reloaded.cardCount()).isEqualTo(2);
        assertThat(dashboards.cards(id)).extracting(BiCard::title).containsExactly("各地区销售额", "趋势");

        dashboards.delete(id);
        assertThatThrownBy(() -> dashboards.dashboard(id)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void replacesCardsInsteadOfAppending() {
        long id = dashboards.create("覆盖保存", "", "admin");
        dashboards.replaceCards(id, List.of(card("bar", "旧卡", null, 0, 6)));

        dashboards.replaceCards(id, List.of(card("kpi", "新卡", null, 0, 3)));

        assertThat(dashboards.cards(id)).extracting(BiCard::title).containsExactly("新卡");
        dashboards.delete(id);
    }

    @Test
    void bumpingAStaleVersionAffectsNoRows() {
        long id = dashboards.create("乐观锁", "", "admin");

        assertThat(dashboards.touchVersion(id, 1)).isEqualTo(1);
        assertThat(dashboards.touchVersion(id, 1)).isZero();
        assertThat(dashboards.dashboard(id).version()).isEqualTo(2);

        dashboards.delete(id);
    }

    @Test
    void countsDashboardsReferencingADataset() {
        long datasetId = datasets.create(new Dataset(null, "订单", "", "TABLE", null, "CDC-SOURCE",
                "Orders", null, null, null, 0, 0, "READY", "admin", null, null), List.of());
        long id = dashboards.create("引用检查", "", "admin");
        dashboards.replaceCards(id, List.of(card("bar", "用了订单", datasetId, 0, 6)));

        assertThat(dashboards.referencingDashboards(datasetId)).isEqualTo(1);
        assertThat(dashboards.referencingDashboards(datasetId + 1000)).isZero();

        dashboards.delete(id);
        datasets.delete(datasetId);
    }
}
