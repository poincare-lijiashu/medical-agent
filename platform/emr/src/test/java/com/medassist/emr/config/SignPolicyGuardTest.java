package com.medassist.emr.config;

import com.medassist.emr.repo.EmrRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 启动守卫：模板文书类型必须都有归档签名策略（第四轮 SF-17）。
 */
class SignPolicyGuardTest {

    private final EmrRepository repo = mock(EmrRepository.class);

    @Test
    @DisplayName("模板类型全部有策略：启动通过")
    void passesWhenAllTemplatesHavePolicy() {
        when(repo.listTemplateDocTypes()).thenReturn(List.of("ADMISSION_NOTE", "PROGRESS_NOTE"));
        when(repo.listPolicyDocTypes()).thenReturn(
                List.of("ADMISSION_NOTE", "PROGRESS_NOTE", "DISCHARGE_SUMMARY"));

        assertThatCode(() -> SignPolicyGuard.verify(repo)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("新增模板漏配策略：启动失败并列出缺失类型")
    void failsWhenTemplateHasNoPolicy() {
        when(repo.listTemplateDocTypes()).thenReturn(List.of("ADMISSION_NOTE", "NEW_DOC"));
        when(repo.listPolicyDocTypes()).thenReturn(List.of("ADMISSION_NOTE"));

        assertThatThrownBy(() -> SignPolicyGuard.verify(repo))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NEW_DOC")
                .hasMessageContaining("缺少归档签名策略");
    }

    @Test
    @DisplayName("策略多于模板（预留配置）：仍通过")
    void passesWhenPoliciesAreSuperset() {
        when(repo.listTemplateDocTypes()).thenReturn(List.of());
        when(repo.listPolicyDocTypes()).thenReturn(List.of("ADMISSION_NOTE"));

        assertThatCode(() -> SignPolicyGuard.verify(repo)).doesNotThrowAnyException();
    }
}
