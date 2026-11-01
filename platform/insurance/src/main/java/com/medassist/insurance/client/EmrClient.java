package com.medassist.insurance.client;

import java.util.List;
import java.util.UUID;

/**
 * 病案首页查询端口：DRG/DIP 模拟分组的**手术操作**来自病案首页。
 *
 * <p>为什么是病案首页而不是临床域：临床目前没有手术麻醉域，手术操作在病案首页录入；
 * 医保分组必须与上报病案首页一致，否则会出现"分组用了 A 编码、上报用了 B 编码"。
 */
public interface EmrClient {

    /**
     * 就诊对应的首页手术操作编码。
     *
     * @param found 是否已生成病案首页（未出院/未生成时 false，手术编码为空属正常）
     */
    record HomepageProcedures(UUID visitId, boolean found, String status, List<String> codes) {
    }

    /**
     * 拉取首页手术编码。
     *
     * <p>病历域不可达时抛 503（fail-closed）：不能把"取不到"当成"没有手术"，
     * 否则会静默分到低权重组，把超支风险掩盖掉。
     */
    HomepageProcedures procedures(UUID visitId);
}
