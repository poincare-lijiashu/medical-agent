package com.medassist.migration.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.FieldCipher;
import com.medassist.migration.config.MigrationProperties;
import com.medassist.migration.domain.MigrationModel;
import com.medassist.migration.domain.MigrationModel.Batch;
import com.medassist.migration.domain.MigrationModel.Target;
import com.medassist.migration.domain.MigrationRequests.PreviewReq;
import com.medassist.migration.repo.MigrationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 迁移编排：预检计数、阻塞批次拒绝、状态机与操作人边界。
 */
class MigrationServiceTest {

    private MigrationRepository repo;
    private TargetEngine engine;
    private FieldCipher cipher;
    private MigrationService service;

    @BeforeEach
    void setUp() {
        repo = mock(MigrationRepository.class);
        engine = mock(TargetEngine.class);
        cipher = new FieldCipher(
                Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef"
                        .getBytes(StandardCharsets.UTF_8)),
                "unit-test-salt-0123456789",
                new MockEnvironment().withProperty("spring.application.name", "platform-migration"));
        service = new MigrationService(repo, new RowNormalizer(), engine, cipher,
                new MigrationProperties(), JsonMapper.builder().build(),
                mock(PlatformTransactionManager.class));
    }

    @Test
    @DisplayName("预检：两行都判定为新增，计数与加密载荷落库")
    void previewCounts() {
        when(repo.findTarget("EMPI_PATIENT")).thenReturn(java.util.Optional.of(
                target("EMPI_PATIENT", 60)));
        when(engine.evaluate(any(), any())).thenAnswer(invocation -> {
            NormalizedRow row = invocation.getArgument(1);
            return new TargetEngine.Evaluation(MigrationModel.CREATE, null, 0,
                    "patient_no=" + row.values().get("patient_no"), Map.of(), Map.of(), null);
        });
        when(repo.findBatch(any())).thenReturn(java.util.Optional.of(
                batch(UUID.randomUUID(), "MIG-1", MigrationModel.PREVIEWED, 0, 0)));
        when(repo.listRows(any(), anyInt(), anyInt())).thenReturn(List.of());
        ArgumentCaptor<Batch> batchCaptor = ArgumentCaptor.forClass(Batch.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Integer, String>> payloadCaptor = ArgumentCaptor.forClass(Map.class);

        PreviewReq req = new PreviewReq("EMPI_PATIENT", "HIS", "patients.csv", null,
                List.of(Map.of("patient_no", "P1", "name", "张三"),
                        Map.of("patient_no", "P2", "name", "李四")), null);
        service.preview(req, "tester", null, null);

        verify(repo).insertBatch(batchCaptor.capture(), any(), payloadCaptor.capture());
        Batch saved = batchCaptor.getValue();
        assertThat(saved.rowCount()).isEqualTo(2);
        assertThat(saved.createCount()).isEqualTo(2);
        assertThat(saved.errorCount()).isZero();
        assertThat(saved.status()).isEqualTo(MigrationModel.PREVIEWED);
        assertThat(payloadCaptor.getValue()).hasSize(2);
        assertThat(payloadCaptor.getValue().values()).allMatch(v -> v != null && !v.isBlank());
        verify(repo).audit(any(), any(), any(), any(), anyMap(), any(), any());
    }

    @Test
    @DisplayName("预检：坏行进入 ERROR 而不是让整批失败")
    void previewBadRow() {
        when(repo.findTarget("EMPI_PATIENT")).thenReturn(java.util.Optional.of(
                target("EMPI_PATIENT", 60)));
        when(engine.evaluate(any(), any())).thenReturn(new TargetEngine.Evaluation(
                MigrationModel.CREATE, null, 0, "patient_no=P1", Map.of(), Map.of(), null));
        when(repo.findBatch(any())).thenReturn(java.util.Optional.of(
                batch(UUID.randomUUID(), "MIG-2", MigrationModel.PREVIEWED, 0, 1)));
        when(repo.listRows(any(), anyInt(), anyInt())).thenReturn(List.of());
        ArgumentCaptor<Batch> batchCaptor = ArgumentCaptor.forClass(Batch.class);

        PreviewReq req = new PreviewReq("EMPI_PATIENT", "HIS", null, null,
                List.of(Map.of("patient_no", "P1", "name", "张三"),
                        Map.of("patient_no", "P2")), null);
        service.preview(req, "tester", null, null);

        verify(repo).insertBatch(batchCaptor.capture(), any(), anyMap());
        assertThat(batchCaptor.getValue().errorCount()).isEqualTo(1);
        assertThat(batchCaptor.getValue().createCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("预检：同一自然键在文件内重复，后一条记 ERROR 而不是两次覆盖")
    void previewDuplicateNaturalKey() {
        when(repo.findTarget("EMPI_PATIENT")).thenReturn(java.util.Optional.of(
                target("EMPI_PATIENT", 60)));
        when(engine.evaluate(any(), any())).thenReturn(new TargetEngine.Evaluation(
                MigrationModel.CREATE, null, 0, "patient_no=P1", Map.of(), Map.of(), null));
        when(repo.findBatch(any())).thenReturn(java.util.Optional.of(
                batch(UUID.randomUUID(), "MIG-DUP", MigrationModel.PREVIEWED, 0, 1)));
        when(repo.listRows(any(), anyInt(), anyInt())).thenReturn(List.of());
        ArgumentCaptor<Batch> batchCaptor = ArgumentCaptor.forClass(Batch.class);

        PreviewReq req = new PreviewReq("EMPI_PATIENT", "HIS", null, null,
                List.of(Map.of("patient_no", "P1", "name", "张三"),
                        Map.of("patient_no", "P1", "name", "张三（重复）")), null);
        service.preview(req, "tester", null, null);

        verify(repo).insertBatch(batchCaptor.capture(), any(), anyMap());
        assertThat(batchCaptor.getValue().errorCount()).isEqualTo(1);
        assertThat(batchCaptor.getValue().createCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("应用：存在冲突/错误行的批次整批拒绝")
    void applyRejectsBlockingBatch() {
        UUID id = UUID.randomUUID();
        when(repo.findBatch(id)).thenReturn(java.util.Optional.of(
                batch(id, "MIG-1", MigrationModel.PREVIEWED, 1, 0)));

        assertThatThrownBy(() -> service.apply(id, "tester", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.MIG_BATCH_HAS_BLOCKING_ROWS);
        verify(engine, never()).apply(any(), any(), any());
    }

    @Test
    @DisplayName("应用：非预检状态不允许再次应用")
    void applyRequiresPreviewed() {
        UUID id = UUID.randomUUID();
        when(repo.findBatch(id)).thenReturn(java.util.Optional.of(
                batch(id, "MIG-2", MigrationModel.ROLLED_BACK, 0, 0)));

        assertThatThrownBy(() -> service.apply(id, "tester", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.MIG_BATCH_STATUS_INVALID);
    }

    @Test
    @DisplayName("写操作必须带真实操作人")
    void actorRequired() {
        assertThatThrownBy(() -> service.apply(UUID.randomUUID(), " ", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.MIG_ACTOR_REQUIRED);
    }

    @Test
    @DisplayName("回滚必须填写原因，且不允许重复批次号")
    void rollbackGuards() {
        assertThatThrownBy(() -> service.rollback(List.of("MIG-1"), "x", "tester", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.MIG_REASON_REQUIRED);

        assertThatThrownBy(() -> service.rollback(List.of("MIG-1", "MIG-1"), "数据有误", "tester",
                null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("目标列表：数据库里存在但代码未实现的目标不暴露模板")
    void listTargetsSkipsUnknownSpec() {
        when(repo.listTargets(true)).thenReturn(List.of(
                target("EMPI_PATIENT", 60), target("UNKNOWN_TARGET", 999)));
        when(repo.listFields(any())).thenReturn(List.of());
        when(repo.listDependsOn(any())).thenReturn(List.of());
        when(repo.listRequiredBy(any())).thenReturn(List.of());

        assertThat(service.listTargets()).extracting(d -> d.target().code())
                .containsExactly("EMPI_PATIENT");
    }

    @Test
    @DisplayName("模板：表头来自字段字典，带 UTF-8 BOM")
    void template() {
        when(repo.listFields("EMPI_PATIENT")).thenReturn(List.of(
                new MigrationModel.FieldDef("patient_no", "患者号", "TEXT", true, 32,
                        null, null, null, 10),
                new MigrationModel.FieldDef("name", "姓名", "TEXT", true, 64,
                        null, null, null, 20)));

        assertThat(service.template("EMPI_PATIENT"))
                .isEqualTo("\uFEFFpatient_no,name\r\n");
    }

    private static Target target(String code, int sortNo) {
        return new Target(code, code, "PATIENT", "empi_svc", "patient", "patient_no",
                "UPSERT", sortNo, null);
    }

    private static Batch batch(UUID id, String batchNo, String status,
                               int conflicts, int errors) {
        return new Batch(id, batchNo, "EMPI_PATIENT", "HIS", "patients.csv", null,
                "checksum", 2, 1, 0, 0, conflicts, errors, status, Map.of(),
                "tester", Instant.now(), null, null, null, List.of());
    }
}
