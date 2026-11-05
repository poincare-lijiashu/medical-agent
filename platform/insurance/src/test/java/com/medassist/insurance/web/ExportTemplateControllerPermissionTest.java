package com.medassist.insurance.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.insurance.config.InsuranceMetrics;
import com.medassist.insurance.domain.InsuranceModel.TemplateField;
import com.medassist.insurance.service.ExportTemplateService;
import com.medassist.insurance.web.InsuranceDtos.CreateExportTemplateReq;
import com.medassist.insurance.web.InsuranceDtos.ExportTemplateView;
import com.medassist.insurance.web.InsuranceDtos.TemplateFieldReq;
import com.medassist.insurance.web.InsuranceDtos.UpdateExportTemplateReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 报文模板权限：查看与写操作分离，写操作需 manage。
 */
class ExportTemplateControllerPermissionTest {

    private static final String MANAGE = "insurance:export:template:manage";
    private static final String READ = "insurance:export:template:read";

    private ExportTemplateService service;
    private ExportTemplateController controller;

    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(ExportTemplateService.class);
        controller = new ExportTemplateController(service, mock(InsuranceMetrics.class));
    }

    private ExportTemplateView view() {
        return new ExportTemplateView(id, "PROV_V2", "省局模板", 1, "PUBLISHED",
                List.of(new TemplateField("billNo", "billNo", true, null)),
                "yb01", "yb01", "yb01", Instant.now(), 1L, Instant.now(), Instant.now());
    }

    private CreateExportTemplateReq createReq() {
        return new CreateExportTemplateReq("PROV_V2", "省局模板",
                List.of(new TemplateFieldReq("billNo", "billNo", true, null)));
    }

    @Test
    @DisplayName("查看：read 权限即可，无 read 则 403")
    void listRequiresRead() {
        when(service.list(10)).thenReturn(List.of(view()));

        assertThat(controller.list(10, READ).data()).hasSize(1);
        assertThatThrownBy(() -> controller.list(10, MANAGE + ",other:perm"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("新建：只读权限 403，manage 放行")
    void createRequiresManage() {
        assertThatThrownBy(() -> controller.create(createReq(), READ, "yb01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(service, never()).create(any(), anyString());

        when(service.create(any(), eq("yb01"))).thenReturn(view());
        assertThat(controller.create(createReq(), MANAGE, "yb01").data().code())
                .isEqualTo("PROV_V2");
    }

    @Test
    @DisplayName("修改/发布/停用：均需 manage")
    void writeOperationsRequireManage() {
        var update = new UpdateExportTemplateReq("new", List.of(), 0L);

        assertThatThrownBy(() -> controller.update(id, update, READ, "yb01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> controller.publish(id, READ, "yb01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> controller.disable(id, READ, "yb01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }
}
