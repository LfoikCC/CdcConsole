package com.cdcplatform;

import com.cdcplatform.model.bi.BiCard;
import com.cdcplatform.model.bi.BiDashboard;
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
        long id = dashboards.create("销售看板", "华东区日销", "admin", null);

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
        long id = dashboards.create("覆盖保存", "", "admin", null);
        dashboards.replaceCards(id, List.of(card("bar", "旧卡", null, 0, 6)));

        dashboards.replaceCards(id, List.of(card("kpi", "新卡", null, 0, 3)));

        assertThat(dashboards.cards(id)).extracting(BiCard::title).containsExactly("新卡");
        dashboards.delete(id);
    }

    @Test
    void bumpingAStaleVersionAffectsNoRows() {
        long id = dashboards.create("乐观锁", "", "admin", null);

        assertThat(dashboards.touchVersion(id, 1)).isEqualTo(1);
        assertThat(dashboards.touchVersion(id, 1)).isZero();
        assertThat(dashboards.dashboard(id).version()).isEqualTo(2);

        dashboards.delete(id);
    }

    @Test
    void countsDashboardsReferencingADataset() {
        long datasetId = datasets.create(new Dataset(null, "订单", "", "TABLE", null, "CDC-SOURCE",
                "Orders", null, null, null, 0, 0, "READY", "admin", null, null), List.of());
        long id = dashboards.create("引用检查", "", "admin", null);
        dashboards.replaceCards(id, List.of(card("bar", "用了订单", datasetId, 0, 6)));

        assertThat(dashboards.referencingDashboards(datasetId)).isEqualTo(1);
        assertThat(dashboards.referencingDashboards(datasetId + 1000)).isZero();

        dashboards.delete(id);
        datasets.delete(datasetId);
    }

    @Test
    void nestsFoldersAndFiltersDashboardsBySubtree() {
        long parent = dashboards.createFolder("销售分析", null);
        long child = dashboards.createFolder("华东", parent);
        long inChild = dashboards.create("华东日报", "", "admin", child);
        long atRoot = dashboards.create("根目录看板", "", "admin", null);

        // 选中父目录时，子目录里的看板也要出现
        var subtree = dashboards.dashboards(null, null, false, List.of(parent, child), false);
        assertThat(subtree).extracting(BiDashboard::id).contains(inChild).doesNotContain(atRoot);
        // 只看根目录
        var root = dashboards.dashboards(null, null, false, null, true);
        assertThat(root).extracting(BiDashboard::id).contains(atRoot).doesNotContain(inChild);

        // 移回根目录
        dashboards.moveToFolder(inChild, null);
        assertThat(dashboards.dashboard(inChild).folderId()).isNull();

        dashboards.delete(inChild);
        dashboards.delete(atRoot);
        dashboards.deleteFolder(parent);
    }

    @Test
    void deletingFolderMovesChildrenAndDashboardsToParent() {
        long parent = dashboards.createFolder("待删父级", null);
        long middle = dashboards.createFolder("中间层", parent);
        long leaf = dashboards.createFolder("叶子", middle);
        long inMiddle = dashboards.create("挂在中间层", "", "admin", middle);
        long inLeaf = dashboards.create("挂在叶子", "", "admin", leaf);

        dashboards.deleteFolder(middle);

        // 直接子文件夹和直接挂在这一层的看板都上移到父级
        assertThat(dashboards.folder(leaf).parentId()).isEqualTo(parent);
        assertThat(dashboards.dashboard(inMiddle).folderId()).isEqualTo(parent);
        // 更深一层的看板留在自己的文件夹里（文件夹本身已经上移了一层）
        assertThat(dashboards.dashboard(inLeaf).folderId()).isEqualTo(leaf);

        dashboards.delete(inMiddle);
        dashboards.delete(inLeaf);
        dashboards.deleteFolder(leaf);
        dashboards.deleteFolder(parent);
    }

    @Test
    void recentViewsArePerUserAndDeduped() {
        long first = dashboards.create("最近查看A", "", "admin", null);
        long second = dashboards.create("最近查看B", "", "admin", null);

        dashboards.recordView(first, "alice");
        dashboards.recordView(second, "alice");
        // 再看一次 A，A 应该回到最前面，而且不会出现两条
        dashboards.recordView(first, "alice");

        assertThat(dashboards.recentViewed("alice", false, 10))
                .extracting(BiDashboard::id).containsSubsequence(first, second);
        assertThat(dashboards.recentViewed("alice", false, 10).stream()
                .filter(item -> item.id() == first).count()).isEqualTo(1);
        // 别人没看过，列表是空的
        assertThat(dashboards.recentViewed("bob", false, 10)).isEmpty();

        dashboards.delete(first);
        dashboards.delete(second);
    }

    @Test
    void recentPublishedSkipsDraftsAndSortsByPublishTime() {
        long older = dashboards.create("发布较早", "", "admin", null);
        long newer = dashboards.create("发布较晚", "", "admin", null);
        long draft = dashboards.create("还是草稿", "", "admin", null);
        dashboards.setStatus(older, "PUBLISHED");
        dashboards.setStatus(newer, "PUBLISHED");

        var recent = dashboards.recentPublished(10);
        assertThat(recent).extracting(BiDashboard::id).contains(older, newer).doesNotContain(draft);

        dashboards.delete(older);
        dashboards.delete(newer);
        dashboards.delete(draft);
    }
}
