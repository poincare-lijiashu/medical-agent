package com.medassist.empi.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.empi.crypto.FieldCipher;
import com.medassist.empi.domain.Patient;
import com.medassist.empi.repo.PatientRepository;
import com.medassist.empi.web.dto.CreatePatientReq;
import com.medassist.empi.web.dto.MergePatientReq;
import com.medassist.empi.web.dto.PatientResp;
import com.medassist.empi.web.dto.UpdatePatientReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 患者主索引业务规则（此前 empi 模块零单测，是患者数据正确性的最大盲区）。
 *
 * <p>覆盖：证件号查重、证件类型归一化、脱敏与 reveal 边界、合并的前置校验与锁顺序、
 * 合并/归档后禁止修改与读明文。
 */
class PatientServiceTest {

    private PatientRepository repo;
    private FieldCipher cipher;
    private PatientService service;

    private final UUID patientId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(PatientRepository.class);
        // 用真实加解密实现（AES-GCM 很快），避免 mock 掩盖脱敏/哈希逻辑
        cipher = new FieldCipher(
                Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef"
                        .getBytes(StandardCharsets.UTF_8)),
                "unit-test-salt-0123456789",
                new MockEnvironment().withProperty("spring.application.name", "platform-empi"));
        service = new PatientService(repo, cipher, JsonMapper.builder().build());
    }

    private Patient patient(String status, UUID mergedInto, long version) {
        return new Patient(patientId, "P20260101000001", "张三", "MALE",
                LocalDate.of(1990, 1, 1), "ID_CARD",
                cipher.encrypt("110101199001011234"), cipher.hash("110101199001011234"),
                cipher.encrypt("13800001111"), cipher.hash("13800001111"),
                cipher.encrypt("北京市东城区某街道 1 号"), "A", null,
                status, mergedInto, version, Instant.now(), Instant.now());
    }

    private CreatePatientReq createReq(String idType, String idNo) {
        return new CreatePatientReq("张三", "MALE", LocalDate.of(1990, 1, 1),
                idType, idNo, "13800001111", "北京市东城区某街道 1 号", "A", null);
    }

    // ---------------------------------------------------------------- 检索

    @Test
    @DisplayName("检索：不给患者号也不给姓名 → 422，且不查库（禁止无条件翻全院名册）")
    void searchRequiresCriterion() {
        assertThatThrownBy(() -> service.search(null, "  ", 1, 20, "clerk01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(repo, never()).search(any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("检索：有患者号时返回脱敏结果并写 PATIENT_SEARCH 审计")
    void searchMasksAndAudits() {
        when(repo.search("P20260101000001", null, 20, 0))
                .thenReturn(List.of(patient("ACTIVE", null, 0L)));
        when(repo.count("P20260101000001", null)).thenReturn(1L);
        when(repo.listCrosswalk(patientId)).thenReturn(List.of());

        var result = service.search("P20260101000001", null, 1, 20, "clerk01");

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).phiMasked()).isTrue();
        assertThat(result.items().get(0).idNo()).contains("****");
        verify(repo).logChange(eq("clerk01"), eq("PATIENT_SEARCH"), eq("-"), anyString());
    }

    // ---------------------------------------------------------------- 建档

    @Test
    @DisplayName("建档：证件号已存在 → 409 且消息带出既有档案号（不能建重复患者）")
    void createRejectsDuplicateIdNo() {
        when(repo.findActiveByIdNoHash(eq("ID_CARD"), anyString()))
                .thenReturn(Optional.of(patient(Patient.STATUS_ACTIVE, null, 0L)));

        assertThatThrownBy(() -> service.create(createReq("ID_CARD", "110101199001011234"), "admin01"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.PATIENT_DUPLICATE);
                    assertThat(e.getMessage()).contains("P20260101000001");
                });
        verify(repo, never()).insert(any());
    }

    @Test
    @DisplayName("建档：有证件号但未指定证件类型 → 422（不能把证件号当无类型存）")
    void createRejectsIdNoWithoutType() {
        assertThatThrownBy(() -> service.create(createReq(null, "110101199001011234"), "admin01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(repo, never()).insert(any());
    }

    @Test
    @DisplayName("建档：无证件号时类型归一为 NONE，且不查重、不写哈希")
    void createWithoutIdNoSkipsDeduplication() {
        when(repo.nextPatientNo()).thenReturn("P20260101000002");
        when(repo.findById(any())).thenAnswer(inv -> Optional.of(
                new Patient(inv.getArgument(0), "P20260101000002", "李四", "FEMALE",
                        null, "NONE", null, null, null, null, null, null, null,
                        Patient.STATUS_ACTIVE, null, 0L, Instant.now(), Instant.now())));
        when(repo.listCrosswalk(any())).thenReturn(List.of());

        service.create(new CreatePatientReq("李四", "FEMALE", null, null, null, null, null, null, null),
                "admin01");

        verify(repo, never()).findActiveByIdNoHash(any(), any());
        verify(repo).insert(org.mockito.ArgumentMatchers.argThat(p ->
                "NONE".equals(p.idType()) && p.idNoHash() == null && p.idNoEnc() == null));
    }

    // ---------------------------------------------------------------- 脱敏与 reveal

    @Test
    @DisplayName("详情：默认脱敏（证件号前 4 后 4、手机前 3 后 4、地址截断），且不写 PHI 读审计")
    void detailMasksPhiByDefault() {
        when(repo.findById(patientId)).thenReturn(Optional.of(
                patient(Patient.STATUS_ACTIVE, null, 3L)));
        when(repo.listCrosswalk(patientId)).thenReturn(List.of());

        PatientResp resp = service.detail(patientId, false, "doctor01");

        assertThat(resp.idNo()).isEqualTo("1101****1234");
        assertThat(resp.phone()).isEqualTo("138****1111");
        assertThat(resp.address()).isEqualTo("北京市东城区******");
        assertThat(resp.phiMasked()).isTrue();
        verify(repo, never()).logChange(anyString(), eq("PATIENT_VIEW_PHI"), anyString(), anyString());
    }

    @Test
    @DisplayName("详情 reveal=true：返回明文并强制写 PHI 读审计")
    void detailRevealsPhiWithAudit() {
        when(repo.findById(patientId)).thenReturn(Optional.of(
                patient(Patient.STATUS_ACTIVE, null, 3L)));
        when(repo.listCrosswalk(patientId)).thenReturn(List.of());

        PatientResp resp = service.detail(patientId, true, "admin01");

        assertThat(resp.idNo()).isEqualTo("110101199001011234");
        assertThat(resp.phiMasked()).isFalse();
        verify(repo).logChange(eq("admin01"), eq("PATIENT_VIEW_PHI"), eq(patientId.toString()),
                anyString());
    }

    @Test
    @DisplayName("详情 reveal=true 且档案已合并 → 409（不能绕过合并后的授权从旧档案读明文）")
    void detailRejectsRevealOnMergedPatient() {
        when(repo.findById(patientId)).thenReturn(Optional.of(
                patient(Patient.STATUS_MERGED, UUID.randomUUID(), 4L)));

        assertThatThrownBy(() -> service.detail(patientId, true, "admin01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.PATIENT_MERGE_CONFLICT));
        verify(repo, never()).logChange(anyString(), eq("PATIENT_VIEW_PHI"), anyString(), anyString());
    }

    // ---------------------------------------------------------------- 修改

    @Test
    @DisplayName("修改：档案已合并 → 409（防止往废弃档案写数据）")
    void updateRejectsMergedPatient() {
        when(repo.findById(patientId)).thenReturn(Optional.of(
                patient(Patient.STATUS_MERGED, UUID.randomUUID(), 1L)));

        assertThatThrownBy(() -> service.update(patientId,
                new UpdatePatientReq("张三", "MALE", null, null, null, null, null, null, null, 1L),
                "admin01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(repo, never()).update(any(), anyString(), anyString(), any(), anyString(),
                any(), any(), any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("修改：版本号已变化 → 409（乐观锁）")
    void updateRejectsStaleVersion() {
        when(repo.findById(patientId)).thenReturn(Optional.of(
                patient(Patient.STATUS_ACTIVE, null, 5L)));
        when(repo.update(any(), anyString(), anyString(), any(), anyString(), any(), any(),
                any(), any(), any(), any(), any(), anyLong())).thenReturn(false);

        assertThatThrownBy(() -> service.update(patientId,
                new UpdatePatientReq("张三", "MALE", null, null, null, null, null, null, null, 3L),
                "admin01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("修改：改证件号与其它档案冲突（排除自身）→ 409")
    void updateRejectsIdNoOwnedByAnotherPatient() {
        when(repo.findById(patientId)).thenReturn(Optional.of(
                patient(Patient.STATUS_ACTIVE, null, 1L)));
        Patient other = new Patient(UUID.randomUUID(), "P20260101000009", "李四", "FEMALE",
                null, "ID_CARD", "enc", "hash", null, null, null, null, null,
                Patient.STATUS_ACTIVE, null, 0L, Instant.now(), Instant.now());
        when(repo.findActiveByIdNoHash(eq("ID_CARD"), anyString())).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.update(patientId,
                new UpdatePatientReq("张三", "MALE", null, "ID_CARD", "110101199001011234",
                        null, null, null, null, 1L), "admin01"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.PATIENT_DUPLICATE);
                    assertThat(e.getMessage()).contains("P20260101000009");
                });
    }

    // ---------------------------------------------------------------- 合并

    @Test
    @DisplayName("合并：源与目标相同 → 422")
    void mergeRejectsSamePatient() {
        assertThatThrownBy(() -> service.merge(
                new MergePatientReq(patientId, patientId, "重复建档"), "admin01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(repo, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("合并：按 id 稳定顺序加两把行锁（防 A→B / B→A 并发成环）")
    void mergeLocksInStableOrder() {
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID high = UUID.fromString("00000000-0000-0000-0000-000000000002");
        // 请求方向故意与 id 顺序相反：源=high、目标=low，验证加锁顺序仍按 id 升序
        when(repo.findById(low)).thenReturn(Optional.of(new Patient(low, "P1", "甲", "MALE",
                null, "NONE", null, null, null, null, null, null, null,
                Patient.STATUS_ACTIVE, null, 1L, Instant.now(), Instant.now())));
        when(repo.findById(high)).thenReturn(Optional.of(new Patient(high, "P2", "乙", "MALE",
                null, "NONE", null, null, null, null, null, null, null,
                Patient.STATUS_ACTIVE, null, 1L, Instant.now(), Instant.now())));
        when(repo.markMerged(high, low, 1L)).thenReturn(true);
        when(repo.moveCrosswalk(high, low)).thenReturn(2);
        when(repo.listCrosswalk(low)).thenReturn(List.of());

        service.merge(new MergePatientReq(high, low, "同一人两次建档"), "admin01");

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(repo);
        order.verify(repo).findByIdForUpdate(low);
        order.verify(repo).findByIdForUpdate(high);
        order.verify(repo).markMerged(high, low, 1L);
        verify(repo).logMerge(eq(high), eq(low), eq("admin01"), eq("同一人两次建档"), eq(2));
    }

    @Test
    @DisplayName("合并：源档案已合并 → 409（不能链式合并到已作废档案）")
    void mergeRejectsSourceAlreadyMerged() {
        UUID from = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID to = UUID.fromString("00000000-0000-0000-0000-000000000002");
        when(repo.findById(from)).thenReturn(Optional.of(new Patient(from, "P1", "甲", "MALE",
                null, "NONE", null, null, null, null, null, null, null,
                Patient.STATUS_MERGED, UUID.randomUUID(), 2L, Instant.now(), Instant.now())));
        when(repo.findById(to)).thenReturn(Optional.of(new Patient(to, "P2", "乙", "MALE",
                null, "NONE", null, null, null, null, null, null, null,
                Patient.STATUS_ACTIVE, null, 1L, Instant.now(), Instant.now())));

        assertThatThrownBy(() -> service.merge(new MergePatientReq(from, to, "重复"), "admin01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.PATIENT_MERGE_CONFLICT));
        verify(repo, never()).markMerged(any(), any(), anyLong());
    }

    @Test
    @DisplayName("合并：目标档案已归档 → 409")
    void mergeRejectsInactiveTarget() {
        UUID from = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID to = UUID.fromString("00000000-0000-0000-0000-000000000002");
        when(repo.findById(from)).thenReturn(Optional.of(new Patient(from, "P1", "甲", "MALE",
                null, "NONE", null, null, null, null, null, null, null,
                Patient.STATUS_ACTIVE, null, 1L, Instant.now(), Instant.now())));
        when(repo.findById(to)).thenReturn(Optional.of(new Patient(to, "P2", "乙", "MALE",
                null, "NONE", null, null, null, null, null, null, null,
                Patient.STATUS_DECEASED, null, 1L, Instant.now(), Instant.now())));

        assertThatThrownBy(() -> service.merge(new MergePatientReq(from, to, "重复"), "admin01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.PATIENT_MERGE_CONFLICT));
        verify(repo, never()).markMerged(any(), any(), anyLong());
    }

    @Test
    @DisplayName("合并：markMerged 版本冲突 → 409（并发合并只允许一个成功）")
    void mergeRejectsConcurrentVersionChange() {
        UUID from = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID to = UUID.fromString("00000000-0000-0000-0000-000000000002");
        when(repo.findById(from)).thenReturn(Optional.of(new Patient(from, "P1", "甲", "MALE",
                null, "NONE", null, null, null, null, null, null, null,
                Patient.STATUS_ACTIVE, null, 3L, Instant.now(), Instant.now())));
        when(repo.findById(to)).thenReturn(Optional.of(new Patient(to, "P2", "乙", "MALE",
                null, "NONE", null, null, null, null, null, null, null,
                Patient.STATUS_ACTIVE, null, 1L, Instant.now(), Instant.now())));
        when(repo.markMerged(from, to, 3L)).thenReturn(false);

        assertThatThrownBy(() -> service.merge(new MergePatientReq(from, to, "重复"), "admin01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(repo, never()).moveCrosswalk(any(), any());
    }

    // ---------------------------------------------------------------- 跨系统标识

    @Test
    @DisplayName("登记外部标识：档案已合并 → 409")
    void addCrosswalkRejectsMergedPatient() {
        when(repo.findById(patientId)).thenReturn(Optional.of(
                patient(Patient.STATUS_MERGED, UUID.randomUUID(), 1L)));

        assertThatThrownBy(() -> service.addCrosswalk(patientId, "HIS", "X-1", "admin01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(repo, never()).addCrosswalk(any(), anyString(), anyString());
    }
}
