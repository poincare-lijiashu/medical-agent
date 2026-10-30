package com.medassist.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 用户与权限中心。
 *
 * <p>启用定时任务：清理过期刷新令牌 / 过期撤销记录 / 历史登录尝试（P1）。
 */
@SpringBootApplication
@EnableScheduling
public class AuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }
}
