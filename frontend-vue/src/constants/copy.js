// 免责声明常量：与 legacy frontend/js/state.js 的 DRUG_DISCLAIMER 逐字同源
// （后端 backend/core/drug_dict.py 的 DISCLAIMER 亦逐字一致，tests/test_drug_dict_admin.py 锁定）。
export const DRUG_DISCLAIMER =
  '药品数据由 AI 辅助生成，临床使用前必须经执业药师核对；本系统仅供辅助决策，不构成医疗建议。'

// 影像/会诊等 AI 视图通用边界提示：复用 legacy 登录页既有文案（不新造）。
export const AI_DISCLAIMER = '本工具为辅助决策，非医疗器械；结论须由执业医师复核。'
