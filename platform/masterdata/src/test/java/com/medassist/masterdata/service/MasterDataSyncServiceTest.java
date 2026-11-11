package com.medassist.masterdata.service;

import com.medassist.masterdata.config.MasterDataProperties;
import com.medassist.masterdata.domain.MasterDataModel.Domain;
import com.medassist.masterdata.domain.MasterDataModel.Item;
import com.medassist.masterdata.domain.MasterDataModel.SyncResult;
import com.medassist.masterdata.repo.MasterDataRepository;
import com.medassist.masterdata.repo.SourceCatalogRepository;
import com.medassist.masterdata.repo.SourceCatalogRepository.SourceRow;
import com.medassist.masterdata.support.Snapshots;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 源表同步：只对账不覆盖、消失置 MISSING、源与手工条目冲突不越权。
 */
class MasterDataSyncServiceTest {

    private MasterDataRepository repo;
    private SourceCatalogRepository source;
    private MasterDataWriter writer;
    private MasterDataSyncService service;

    @BeforeEach
    void setUp() {
        repo = mock(MasterDataRepository.class);
        source = mock(SourceCatalogRepository.class);
        writer = mock(MasterDataWriter.class);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class,
                withSettings().lenient());
        service = new MasterDataSyncService(repo, source, writer, new MasterDataProperties(), tx);
        when(repo.findDomain("DEPT")).thenReturn(Optional.of(domain()));
        when(source.load("CLINICAL_DEPT")).thenReturn(List.of(
                row("NEW1", "新科室"),
                row("EX1", "内科"),
                row("CH1", "内科一病区"),
                row("MAN1", "手工科室")));
        when(repo.listAllItems("DEPT")).thenReturn(List.of(
                item("EX1", "内科", Item.SOURCE_IN_HOUSE),
                item("CH1", "内科", Item.SOURCE_IN_HOUSE),
                item("MAN1", "手工科室", Item.SOURCE_MANUAL),
                item("GONE1", "已撤销科室", Item.SOURCE_IN_HOUSE)));
    }

    private Domain domain() {
        return new Domain("DEPT", "科室", "ORGANIZATION", "platform-clinical", "clinical_svc",
                "md_dept", "dept_code", "name", "CLINICAL_DEPT", true, List.of(),
                "科室主数据", 10, true, null, null, Map.of());
    }

    private SourceRow row(String code, String name) {
        return new SourceRow(code, name, null, null, null, null, true, code + "-ref", Map.of());
    }

    private Item item(String code, String name, String sourceSystem) {
        return new Item(UUID.randomUUID(), "DEPT", code, name, null, null, null, null,
                Item.ACTIVE, sourceSystem, null, null, null, Map.of(),
                Snapshots.fingerprint(name, null, null, null, null, Item.ACTIVE, null, null,
                        Map.of()), 1, Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("dryRun：只算差异，不写条目/不记同步时间")
    void dryRunDoesNotWrite() {
        SyncResult result = service.sync("DEPT", true, "alice", null, null);

        assertThat(result.sourceCount()).isEqualTo(4);
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.unchanged()).isEqualTo(1);
        assertThat(result.missing()).isEqualTo(1);
        assertThat(result.conflicts()).hasSize(1);
        assertThat(result.conflicts().get(0)).contains("MAN1");

        verify(writer, never()).create(anyString(), anyString(), anyString(), any(), any(), any(),
                any(), anyString(), anyString(), any(), any(), any(), any(), anyString(),
                anyString(), any(), anyString(), anyString());
        verify(repo, never()).updateSyncResult(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("应用：新增/更新/缺失各记一次版本，手工条目冲突保留")
    void applyWritesChanges() {
        SyncResult result = service.sync("DEPT", false, "alice", "10.0.0.1", "trace-3");

        assertThat(result.dryRun()).isFalse();
        verify(writer).create(eq("DEPT"), eq("NEW1"), eq("新科室"), any(), any(), any(), any(),
                eq(Item.ACTIVE), eq(Item.SOURCE_IN_HOUSE), eq("NEW1-ref"), any(), any(), any(),
                eq("CREATE"), eq(MasterDataWriter.Ref.SYNC), any(), anyString(), eq("alice"));
        verify(writer).update(any(Item.class), eq("内科一病区"), any(), any(), any(), any(),
                eq(Item.ACTIVE), eq("CH1-ref"), any(), any(), any(), anyString(),
                eq(MasterDataWriter.Ref.SYNC), any(), anyString(), eq("alice"));
        verify(writer).changeStatus(any(Item.class), eq(Item.MISSING_IN_SOURCE), eq("MISSING"),
                anyString(), eq(MasterDataWriter.Ref.SYNC), any(), eq("alice"));
        verify(repo).updateSyncResult(eq("DEPT"), eq("OK"), any());
        verify(repo).insertAudit(eq("alice"), eq("SYNC"), eq("DEPT"), isNull(), isNull(),
                any(), eq("10.0.0.1"), eq("trace-3"));
        verify(writer, never()).changeStatus(any(Item.class), eq(Item.INACTIVE), anyString(),
                anyString(), anyString(), any(), anyString());
    }
}
