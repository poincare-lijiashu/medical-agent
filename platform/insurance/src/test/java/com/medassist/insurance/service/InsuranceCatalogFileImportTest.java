package com.medassist.insurance.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.insurance.client.ClinicalClient;
import com.medassist.insurance.client.PatientClient;
import com.medassist.insurance.domain.InsuranceModel.InsuranceItem;
import com.medassist.insurance.repo.InsuranceRepository;
import com.medassist.insurance.web.InsuranceDtos.CatalogImportResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 目录文件导入：差异预览、幂等落库、缺列不覆盖与审计。
 */
class InsuranceCatalogFileImportTest {

    private InsuranceRepository repo;
    private InsuranceService service;

    @BeforeEach
    void setUp() {
        repo = mock(InsuranceRepository.class);
        service = new InsuranceService(repo, mock(PatientClient.class), mock(ClinicalClient.class),
                JsonMapper.builder().build());
    }

    private static byte[] csv(String body) {
        return ("code,name,category,payCategory\n" + body).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] csvWithRatio(String body) {
        return ("code,name,category,payCategory,selfPayRatio\n" + body)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static InsuranceItem item(String code, String name, String category, String pay,
                                      String ratio, String limit, boolean enabled, long version) {
        return new InsuranceItem(UUID.randomUUID(), code, name, category, pay,
                ratio == null ? null : new BigDecimal(ratio),
                limit == null ? null : new BigDecimal(limit), enabled, version);
    }

    @Test
    @DisplayName("预览：只比对不写库，返回新建/更新/未变与未覆盖数量")
    void previewReportsChangesWithoutWriting() {
        when(repo.findItemsByCodes(anyList()))
                .thenReturn(List.of(item("A", "旧名称", "DRUG", "A", "0.00", null, true, 3L)));
        when(repo.countEnabledItemsNotInCodes(anyList())).thenReturn(7L);

        CatalogImportResult result = service.importCatalogFile("catalog.csv",
                csvWithRatio("A,新名称,DRUG,A,\nB,新增项目,SERVICE,B,0.1\n"), "preview", "yb01");

        assertThat(result.mode()).isEqualTo("PREVIEW");
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.unchanged()).isZero();
        assertThat(result.missingInFile()).isEqualTo(7);
        assertThat(result.sha256()).hasSize(64);
        assertThat(result.changes()).hasSize(2);
        assertThat(result.changes()).anyMatch(c -> c.insuranceCode().equals("A")
                && c.action().equals("UPDATE") && c.fields().contains("name"));
        assertThat(result.changes()).anyMatch(c -> c.insuranceCode().equals("B")
                && c.action().equals("CREATE"));
        verify(repo, never()).upsertItem(any(), any());
        verify(repo, never()).logChange(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("落库：内容未变的目录项不写库、不升版本（幂等）")
    void applyWritesOnlyChangedItems() {
        when(repo.findItemsByCodes(anyList())).thenReturn(List.of(
                item("A", "同名", "DRUG", "A", "0.00", null, true, 3L),
                item("STALE", "未覆盖", "DRUG", "A", "0.00", null, true, 1L)));
        when(repo.countEnabledItemsNotInCodes(anyList())).thenReturn(1L);

        CatalogImportResult result = service.importCatalogFile("catalog.csv",
                csvWithRatio("A,同名,DRUG,A,\nB,新增项目,SERVICE,B,0.1\n"), "apply", "yb01");

        assertThat(result.mode()).isEqualTo("APPLY");
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.updated()).isZero();
        assertThat(result.unchanged()).isEqualTo(1);
        ArgumentCaptor<InsuranceItem> captor = ArgumentCaptor.forClass(InsuranceItem.class);
        verify(repo).upsertItem(captor.capture(), isNull());
        assertThat(captor.getValue().insuranceCode()).isEqualTo("B");
        assertThat(captor.getValue().enabled()).isTrue();
        assertThat(captor.getValue().selfPayRatio()).isEqualByComparingTo("0.1");
        verify(repo).logChange(eq("yb01"), eq("ITEM_FILE_UPSERT"), eq("INSURANCE_ITEM"),
                eq("B"), anyString());
        verify(repo).logChange(eq("yb01"), eq("ITEM_FILE_IMPORT"), eq("INSURANCE_ITEM"),
                eq("file"), anyString());
    }

    @Test
    @DisplayName("重复 apply 同一文件：全部 unchanged，零写入但仍留汇总审计")
    void applyIsIdempotentWhenNothingChanged() {
        when(repo.findItemsByCodes(anyList())).thenReturn(List.of(
                item("A", "同名", "DRUG", "A", "0.00", null, true, 3L)));

        CatalogImportResult result = service.importCatalogFile("catalog.csv",
                csv("A,同名,DRUG,A\n"), "apply", "yb01");

        assertThat(result.created()).isZero();
        assertThat(result.updated()).isZero();
        assertThat(result.unchanged()).isEqualTo(1);
        verify(repo, never()).upsertItem(any(), any());
        verify(repo).logChange(eq("yb01"), eq("ITEM_FILE_IMPORT"), eq("INSURANCE_ITEM"),
                eq("file"), anyString());
    }

    @Test
    @DisplayName("缺列不覆盖：文件未给出的比例/限价/状态沿用库里原值（停用项不被隐式复活）")
    void omittedColumnsPreserveExistingValues() {
        when(repo.findItemsByCodes(anyList())).thenReturn(List.of(
                item("C", "旧名称", "DRUG", "A", "0.20", "10.00", false, 2L)));

        service.importCatalogFile("catalog.csv", csv("C,新名称,DRUG,B\n"), "apply", "yb01");

        ArgumentCaptor<InsuranceItem> captor = ArgumentCaptor.forClass(InsuranceItem.class);
        verify(repo).upsertItem(captor.capture(), isNull());
        InsuranceItem saved = captor.getValue();
        assertThat(saved.payCategory()).isEqualTo("B");
        assertThat(saved.selfPayRatio()).isEqualByComparingTo("0.20");
        assertThat(saved.priceLimit()).isEqualByComparingTo("10.00");
        assertThat(saved.enabled()).isFalse();
    }

    @Test
    @DisplayName("乙类新建缺自付比例：整体拒绝并指出行号（MF-03）")
    void rejectsClassBWithoutRatio() {
        assertThatThrownBy(() -> service.importCatalogFile("catalog.csv",
                csv("B,新增乙类,SERVICE,B\n"), "preview", "yb01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("自付比例")
                .hasMessageContaining("第 2 行");
        verify(repo, never()).upsertItem(any(), any());
    }

    @Test
    @DisplayName("乙类更新未给比例：允许沿用库里原值（不是静默清零）")
    void classBUpdateKeepsExistingRatio() {
        when(repo.findItemsByCodes(anyList())).thenReturn(List.of(
                item("B", "旧乙类", "SERVICE", "B", "0.20", null, true, 2L)));

        service.importCatalogFile("catalog.csv", csv("B,新乙类,SERVICE,B\n"), "apply", "yb01");

        ArgumentCaptor<InsuranceItem> captor = ArgumentCaptor.forClass(InsuranceItem.class);
        verify(repo).upsertItem(captor.capture(), isNull());
        assertThat(captor.getValue().selfPayRatio()).isEqualByComparingTo("0.20");
    }

    @Test
    @DisplayName("非法 mode：422")
    void invalidModeRejected() {
        assertThatThrownBy(() -> service.importCatalogFile("catalog.csv", csv("A,甲,DRUG,A\n"),
                "publish", "yb01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("文件校验失败：整体拒绝，不查库")
    void parseErrorsRejectWholeFile() {
        assertThatThrownBy(() -> service.importCatalogFile("catalog.csv",
                csv("A,甲,DRUG,Z\n"), "apply", "yb01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("目录文件校验失败");
        verify(repo, never()).findItemsByCodes(anyList());
    }

    @Test
    @DisplayName("Excel 文件：422 并提示另存为 CSV")
    void excelRejected() {
        assertThatThrownBy(() -> service.importCatalogFile("catalog.xlsx", new byte[]{1, 2},
                "preview", "yb01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Excel");
    }

    @Test
    @DisplayName("超过 5 MB：422")
    void oversizeRejected() {
        byte[] huge = new byte[5 * 1024 * 1024 + 1];

        assertThatThrownBy(() -> service.importCatalogFile("catalog.csv", huge, "preview", "yb01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("5 MB");
    }
}
