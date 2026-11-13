package com.medassist.license.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 交付形态校验（§289）：院方构建必须不能签发，误配必须拒绝启动。
 *
 * <p>这些用例锁的是"商业承诺不能被配置漂移悄悄破坏"：
 * 院方部署哪怕只多写了一个 {@code LICENSE_ISSUANCE_ENABLED=true}，也不能起来。
 *
 * <p>评测 D-22 后新增生产形态约束：实例码不得回落到演示值、模块门禁必须开启。
 */
class LicenseDeploymentGuardTest {

    @Test
    @DisplayName("院方构建开启签发 → 拒绝启动")
    void hospitalModeRejectsIssuance() {
        LicenseProperties props = props("hospital", true);
        assertThatThrownBy(() -> guard(props).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("禁止开启签发能力");
    }

    @Test
    @DisplayName("院方构建关闭签发 → 正常启动")
    void hospitalModeWithoutIssuanceStarts() {
        LicenseProperties props = props(null, false);
        assertThat(props.deploymentMode()).isEqualTo(LicenseProperties.HOSPITAL);
        assertThatCode(() -> guard(props).afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("厂商构建开启签发 → 正常启动")
    void vendorModeWithIssuanceStarts() {
        assertThatCode(() -> guard(props("vendor", true)).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("未知交付形态 → 拒绝启动（不静默猜）")
    void unknownModeIsRejected() {
        assertThatThrownBy(() -> guard(props("vendro", false)).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("只能是 vendor 或 hospital");
    }

    @Test
    @DisplayName("生产沿用演示实例码 → 拒绝启动（防止多院共用授权书）")
    void productionRejectsDevInstanceCode() {
        LicenseProperties props = new LicenseProperties("MEDASSIST-DEV-0001", null, null,
                "data/license", "hospital", false, 30, true);
        assertThatThrownBy(() -> guard(props, "production").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LICENSE_INSTANCE_CODE");
    }

    @Test
    @DisplayName("生产未开启模块门禁 → 拒绝启动（授权不能只是元数据）")
    void productionRequiresEnforce() {
        LicenseProperties props = new LicenseProperties("MEDASSIST-HOSP-0001", null, null,
                "data/license", "hospital", false, 30, false);
        assertThatThrownBy(() -> guard(props, "production").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LICENSE_ENFORCE");
    }

    @Test
    @DisplayName("生产实例码绑定且开启门禁 → 正常启动")
    void productionWithBoundInstanceStarts() {
        LicenseProperties props = new LicenseProperties("MEDASSIST-HOSP-0001", null, null,
                "data/license", "hospital", false, 30, true);
        assertThatCode(() -> guard(props, "production").afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    private LicenseDeploymentGuard guard(LicenseProperties props) {
        return guard(props, "dev");
    }

    private LicenseDeploymentGuard guard(LicenseProperties props, String env) {
        MockEnvironment environment = new MockEnvironment();
        // SecurityDefaults 按 active profile 判定生产（prod/production/staging/k8s）
        environment.setActiveProfiles("production".equals(env) ? "prod" : "dev");
        return new LicenseDeploymentGuard(props, environment);
    }

    private LicenseProperties props(String mode, boolean issuance) {
        return new LicenseProperties("MEDASSIST-TEST-0001", null, null, "data/license",
                mode, issuance, 30, false);
    }
}
