package com.medassist.license.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.license.config.LicenseProperties;
import com.medassist.license.domain.LicenseModel.Edition;
import com.medassist.license.domain.LicenseModel.License;
import com.medassist.license.domain.LicenseModel.ModuleEntitlement;
import com.medassist.license.domain.LicenseModel.Phase;
import com.medassist.license.domain.LicenseModel.Quota;
import com.medassist.license.repo.LicenseRepository;
import com.medassist.license.web.LicenseDtos.IssueReq;
import com.medassist.license.web.LicenseDtos.Issued;
import com.medassist.license.web.LicenseDtos.StatusView;
import com.medassist.license.web.LicenseDtos.UsageReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 授权核心语义：签发→导入→判定→配额→吊销。
 *
 * <p>这些用例锁的是"商业承诺不能自相矛盾"：
 * 签名必须逐字节可验、实例必须绑定、过期与宽限必须可区分、超配额只告警不炸临床。
 */
class LicenseServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-12T09:00:00Z");

    @TempDir
    Path keyDir;

    private LicenseRepository repo;
    private LicenseService service;
    private final AtomicReference<License> stored = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(LicenseRepository.class);
        when(repo.listEditions()).thenReturn(editions());
        when(repo.insert(any())).thenAnswer(inv -> {
            stored.set(inv.getArgument(0));
            return true;
        });
        when(repo.findById(any())).thenAnswer(inv -> Optional.ofNullable(stored.get()));
        when(repo.findByNo(anyString())).thenReturn(Optional.empty());
        when(repo.findActive(anyString())).thenReturn(Optional.empty());
        when(repo.supersedeActive(anyString(), any())).thenReturn(0);
        when(repo.listAudit(anyInt())).thenReturn(List.of());

        LicenseProperties props = new LicenseProperties(
                "MEDASSIST-TEST-0001", null, null, keyDir.toString(), true, 30, false);
        service = new LicenseService(repo, props, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void issued_document_round_trips_through_import() {
        Issued issued = service.issue(req("LIC-2026-0001", "2027-11-11T00:00:00Z"), "vendor", "t-1");

        assertThat(issued.document()).contains("\"schema\":\"medassist-license/v1\"");
        assertThat(issued.payloadHash()).hasSize(64);
        assertThat(issued.keyId()).hasSize(16);

        License imported = service.importLicense(issued.document(), "admin01", "t-2");

        assertThat(imported.licenseNo()).isEqualTo("LIC-2026-0001");
        assertThat(imported.instanceCode()).isEqualTo("MEDASSIST-TEST-0001");
        assertThat(imported.status()).isEqualTo("ACTIVE");
        assertThat(imported.modules()).containsExactly("ai", "clinical", "emr", "pharmacy");
        assertThat(imported.payloadHash()).isEqualTo(issued.payloadHash());
        assertThat(imported.signature()).isNotBlank();
        assertThat(imported.issuedAt()).isEqualTo(NOW);
    }

    @Test
    void tampered_payload_is_rejected() {
        String document = service.issue(req("LIC-2026-0002", "2027-11-11T00:00:00Z"), "vendor", null).document();
        String tampered = document.replace("\"maxBeds\":2000", "\"maxBeds\":99999")
                .replace("\"maxBeds\": 2000", "\"maxBeds\": 99999");

        assertThatThrownBy(() -> service.importLicense(tampered, "admin01", null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.LICENSE_SIGNATURE_INVALID);
    }

    @Test
    void license_is_bound_to_instance() {
        Issued issued = service.issue(
                new IssueReq("LIC-2026-0003", "OTHER-HOSPITAL-01", "外院", "ENTERPRISE",
                        null, null, null, null, null, "2027-11-11T00:00:00Z", null),
                "vendor", null);

        assertThatThrownBy(() -> service.importLicense(issued.document(), "admin01", null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.LICENSE_INSTANCE_MISMATCH);
    }

    @Test
    void duplicate_license_number_is_rejected() {
        String document = service.issue(req("LIC-2026-0004", "2027-11-11T00:00:00Z"), "vendor", null).document();
        when(repo.findByNo("LIC-2026-0004")).thenReturn(Optional.of(sample()));

        assertThatThrownBy(() -> service.importLicense(document, "admin01", null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.LICENSE_DUPLICATE);
    }

    @Test
    void phase_moves_from_active_to_grace_to_expired() {
        License license = sample();

        assertThat(service.stateOf(license, NOW).phase()).isEqualTo(Phase.ACTIVE);

        Instant afterExpiry = Instant.parse("2027-11-20T00:00:00Z");
        assertThat(service.stateOf(license, afterExpiry).phase()).isEqualTo(Phase.GRACE);
        assertThat(service.stateOf(license, afterExpiry).daysLeft()).isEqualTo(21);

        Instant afterGrace = Instant.parse("2027-12-20T00:00:00Z");
        assertThat(service.stateOf(license, afterGrace).phase()).isEqualTo(Phase.EXPIRED);
        assertThat(service.stateOf(license, afterGrace).usable()).isFalse();
    }

    @Test
    void revoked_license_is_not_usable_even_if_time_is_valid() {
        License revoked = new License(sample().id(), sample().licenseNo(), sample().instanceCode(),
                sample().hospitalName(), sample().editionCode(), sample().modules(), sample().maxBeds(),
                sample().maxUsers(), sample().maxAiCallsPerDay(), sample().notBefore(), sample().expiresAt(),
                sample().graceDays(), "REVOKED", sample().payload(), sample().payloadHash(),
                sample().signature(), sample().keyId(), "admin01", NOW, NOW, "admin01", NOW, "合同终止");

        assertThat(service.stateOf(revoked, NOW).phase()).isEqualTo(Phase.REVOKED);
        assertThat(service.stateOf(revoked, NOW).usable()).isFalse();
    }

    @Test
    void status_reports_entitlements_and_quota_overflow() {
        when(repo.findActive("MEDASSIST-TEST-0001")).thenReturn(Optional.of(sample()));
        when(repo.latestUsage("MEDASSIST-TEST-0001"))
                .thenReturn(Optional.of(new com.medassist.license.domain.LicenseModel.Usage(
                        NOW.atZone(ZoneOffset.UTC).toLocalDate(), 2100, 120, 100, "GATEWAY", NOW)));

        StatusView view = service.status();

        assertThat(view.phase()).isEqualTo("ACTIVE");
        List<ModuleEntitlement> entitled = view.entitlements().stream().filter(ModuleEntitlement::entitled).toList();
        assertThat(entitled).extracting(ModuleEntitlement::code)
                .containsExactly("clinical", "pharmacy", "emr", "ai");
        assertThat(view.entitlements()).filteredOn(e -> e.code().equals("mdm"))
                .singleElement().satisfies(e -> assertThat(e.entitled()).isFalse());

        Quota beds = view.quotas().stream().filter(q -> q.code().equals("beds")).findFirst().orElseThrow();
        assertThat(beds.exceeded()).isTrue();
        assertThat(beds.utilization()).isGreaterThan(1.0);
        assertThat(view.warnings()).anyMatch(w -> w.contains("配额超限"));
        assertThat(view.warnings()).anyMatch(w -> w.contains("观察模式"));
    }

    @Test
    void status_without_license_keeps_modules_unentitled() {
        StatusView view = service.status();

        assertThat(view.phase()).isEqualTo("NONE");
        assertThat(view.entitlements()).allSatisfy(e -> assertThat(e.entitled()).isFalse());
        assertThat(view.warnings()).anyMatch(w -> w.contains("尚未导入授权"));
        assertThat(view.verifyKeyId()).hasSize(16);
    }

    @Test
    void revoked_only_instance_reports_revoked_instead_of_none() {
        License revoked = new License(UUID.randomUUID(), "LIC-2026-0001", "MEDASSIST-TEST-0001",
                "铭医示范医院", "ENTERPRISE", List.of("clinical"), 200, 100, 0,
                Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2027-11-11T00:00:00Z"),
                30, "REVOKED", "{}", "hash", "sig", "key", "admin01", NOW, NOW, "admin01", NOW, "合同终止");
        when(repo.findActive("MEDASSIST-TEST-0001")).thenReturn(Optional.empty());
        when(repo.findLatest("MEDASSIST-TEST-0001")).thenReturn(Optional.of(revoked));

        StatusView view = service.status();

        assertThat(view.phase()).isEqualTo("REVOKED");
        assertThat(view.license()).isNotNull();
        assertThat(view.warnings()).anyMatch(w -> w.contains("吊销"));
        assertThat(view.entitlements()).allSatisfy(e -> assertThat(e.entitled()).isFalse());
    }

    @Test
    void negative_usage_is_rejected() {
        assertThatThrownBy(() -> service.captureUsage(
                new UsageReq(null, -1, 0, 0, "MANUAL"), "admin01", null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.LICENSE_USAGE_INVALID);
    }

    @Test
    void issuance_can_be_disabled_for_hospital_builds() {
        LicenseProperties props = new LicenseProperties(
                "MEDASSIST-TEST-0001", null, null, keyDir.toString(), false, 30, false);
        LicenseService hospitalSide = new LicenseService(repo, props, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> hospitalSide.issue(req("LIC-2026-0009", "2027-11-11T00:00:00Z"), "admin01", null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.LICENSE_ISSUANCE_DISABLED);
    }

    /* ------------------------------------------------------------------ fixtures */

    private IssueReq req(String licenseNo, String expiresAt) {
        return new IssueReq(licenseNo, "MEDASSIST-TEST-0001", "铭医示范医院", "ENTERPRISE",
                List.of("clinical", "pharmacy", "emr", "ai", "PHARMACY"), null, null, null,
                "2026-11-12T00:00:00Z", expiresAt, 30);
    }

    private License sample() {
        return new License(UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "LIC-2026-0001", "MEDASSIST-TEST-0001", "铭医示范医院", "ENTERPRISE",
                List.of("ai", "clinical", "emr", "pharmacy"), 2000, 5000, 20000,
                Instant.parse("2026-11-12T00:00:00Z"), Instant.parse("2027-11-11T00:00:00Z"),
                30, "ACTIVE", "{}", "hash", "sig", "key", "admin01", NOW, NOW, null, null, null);
    }

    private List<Edition> editions() {
        return List.of(
                new Edition("BASIC", "基础版", null,
                        List.of("clinical", "pharmacy", "emr", "mdm"), 200, 150, 0, 10, "ACTIVE"),
                new Edition("ENTERPRISE", "全院版", null,
                        List.of("clinical", "pharmacy", "emr", "mdm", "tech", "insurance", "material",
                                "kb", "ai", "fhir", "ops", "release", "integration", "migration"),
                        2000, 5000, 20000, 30, "ACTIVE"));
    }
}
