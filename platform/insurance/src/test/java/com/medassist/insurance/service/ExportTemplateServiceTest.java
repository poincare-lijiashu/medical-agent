package com.medassist.insurance.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.insurance.domain.InsuranceModel.ExportMessageTemplate;
import com.medassist.insurance.domain.InsuranceModel.TemplateField;
import com.medassist.insurance.repo.InsuranceRepository;
import com.medassist.insurance.web.InsuranceDtos.CreateExportTemplateReq;
import com.medassist.insurance.web.InsuranceDtos.ExportTemplateView;
import com.medassist.insurance.web.InsuranceDtos.TemplateFieldReq;
import com.medassist.insurance.web.InsuranceDtos.UpdateExportTemplateReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 报文模板：字段映射校验、状态机、CAS 与"全局唯一生效模板"冲突。
 */
class ExportTemplateServiceTest {

    private InsuranceRepository repo;
    private ExportTemplateService service;

    private final UUID id = UUID.randomUUID();
    private ExportMessageTemplate stored;

    @BeforeEach
    void setUp() {
        repo = mock(InsuranceRepository.class);
        service = new ExportTemplateService(repo, JsonMapper.builder().build());
    }

    private static List<TemplateFieldReq> coreFields() {
        return List.of(
                new TemplateFieldReq("billNo", "billNo", true, "结算单号"),
                new TemplateFieldReq("patientNo", "patientNo", true, "患者号"),
                new TemplateFieldReq("totalAmount", "totalAmount", true, "总金额"),
                new TemplateFieldReq("insuranceAmount", "insuranceAmount", true, "医保支付"));
    }

    private static final String VALID_FIELDS = """
            [{"element":"billNo","source":"billNo","required":true},
             {"element":"patientNo","source":"patientNo","required":true},
             {"element":"totalAmount","source":"totalAmount","required":true},
             {"element":"insuranceAmount","source":"insuranceAmount","required":true}]
            """;

    private ExportMessageTemplate template(String status, int version, long rowVersion) {
        return new ExportMessageTemplate(id, "PROV_V2", "省局模板", version, status,
                VALID_FIELDS, "yb01", "yb01",
                ExportMessageTemplate.DRAFT.equals(status) ? null : "yb01",
                ExportMessageTemplate.DRAFT.equals(status) ? null : Instant.now(),
                rowVersion, Instant.now(), Instant.now());
    }

    private void echoInsert() {
        org.mockito.Mockito.doAnswer(inv -> {
            stored = inv.getArgument(0);
            return null;
        }).when(repo).insertExportTemplate(any());
        when(repo.findExportTemplate(any())).thenAnswer(inv -> Optional.ofNullable(stored));
    }

    @Test
    @DisplayName("新建草稿：字段规范化落库，初始 version=0 / rowVersion=0")
    void createStoresDraft() {
        echoInsert();

        ExportTemplateView view = service.create(new CreateExportTemplateReq(
                "  PROV_G85  ", "省局模板 G85", coreFields()), "yb01");

        ArgumentCaptor<ExportMessageTemplate> captor =
                ArgumentCaptor.forClass(ExportMessageTemplate.class);
        verify(repo).insertExportTemplate(captor.capture());
        ExportMessageTemplate inserted = captor.getValue();
        assertThat(inserted.code()).isEqualTo("PROV_G85");
        assertThat(inserted.status()).isEqualTo(ExportMessageTemplate.DRAFT);
        assertThat(inserted.version()).isZero();
        assertThat(inserted.rowVersion()).isZero();
        assertThat(inserted.fields()).contains("\"element\":\"billNo\"");
        assertThat(view.status()).isEqualTo(ExportMessageTemplate.DRAFT);
        assertThat(view.fields()).hasSize(4);
        verify(repo).logChange(eq("yb01"), eq("EXPORT_TEMPLATE_CREATE"),
                eq("EXPORT_TEMPLATE"), anyString(), anyString());
    }

    @Test
    @DisplayName("新建：来源字段不受支持 → 422，不落库")
    void createRejectsUnsupportedSource() {
        List<TemplateFieldReq> fields = List.of(
                new TemplateFieldReq("billNo", "billNo", true, null),
                new TemplateFieldReq("patientNo", "patientNo", true, null),
                new TemplateFieldReq("totalAmount", "totalAmount", true, null),
                new TemplateFieldReq("insuranceAmount", "insuranceAmount", true, null),
                new TemplateFieldReq("secret", "patientIdCard", false, null));

        assertThatThrownBy(() -> service.create(
                new CreateExportTemplateReq("PROV_BAD", "bad", fields), "yb01"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.INSURANCE_TEMPLATE_INVALID);
                    assertThat(e.getMessage()).contains("patientIdCard");
                });
        verify(repo, never()).insertExportTemplate(any());
    }

    @Test
    @DisplayName("新建：元素名注入/重复 → 422")
    void createRejectsBadElement() {
        List<TemplateFieldReq> injected = List.of(
                new TemplateFieldReq("billNo\"x", "billNo", true, null));
        assertThatThrownBy(() -> service.create(
                new CreateExportTemplateReq("PROV_BAD", "bad", injected), "yb01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.INSURANCE_TEMPLATE_INVALID));

        List<TemplateFieldReq> duplicated = List.of(
                new TemplateFieldReq("billNo", "billNo", true, null),
                new TemplateFieldReq("BILLNO", "patientNo", true, null),
                new TemplateFieldReq("totalAmount", "totalAmount", true, null),
                new TemplateFieldReq("insuranceAmount", "insuranceAmount", true, null));
        assertThatThrownBy(() -> service.create(
                new CreateExportTemplateReq("PROV_BAD", "bad", duplicated), "yb01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getMessage()).contains("重复"));
    }

    @Test
    @DisplayName("新建：省局核心必填项未声明 → 422")
    void createRejectsMissingCoreRequired() {
        List<TemplateFieldReq> fields = List.of(
                new TemplateFieldReq("billNo", "billNo", true, null),
                new TemplateFieldReq("patientNo", "patientNo", true, null),
                new TemplateFieldReq("totalAmount", "totalAmount", false, null),
                new TemplateFieldReq("insuranceAmount", "insuranceAmount", true, null));

        assertThatThrownBy(() -> service.create(
                new CreateExportTemplateReq("PROV_BAD", "bad", fields), "yb01"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.INSURANCE_TEMPLATE_INVALID);
                    assertThat(e.getMessage()).contains("totalAmount");
                });
    }

    @Test
    @DisplayName("新建：编码重复 → 409")
    void createRejectsDuplicateCode() {
        when(repo.findExportTemplateByCode("PROV_V2"))
                .thenReturn(Optional.of(template(ExportMessageTemplate.DRAFT, 0, 0)));

        assertThatThrownBy(() -> service.create(
                new CreateExportTemplateReq("PROV_V2", "dup", coreFields()), "yb01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INSURANCE_TEMPLATE_DUPLICATE);
        verify(repo, never()).insertExportTemplate(any());
    }

    @Test
    @DisplayName("修改：已发布模板不可改（409），不调用更新")
    void updateRejectsPublished() {
        when(repo.findExportTemplate(id))
                .thenReturn(Optional.of(template(ExportMessageTemplate.PUBLISHED, 1, 2)));

        assertThatThrownBy(() -> service.update(id,
                new UpdateExportTemplateReq("new name", coreFields(), 2L), "yb01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode())
                                .isEqualTo(ErrorCode.INSURANCE_TEMPLATE_STATUS_INVALID));
        verify(repo, never()).updateExportTemplate(any(), anyString(), anyString(),
                anyString(), anyLong());
    }

    @Test
    @DisplayName("修改：CAS 失败 → 409")
    void updateCasConflict() {
        when(repo.findExportTemplate(id))
                .thenReturn(Optional.of(template(ExportMessageTemplate.DRAFT, 0, 1)));
        when(repo.updateExportTemplate(eq(id), anyString(), anyString(), eq("yb01"), eq(1L)))
                .thenReturn(false);

        assertThatThrownBy(() -> service.update(id,
                new UpdateExportTemplateReq("new name", coreFields(), 1L), "yb01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("发布：草稿 → PUBLISHED，version+1 且写审计")
    void publishDraft() {
        ExportMessageTemplate draft = template(ExportMessageTemplate.DRAFT, 0, 0);
        ExportMessageTemplate published = template(ExportMessageTemplate.PUBLISHED, 1, 1);
        when(repo.findExportTemplate(id))
                .thenReturn(Optional.of(draft)).thenReturn(Optional.of(published));
        when(repo.publishExportTemplate(id, "yb01", 0L)).thenReturn(true);

        ExportTemplateView view = service.publish(id, "yb01");

        assertThat(view.status()).isEqualTo(ExportMessageTemplate.PUBLISHED);
        assertThat(view.version()).isEqualTo(1);
        verify(repo).logChange(eq("yb01"), eq("EXPORT_TEMPLATE_PUBLISH"),
                eq("EXPORT_TEMPLATE"), eq(id.toString()), anyString());
    }

    @Test
    @DisplayName("发布：已非草稿 → 409")
    void publishRejectsNonDraft() {
        when(repo.findExportTemplate(id))
                .thenReturn(Optional.of(template(ExportMessageTemplate.DISABLED, 1, 3)));

        assertThatThrownBy(() -> service.publish(id, "yb01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INSURANCE_TEMPLATE_STATUS_INVALID);
    }

    @Test
    @DisplayName("发布：已有生效模板（唯一索引冲突）→ 409")
    void publishRejectsSecondPublished() {
        when(repo.findExportTemplate(id))
                .thenReturn(Optional.of(template(ExportMessageTemplate.DRAFT, 0, 0)));
        when(repo.publishExportTemplate(id, "yb01", 0L)).thenThrow(
                new org.springframework.dao.DataIntegrityViolationException(
                        "duplicate key ux_export_template_single_published"));

        assertThatThrownBy(() -> service.publish(id, "yb01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INSURANCE_TEMPLATE_PUBLISH_CONFLICT);
    }

    @Test
    @DisplayName("停用：仅生效中模板可停用")
    void disableRequiresPublished() {
        when(repo.findExportTemplate(id))
                .thenReturn(Optional.of(template(ExportMessageTemplate.DRAFT, 0, 0)));

        assertThatThrownBy(() -> service.disable(id, "yb01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INSURANCE_TEMPLATE_STATUS_INVALID);
        verify(repo, never()).disableExportTemplate(any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("停用成功：PUBLISHED → DISABLED 并写审计")
    void disablePublished() {
        ExportMessageTemplate published = template(ExportMessageTemplate.PUBLISHED, 1, 1);
        ExportMessageTemplate disabled = template(ExportMessageTemplate.DISABLED, 1, 2);
        when(repo.findExportTemplate(id))
                .thenReturn(Optional.of(published)).thenReturn(Optional.of(disabled));
        when(repo.disableExportTemplate(id, "yb01", 1L)).thenReturn(true);

        ExportTemplateView view = service.disable(id, "yb01");

        assertThat(view.status()).isEqualTo(ExportMessageTemplate.DISABLED);
        verify(repo).logChange(eq("yb01"), eq("EXPORT_TEMPLATE_DISABLE"),
                eq("EXPORT_TEMPLATE"), eq(id.toString()), anyString());
    }

    @Test
    @DisplayName("字段解析：损坏 JSON → 422，不返回半个模板")
    void parseRejectsCorruptJson() {
        assertThatThrownBy(() -> service.parseFields("not-json"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INSURANCE_TEMPLATE_INVALID);
    }

    @Test
    @DisplayName("列表：limit 收敛到 1..100")
    void listClampsLimit() {
        service.list(0);
        service.list(500);
        verify(repo).listExportTemplates(20);
        verify(repo).listExportTemplates(100);
    }
}
