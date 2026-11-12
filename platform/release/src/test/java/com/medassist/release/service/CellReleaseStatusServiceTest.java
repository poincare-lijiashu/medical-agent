package com.medassist.release.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.release.domain.ReleaseModel.Train;
import com.medassist.release.domain.ReleaseModel.TrainItem;
import com.medassist.release.repo.ReleaseRepository;
import com.medassist.release.repo.ReleaseRepository.CellDeployment;
import com.medassist.release.web.ReleaseDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Cell 发布对齐：判定口径必须可解释，且不能把"没发过车"渲染成落后。
 */
class CellReleaseStatusServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-12T06:00:00Z");
    private static final UUID AUTH_VERSION = UUID.randomUUID();
    private static final UUID CLINICAL_VERSION = UUID.randomUUID();
    private static final UUID TRAIN_ID = UUID.randomUUID();

    private ReleaseRepository repo;
    private CellReleaseStatusService service;

    @BeforeEach
    void setUp() {
        repo = mock(ReleaseRepository.class);
        service = new CellReleaseStatusService(repo, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("没有已发布列车：返回 UNKNOWN，而不是把全部组件标成落后")
    void unknownWithoutTrain() {
        when(repo.latestReleasedTrain()).thenReturn(Optional.empty());

        ReleaseDtos.CellReleaseStatus status = service.status("tenant-001");

        assertThat(status.state()).isEqualTo("UNKNOWN");
        assertThat(status.totalComponents()).isZero();
        assertThat(status.components()).isEmpty();
    }

    @Test
    @DisplayName("全部与列车一致：ALIGNED")
    void aligned() {
        when(repo.latestReleasedTrain()).thenReturn(Optional.of(train()));
        when(repo.latestCellDeployments("tenant-001")).thenReturn(List.of(
                new CellDeployment("platform-auth", AUTH_VERSION, "2.1.0", NOW.minusSeconds(600)),
                new CellDeployment("platform-clinical", CLINICAL_VERSION, "1.1.0",
                        NOW.minusSeconds(300))));
        when(repo.latestCellAttempts("tenant-001")).thenReturn(List.of(
                new CellDeployment("platform-auth", AUTH_VERSION, "2.1.0", NOW.minusSeconds(600)),
                new CellDeployment("platform-clinical", CLINICAL_VERSION, "1.1.0",
                        NOW.minusSeconds(300))));

        ReleaseDtos.CellReleaseStatus status = service.status("tenant-001");

        assertThat(status.state()).isEqualTo("ALIGNED");
        assertThat(status.behindComponents()).isZero();
        assertThat(status.failedComponents()).isZero();
        assertThat(status.trainCode()).isEqualTo("2026.11");
    }

    @Test
    @DisplayName("版本不同=落后，无记录=未部署，分别计数")
    void behindAndNeverDeployed() {
        when(repo.latestReleasedTrain()).thenReturn(Optional.of(train()));
        when(repo.latestCellDeployments("tenant-002")).thenReturn(List.of(
                new CellDeployment("platform-auth", UUID.randomUUID(), "2.0.0",
                        NOW.minusSeconds(3600))));
        when(repo.latestCellAttempts("tenant-002")).thenReturn(List.of(
                new CellDeployment("platform-auth", UUID.randomUUID(), "2.0.0",
                        NOW.minusSeconds(3600))));

        ReleaseDtos.CellReleaseStatus status = service.status("tenant-002");

        assertThat(status.state()).isEqualTo("BEHIND");
        assertThat(status.behindComponents()).isEqualTo(2);
        assertThat(status.components()).extracting(ReleaseDtos.CellComponentStatus::status)
                .containsExactlyInAnyOrder("BEHIND", "NEVER_DEPLOYED");
    }

    @Test
    @DisplayName("最近一次部署失败：FAILED 优先于落后（红标）")
    void failedWinsOverBehind() {
        when(repo.latestReleasedTrain()).thenReturn(Optional.of(train()));
        when(repo.latestCellDeployments("tenant-001")).thenReturn(List.of(
                new CellDeployment("platform-auth", AUTH_VERSION, "2.1.0", NOW.minusSeconds(600)),
                new CellDeployment("platform-clinical", CLINICAL_VERSION, "1.1.0",
                        NOW.minusSeconds(300))));
        when(repo.latestCellAttempts("tenant-001")).thenReturn(List.of(
                new CellDeployment("platform-auth", AUTH_VERSION, "2.1.0", NOW.minusSeconds(600)),
                new CellDeployment("platform-clinical", UUID.randomUUID(), "1.2.0",
                        NOW.minusSeconds(60), "FAILED")));

        ReleaseDtos.CellReleaseStatus status = service.status("tenant-001");

        assertThat(status.state()).isEqualTo("FAILED");
        assertThat(status.failedComponents()).isEqualTo(1);
    }

    @Test
    @DisplayName("租户编码不合法：拒绝而不是静默查出空结果")
    void invalidTenantRejected() {
        assertThatThrownBy(() -> service.status("../etc"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.REL_RELEASE_INVALID);
    }

    private static Train train() {
        List<TrainItem> items = List.of(
                new TrainItem(UUID.randomUUID(), TRAIN_ID, "platform-auth", AUTH_VERSION,
                        "release01", "2.1.0"),
                new TrainItem(UUID.randomUUID(), TRAIN_ID, "platform-clinical", CLINICAL_VERSION,
                        "release01", "1.1.0"));
        return new Train(TRAIN_ID, "2026.11", "十一月列车", LocalDate.parse("2026-11-10"),
                NOW.minusSeconds(86400), "RELEASED", null, NOW.minusSeconds(3600),
                "release01", 3, NOW.minusSeconds(7200), NOW.minusSeconds(3600), items);
    }
}
