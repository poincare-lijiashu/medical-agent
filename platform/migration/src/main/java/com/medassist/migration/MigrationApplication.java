package com.medassist.migration;

import com.medassist.common.web.TraceMdcFilter;
import com.medassist.migration.config.MigrationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * 历史数据迁移工具（§270 / 计划书 W4.3）。
 *
 * <p>定位：把旧 HIS/EMR/纸质台账里的数据<b>可控地</b>搬进新系统，而不是写几条一次性脚本：
 * <ul>
 *   <li>每个目标都有字段字典与可下载模板，源系统列名差异用可复用映射档案消化；</li>
 *   <li>上传后先预检（逐行归一化、类型/枚举/引用/业务冲突），预检结果与文件校验和绑定；</li>
 *   <li>应用前逐行复核目标现状，任何一行冲突则整批不落库（全有或全无）；</li>
 *   <li>每行保存前镜像/后镜像，回滚按版本链反向恢复；目标行在应用后被人工改过则拒绝回滚；</li>
 *   <li>迁移前后做总量/金额/关键字段对账，报告留档。</li>
 * </ul>
 *
 * <p>为什么独立服务：迁移要跨 EMPI、临床、主数据多个 schema 读写，放进任一业务服务都会
 * 让该服务获得"改别的域历史数据"的能力；独立服务可以单独授权、单独审计、迁移完成后下线。
 */
@SpringBootApplication
@EnableConfigurationProperties(MigrationProperties.class)
public class MigrationApplication {

    public static void main(String[] args) {
        SpringApplication.run(MigrationApplication.class, args);
    }

    /** 把网关注入的 X-Trace-Id 写入日志 MDC，实现 trace ↔ log 关联（M12）。 */
    @Bean
    public FilterRegistrationBean<TraceMdcFilter> traceMdcFilter() {
        FilterRegistrationBean<TraceMdcFilter> registration = new FilterRegistrationBean<>(new TraceMdcFilter());
        registration.setOrder(Integer.MIN_VALUE + 10);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
