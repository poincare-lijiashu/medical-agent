package com.medassist.insurance.grouper;

import com.medassist.insurance.domain.InsuranceModel.GrouperAdrgRule;
import com.medassist.insurance.domain.InsuranceModel.GrouperCcExclusion;
import com.medassist.insurance.domain.InsuranceModel.GrouperCcRule;
import com.medassist.insurance.domain.InsuranceModel.GrouperDipCatalog;
import com.medassist.insurance.domain.InsuranceModel.GrouperDrgDef;
import com.medassist.insurance.domain.InsuranceModel.GrouperMdcRule;

import java.util.List;

/**
 * 一个规则包的完整只读视图：一次加载、一次分组，避免引擎里发 N 次查询。
 *
 * <p>列表已按 priority 升序（仓储保证），引擎按顺序取首个命中。
 */
public record GrouperRuleSetBundle(List<GrouperMdcRule> mdcRules,
                                   List<GrouperAdrgRule> adrgRules,
                                   List<GrouperDrgDef> drgDefs,
                                   List<GrouperCcRule> ccRules,
                                   List<GrouperCcExclusion> ccExclusions,
                                   List<GrouperDipCatalog> dipCatalog) {
}
