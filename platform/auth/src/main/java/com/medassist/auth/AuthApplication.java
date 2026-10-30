package com.medassist.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 用户与权限中心。
 *
 * <p>P0 阶段交付骨架；后续按计划书 D3 落地账号、角色、RBAC 权限矩阵与组织架构。
 */
@SpringBootApplication
public class AuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }
}
