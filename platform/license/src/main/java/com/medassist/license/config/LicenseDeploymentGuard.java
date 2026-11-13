package com.medassist.license.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * 交付形态的启动校验（§289）：把"院方不能给自己发证"从文档约定变成构建期事实。
 *
 * <p>为什么必须在启动期而不是在接口里判断：
 * <ul>
 *   <li>接口里判断只是"运行时不可用"，运维仍可能误以为已开启而反复排障；</li>
 *   <li>一旦有人把院方部署的 {@code LICENSE_ISSUANCE_ENABLED} 打开，<b>静默降级</b>
 *       会掩盖误配；直接拒绝启动能让问题在发布窗口暴露，而不是在审计时暴露；</li>
 *   <li>签名私钥只应存在于厂商侧；院方构建带着可签发的配置运行本身就是风险信号。</li>
 * </ul>
 *
 * <p>校验规则（fail-closed）：
 * <ol>
 *   <li>{@code deployment-mode} 只能是 {@code vendor} / {@code hospital}，其它值拒绝启动；</li>
 *   <li>{@code hospital} 且 {@code issuance-enabled=true} → 拒绝启动，并给出修复指引；</li>
 *   <li>{@code vendor} 但关闭签发 → 允许（只读厂商构建，常见于签发与交付分离的流水线），仅告警。</li>
 * </ol>
 */
@Component
public class LicenseDeploymentGuard implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(LicenseDeploymentGuard.class);

    /** 本地演示实例码：生产沿用等于"多院共用同一授权书"，必须在启动期拦下。 */
    private static final String DEV_INSTANCE_CODE = "MEDASSIST-DEV-0001";

    private final LicenseProperties props;
    private final org.springframework.core.env.Environment environment;

    public LicenseDeploymentGuard(LicenseProperties props,
                                  org.springframework.core.env.Environment environment) {
        this.props = props;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        String mode = props.deploymentMode();
        if (!props.vendorMode() && !props.hospitalMode()) {
            throw new IllegalStateException(
                    "LICENSE_DEPLOYMENT_MODE 只能是 vendor 或 hospital，当前为："
                            + mode + "（未知形态不允许启动，避免误判签发能力）");
        }
        if (props.hospitalMode() && props.issuanceEnabled()) {
            throw new IllegalStateException(
                    "院方构建（LICENSE_DEPLOYMENT_MODE=hospital）禁止开启签发能力："
                            + "请设置 LICENSE_ISSUANCE_ENABLED=false。"
                            + "授权书必须由厂商侧签发后在院方导入，禁止院方自签。");
        }
        if (props.vendorMode() && !props.issuanceEnabled()) {
            log.warn("license.deployment.vendor_issuance_off "
                    + "mode=vendor issuance=off detail=只读厂商构建（签发与交付分离）");
        }
        // 评测 D-22：许可配置此前默认 fail-open（实例码回落到演示值、enforce=false）。
        // 生产必须显式绑定实例码并开启模块门禁，否则"授权"只是一行元数据。
        if (com.medassist.common.security.SecurityDefaults.isProduction(environment)) {
            if (DEV_INSTANCE_CODE.equals(props.instanceCode())) {
                throw new IllegalStateException(
                        "生产/预发布环境不得使用演示实例码 " + DEV_INSTANCE_CODE
                                + "：请通过 LICENSE_INSTANCE_CODE 绑定本院授权书实例码，服务拒绝启动");
            }
            if (!props.enforce()) {
                throw new IllegalStateException(
                        "生产/预发布环境必须开启授权模块门禁（LICENSE_ENFORCE=true），"
                                + "否则未授权模块不会被拦截，服务拒绝启动");
            }
        }
        log.info("license.deployment.resolved mode={} issuance={} enforce={} instance={}",
                mode, props.issuanceEnabled(), props.enforce(), props.instanceCode());
    }
}
