package com.medassist.license.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 交付形态校验（§289）：院方构建必须不能签发，误配必须拒绝启动。
 *
 * <p>这些用例锁的是"商业承诺不能被配置漂移悄悄破坏"：
 * 院方部署哪怕只多写了一个 {@code LICENSE_ISSUANCE_ENABLED=true}，也不能起来。
 */
class LicenseDeploymentGuardTest {

    @Test
    @DisplayName("院方构建开启签发 → 拒绝启动")
    void hospitalModeRejectsIssuance() {
        LicenseProperties props = props("hospital", true);
        assertThatThrownBy(() -> new LicenseDeploymentGuard(props).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("禁止开启签发能力");
    }

    @Test
    @DisplayName("院方构建关闭签发 → 正常启动")
    void hospitalModeWithoutIssuanceStarts() {
        LicenseProperties props = props(null, false);
        assertThat(props.deploymentMode()).isEqualTo(LicenseProperties.HOSPITAL);
        assertThatCode(() -> new LicenseDeploymentGuard(props).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("厂商构建开启签发 → 正常启动")
    void vendorModeWithIssuanceStarts() {
        assertThatCode(() -> new LicenseDeploymentGuard(props("vendor", true)).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("未知交付形态 → 拒绝启动（不静默猜）")
    void unknownModeIsRejected() {
        assertThatThrownBy(() -> new LicenseDeploymentGuard(props("vendro", false)).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("只能是 vendor 或 hospital");
    }

    private LicenseProperties props(String mode, boolean issuance) {
        return new LicenseProperties("MEDASSIST-TEST-0001", null, null, "data/license",
                mode, issuance, 30, false);
    }
}
