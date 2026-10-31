package com.medassist.emr.config;

import com.medassist.emr.repo.EmrRepository;
import com.medassist.emr.service.SignPolicyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动守卫：模板文书类型必须都有归档签名策略（第四轮 SF-17）。
 *
 * <p>为什么必须 fail-fast：归档逻辑在"查不到策略"时是 fail-closed（拒绝归档），
 * 一旦新增模板却漏配策略，该类文书会**完全无法归档**，而且要到使用时才暴露。
 * 启动时一次性比对，把配置错误挡在发布阶段。
 *
 * <p>反向不校验（策略多于模板）：策略可能提前配置给尚未启用的文书类型，属合法配置。
 */
@Component
public class SignPolicyGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SignPolicyGuard.class);

    private final EmrRepository repo;

    public SignPolicyGuard(EmrRepository repo) {
        this.repo = repo;
    }

    @Override
    public void run(ApplicationArguments args) {
        verify(repo);
    }

    /** 供单测直接调用（不启动 Spring 上下文）。 */
    public static void verify(EmrRepository repo) {
        List<String> templateTypes = repo.listTemplateDocTypes();
        List<String> policyTypes = repo.listPolicyDocTypes();
        List<String> missing = templateTypes.stream()
                .filter(type -> !policyTypes.contains(type))
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("以下文书类型已有模板但缺少归档签名策略，"
                    + "归档将被拒绝（请在 emr_svc.sign_policy 补配或通过 PUT /emr/sign-policies 配置）："
                    + String.join("、", missing)
                    + "；当前已配置：" + String.join("、", policyTypes)
                    + "；常量表：" + String.join("、", SignPolicyService.docTypes()));
        }
        log.info("sign.policy.check.ok templateTypes={} policyTypes={}",
                templateTypes.size(), policyTypes.size());
    }
}
