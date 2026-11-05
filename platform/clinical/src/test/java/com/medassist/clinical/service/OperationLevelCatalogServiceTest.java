package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.OperationCatalog;
import com.medassist.clinical.domain.ClinicalModel.OperationLevel;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.SurgeryLevelDtos.UpsertOperationLevelReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 手术分级目录规则：编码必须启用、级别 1~4、修改/删除需 CAS。 */
class OperationLevelCatalogServiceTest {

    private ClinicalRepository repo;
    private OperationLevelCatalogService service;

    private final String code = "81.5401";

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        service = new OperationLevelCatalogService(repo);
    }

    private OperationCatalog catalog(boolean enabled) {
        return new OperationCatalog(code, "全膝关节置换术", "骨科", enabled, 0L);
    }

    private OperationLevel level(int value, long version) {
        return new OperationLevel(code, value, null, "admin01", version, Instant.now(),
                Instant.now());
    }

    // ---------------------------------------------------------------- 列表

    @Test
    @DisplayName("列表：limit 收敛到 100 并透传关键字")
    void listClampsLimit() {
        when(repo.listOperationLevels(any(), anyInt())).thenReturn(List.of());

        service.list(" 膝 ", 500);
        verify(repo).listOperationLevels(eq(" 膝 "), eq(100));

        service.list(null, 0);
        verify(repo).listOperationLevels(eq(null), eq(100));
    }

    // ---------------------------------------------------------------- 新增/修改

    @Test
    @DisplayName("新增：级别越界 → 422")
    void upsertRejectsInvalidLevel() {
        assertThatThrownBy(() -> service.upsert(code,
                new UpsertOperationLevelReq(9, null, null), "admin01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.OPERATION_LEVEL_INVALID));
        verify(repo, never()).insertOperationLevel(any());
    }

    @Test
    @DisplayName("新增：编码不在字典 → 422（不允许埋永远匹配不上的配置）")
    void upsertRejectsUnknownCode() {
        when(repo.findOperation(code)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upsert(code,
                new UpsertOperationLevelReq(3, null, null), "admin01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.OPERATION_LEVEL_INVALID));
    }

    @Test
    @DisplayName("新增：编码已停用 → 422")
    void upsertRejectsDisabledCode() {
        when(repo.findOperation(code)).thenReturn(Optional.of(catalog(false)));

        assertThatThrownBy(() -> service.upsert(code,
                new UpsertOperationLevelReq(3, null, null), "admin01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.OPERATION_LEVEL_INVALID));
        verify(repo, never()).insertOperationLevel(any());
    }

    @Test
    @DisplayName("新增：首次配置落版本 0 并留审计")
    void upsertCreates() {
        when(repo.findOperation(code)).thenReturn(Optional.of(catalog(true)));
        when(repo.findOperationLevel(code))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(level(3, 0L)));

        OperationLevel result = service.upsert(code,
                new UpsertOperationLevelReq(3, " 关节置换默认三级 ", null), "admin01");

        ArgumentCaptor<OperationLevel> captor = ArgumentCaptor.forClass(OperationLevel.class);
        verify(repo).insertOperationLevel(captor.capture());
        assertThat(captor.getValue().level()).isEqualTo(3);
        assertThat(captor.getValue().note()).isEqualTo("关节置换默认三级");
        assertThat(captor.getValue().updatedBy()).isEqualTo("admin01");
        assertThat(result.level()).isEqualTo(3);
        verify(repo).logChange(eq("admin01"), eq("OPERATION_LEVEL_SET"),
                eq("OPERATION_LEVEL"), eq(code), anyString());
    }

    @Test
    @DisplayName("新增：并发插入撞主键 → 409")
    void upsertCreateRaceConflicts() {
        when(repo.findOperation(code)).thenReturn(Optional.of(catalog(true)));
        when(repo.findOperationLevel(code)).thenReturn(Optional.empty());
        doThrow(new DataIntegrityViolationException("operation_level_pkey"))
                .when(repo).insertOperationLevel(any());

        assertThatThrownBy(() -> service.upsert(code,
                new UpsertOperationLevelReq(3, null, null), "admin01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.OPERATION_LEVEL_CONFLICT));
    }

    @Test
    @DisplayName("修改：不带 version → 422")
    void upsertUpdateRequiresVersion() {
        when(repo.findOperation(code)).thenReturn(Optional.of(catalog(true)));
        when(repo.findOperationLevel(code)).thenReturn(Optional.of(level(2, 1L)));

        assertThatThrownBy(() -> service.upsert(code,
                new UpsertOperationLevelReq(3, null, null), "admin01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(repo, never()).updateOperationLevel(anyString(), anyInt(), any(), anyString(),
                anyLong());
    }

    @Test
    @DisplayName("修改：CAS 失败 → 409")
    void upsertUpdateConflicts() {
        when(repo.findOperation(code)).thenReturn(Optional.of(catalog(true)));
        when(repo.findOperationLevel(code)).thenReturn(Optional.of(level(2, 5L)));
        when(repo.updateOperationLevel(code, 3, null, "admin01", 5L)).thenReturn(false);

        assertThatThrownBy(() -> service.upsert(code,
                new UpsertOperationLevelReq(3, null, 5L), "admin01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.OPERATION_LEVEL_CONFLICT));
    }

    @Test
    @DisplayName("修改成功：按版本号更新并留审计")
    void upsertUpdates() {
        when(repo.findOperation(code)).thenReturn(Optional.of(catalog(true)));
        when(repo.findOperationLevel(code))
                .thenReturn(Optional.of(level(2, 5L)))
                .thenReturn(Optional.of(level(4, 6L)));
        when(repo.updateOperationLevel(code, 4, null, "admin01", 5L)).thenReturn(true);

        OperationLevel result = service.upsert(code,
                new UpsertOperationLevelReq(4, null, 5L), "admin01");

        assertThat(result.level()).isEqualTo(4);
        verify(repo).updateOperationLevel(code, 4, null, "admin01", 5L);
    }

    // ---------------------------------------------------------------- 删除

    @Test
    @DisplayName("删除：目录项不存在 → 404")
    void deleteRejectsMissing() {
        when(repo.findOperationLevel(code)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(code, 0L, "admin01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.OPERATION_LEVEL_NOT_FOUND));
    }

    @Test
    @DisplayName("删除：版本过期 → 409")
    void deleteConflictsOnStaleVersion() {
        when(repo.findOperationLevel(code)).thenReturn(Optional.of(level(3, 2L)));
        when(repo.deleteOperationLevel(code, 9L)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(code, 9L, "admin01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.OPERATION_LEVEL_CONFLICT));
    }

    @Test
    @DisplayName("删除成功：留审计，之后计划同类手术不再带出分级")
    void deleteOk() {
        when(repo.findOperationLevel(code)).thenReturn(Optional.of(level(3, 2L)));
        when(repo.deleteOperationLevel(code, 2L)).thenReturn(true);

        service.delete(code, 2L, "admin01");

        verify(repo).deleteOperationLevel(code, 2L);
        verify(repo).logChange(eq("admin01"), eq("OPERATION_LEVEL_DELETE"),
                eq("OPERATION_LEVEL"), eq(code), anyString());
    }
}
