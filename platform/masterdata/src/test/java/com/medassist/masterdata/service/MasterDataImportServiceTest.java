package com.medassist.masterdata.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.masterdata.config.MasterDataProperties;
import com.medassist.masterdata.domain.MasterDataModel.Domain;
import com.medassist.masterdata.domain.MasterDataModel.ImportBatch;
import com.medassist.masterdata.domain.MasterDataModel.ImportRow;
import com.medassist.masterdata.domain.MasterDataModel.Item;
import com.medassist.masterdata.repo.MasterDataRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批量导入：预检差异、校验和一致才允许应用、幂等、源同步条目不被覆盖。
 */
class MasterDataImportServiceTest {

    private MasterDataRepository repo;
    private MasterDataWriter writer;
    private MasterDataImportService service;

    @BeforeEach
    void setUp() {
        repo = mock(MasterDataRepository.class);
        writer = mock(MasterDataWriter.class);
        service = new MasterDataImportService(repo, writer, new MasterDataProperties());
        when(repo.findDomain("DEPT")).thenReturn(Optional.of(domain()));
    }

    private Domain domain() {
        return new Domain("DEPT", "科室", "ORGANIZATION", "platform-clinical", "clinical_svc",
                "md_dept", "dept_code", "name", "CLINICAL_DEPT", true, List.of(),
                "科室主数据", 10, true, null, null, Map.of());
    }

    private Item item(String code, String name, String sourceSystem) {
        return new Item(UUID.randomUUID(), "DEPT", code, name, null, null, null, null,
                Item.ACTIVE, sourceSystem, null, null, null, Map.of(),
                Snapshots.fingerprint(name, null, null, null, null, Item.ACTIVE, null, null,
                        Map.of()), 0, Instant.now(), Instant.now());
    }

    private MasterDataImportService.ItemSpec spec(String code, String name) {
        return new MasterDataImportService.ItemSpec(code, name, null, null, null, null,
                null, null, null, Map.of(), List.of());
    }

    @Test
    @DisplayName("JSON 预检：区分新建/更新/不变/语法错误，并落批次与逐行结果")
    void previewJsonClassifiesRows() {
        when(repo.findItem("DEPT", "N1")).thenReturn(Optional.empty());
        when(repo.findItem("DEPT", "E1")).thenReturn(Optional.of(item("E1", "内科", Item.SOURCE_IN_HOUSE)));
        when(repo.findItem("DEPT", "M1")).thenReturn(Optional.of(item("M1", "内科", Item.SOURCE_MANUAL)));

        ImportBatch batch = service.previewJson("DEPT", "depts.json", List.of(
                spec("N1", "心内科"),
                spec("E1", "内科"),
                spec("M1", "内科一病区"),
                spec(" ", "缺编码")), "alice", null, null);

        assertThat(batch.mode()).isEqualTo(ImportBatch.PREVIEW);
        assertThat(batch.status()).isEqualTo(ImportBatch.PREVIEWED);
        assertThat(batch.createCount()).isEqualTo(1);
        assertThat(batch.updateCount()).isEqualTo(1);
        assertThat(batch.unchangedCount()).isEqualTo(1);
        assertThat(batch.errorCount()).isEqualTo(1);
        assertThat(batch.checksum()).hasSize(64);
        verify(repo).insertImportBatch(any());
        verify(repo).insertImportRows(eq(batch.id()), any());
    }

    @Test
    @DisplayName("应用时校验和与预检不一致 → 409，不落库")
    void applyRejectsChecksumMismatch() {
        ImportBatch preview = new ImportBatch(UUID.randomUUID(), "MDM-IMP-P1", ImportBatch.PREVIEW,
                "JSON", "DEPT", "depts.json", "different-checksum", 1, 1, 0, 0, 0, 0,
                ImportBatch.PREVIEWED, Map.of(), "alice", Instant.now(), List.of());
        when(repo.findImportBatchByNo("MDM-IMP-P1")).thenReturn(Optional.of(preview));
        when(repo.findItem("DEPT", "N1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.applyJson("DEPT", "depts.json", List.of(spec("N1", "心内科")),
                "MDM-IMP-P1", "bob", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.MDM_IMPORT_CHECKSUM_MISMATCH);
        verify(writer, never()).create(anyString(), anyString(), anyString(), any(), any(), any(),
                any(), anyString(), anyString(), any(), any(), any(), any(), anyString(),
                anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("同内容重复应用 → 直接返回已应用批次，不重复写版本")
    void applyIsIdempotent() {
        ImportBatch preview = service.previewJson("DEPT", "depts.json",
                List.of(spec("N1", "心内科")), "alice", null, null);
        ImportBatch applied = new ImportBatch(UUID.randomUUID(), "MDM-IMP-A1", ImportBatch.APPLY,
                "JSON", "DEPT", "depts.json", preview.checksum(), 1, 1, 0, 0, 0, 0,
                ImportBatch.APPLIED, Map.of(), "bob", Instant.now(), List.of());
        when(repo.findImportBatchByNo(preview.batchNo())).thenReturn(Optional.of(preview));
        when(repo.findAppliedBatch(eq("DEPT"), eq(preview.checksum()))).thenReturn(Optional.of(applied));
        when(repo.listImportRows(eq(applied.id()), anyInt(), eq(0))).thenReturn(List.of());

        ImportBatch result = service.applyJson("DEPT", "depts.json", List.of(spec("N1", "心内科")),
                preview.batchNo(), "bob", null, null);

        assertThat(result.batchNo()).isEqualTo("MDM-IMP-A1");
        verify(writer, never()).create(anyString(), anyString(), anyString(), any(), any(), any(),
                any(), anyString(), anyString(), any(), any(), any(), any(), anyString(),
                anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("应用成功：写入条目并追加 APPLY 批次")
    void applyCreatesItems() {
        ImportBatch preview = service.previewJson("DEPT", "depts.json",
                List.of(spec("N1", "心内科")), "alice", null, null);
        when(repo.findImportBatchByNo(preview.batchNo())).thenReturn(Optional.of(preview));
        when(repo.findAppliedBatch(eq("DEPT"), eq(preview.checksum()))).thenReturn(Optional.empty());
        when(repo.listImportRows(eq(preview.id()), anyInt(), eq(0)))
                .thenReturn(List.of(new ImportRow(1, 1, "N1", ImportRow.CREATE, null, Map.of())));
        when(repo.findItem("DEPT", "N1")).thenReturn(Optional.empty());
        when(writer.create(anyString(), anyString(), anyString(), any(), any(), any(), any(),
                anyString(), anyString(), any(), any(), any(), any(), anyString(), anyString(),
                any(), anyString(), anyString())).thenReturn(item("N1", "心内科", Item.SOURCE_IMPORT));

        ImportBatch applied = service.applyJson("DEPT", "depts.json", List.of(spec("N1", "心内科")),
                preview.batchNo(), "bob", null, null);

        assertThat(applied.mode()).isEqualTo(ImportBatch.APPLY);
        assertThat(applied.status()).isEqualTo(ImportBatch.APPLIED);
        assertThat(applied.createCount()).isEqualTo(1);
        verify(writer).create(eq("DEPT"), eq("N1"), eq("心内科"), any(), any(), any(), any(),
                eq("ACTIVE"), eq(Item.SOURCE_IMPORT), any(), any(), any(), any(), eq("IMPORT"),
                eq(MasterDataWriter.Ref.IMPORT), eq(preview.batchNo()), anyString(), eq("bob"));
        verify(repo, times(2)).insertImportBatch(any());
    }

    @Test
    @DisplayName("CSV 映射预检：本地编码不存在 → 该行 ERROR，不猜测目标")
    void csvPreviewReportsMissingItem() {
        when(repo.findItem("DEPT", "D1")).thenReturn(Optional.empty());
        String csv = "域编码,本地编码,系统编码,外部编码,外部名称,映射类型,主映射,状态,备注\n"
                + "DEPT,D1,HIS,H-001,内科,EXACT,1,ACTIVE,\n";

        ImportBatch batch = service.previewCsv(csv, "mapping.csv", "alice", null, null);

        assertThat(batch.format()).isEqualTo("CSV");
        assertThat(batch.rowCount()).isEqualTo(1);
        assertThat(batch.errorCount()).isEqualTo(1);
        assertThat(batch.createCount()).isZero();
    }
}
