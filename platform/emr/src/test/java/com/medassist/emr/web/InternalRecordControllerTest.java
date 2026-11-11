package com.medassist.emr.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.emr.config.EmrProperties;
import com.medassist.emr.domain.EmrModel.Homepage;
import com.medassist.emr.repo.EmrRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 内网病历归档接口：令牌 fail-closed、只回计数、首页状态如实透出。
 */
class InternalRecordControllerTest {

    private static final String TOKEN = "internal-token";

    private EmrRepository repo;
    private InternalRecordController controller;
    private final UUID visitId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(EmrRepository.class);
        controller = new InternalRecordController(repo,
                new EmrProperties(null, null, new EmrProperties.Internal(TOKEN), null));
        when(repo.archiveCounts(visitId)).thenReturn(new EmrRepository.RecordArchiveCounts(5, 3, 2));
        when(repo.findHomepageByVisit(visitId)).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("未带令牌：403，且不触达数据层（不能靠缺头绕过）")
    void missingTokenRejected() {
        assertThatThrownBy(() -> controller.archiveSummary(visitId, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(repo, never()).archiveCounts(any());
    }

    @Test
    @DisplayName("令牌错误：403（统一泛化文案，不透露接口是否启用）")
    void wrongTokenRejected() {
        assertThatThrownBy(() -> controller.archiveSummary(visitId, "wrong"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("令牌正确：返回计数；首页未生成时如实为 false/null")
    void returnsCountsWithoutHomepage() {
        Map<String, Object> body = controller.archiveSummary(visitId, TOKEN);

        assertThat(body).containsEntry("total", 5)
                .containsEntry("archived", 3)
                .containsEntry("draft", 2)
                .containsEntry("homepageFound", false)
                .containsEntry("homepageArchived", false);
        assertThat(body.get("homepageStatus")).isNull();
    }

    @Test
    @DisplayName("首页已归档：状态与布尔值一起返回，供调用方核对")
    void returnsArchivedHomepage() {
        Homepage homepage = mock(Homepage.class);
        when(homepage.status()).thenReturn(Homepage.ARCHIVED);
        when(repo.findHomepageByVisit(visitId)).thenReturn(Optional.of(homepage));

        Map<String, Object> body = controller.archiveSummary(visitId, TOKEN);

        assertThat(body).containsEntry("homepageFound", true)
                .containsEntry("homepageStatus", Homepage.ARCHIVED)
                .containsEntry("homepageArchived", true);
    }
}
