package com.medassist.empi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 患者主索引（EMPI）服务。
 *
 * <p>P0 阶段交付骨架；后续按计划书 D3/D4 落地建档、合并管理与跨系统标识映射
 * （{@code patient_ids_crosswalk}）。
 */
@SpringBootApplication
public class EmpiApplication {

    public static void main(String[] args) {
        SpringApplication.run(EmpiApplication.class, args);
    }
}
