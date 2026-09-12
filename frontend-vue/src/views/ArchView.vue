<!-- 架构·合规视图（轮 A 补齐，对照矩阵 F-8）：内容为静态展示，逐字迁移 legacy
     frontend/index.html #scr-arch 区块（六层架构流 / 医疗循证 RAG·反幻觉四闸 /
     人机协同·高危双人核对 / 合规红线 四卡）。无数据请求，纯内容视图。 -->
<template>
  <div>
    <div class="shead">
      <div>
        <h2>架构 · 合规</h2>
        <p>临床决策支持系统的设计蓝图与不可越界红线</p>
      </div>
    </div>
    <div class="page-body" style="max-width: 900px">
      <!-- 卡1：六层架构（数据向下，结果向上） -->
      <el-card shadow="never" data-testid="arch-layers">
        <template #header><b>六层架构（数据向下，结果向上）</b></template>
        <div class="flow">
          <span>API层·鉴权/校验/流式</span><i>→</i><span>编排层·意图路由/调度</span><i>→</i><span>Agent层·四大临床助手</span><i>→</i><span>公共层·LLM工厂/重试/记忆/日志</span><i>→</i><span>工具层·检索/ICD/MCP</span><i>→</i><span>数据层·Postgres+Milvus+本地模型</span>
        </div>
      </el-card>
      <!-- 卡2：医疗循证 RAG · 反幻觉四闸 -->
      <el-card shadow="never" data-testid="arch-rag">
        <template #header><b>医疗循证 RAG · 反幻觉四闸</b></template>
        <ol class="rules">
          <li><b>检索约束生成</b>：稠密+稀疏混合召回 + 交叉编码器精排；只用检索到的权威要点作答。</li>
          <li><b>逐条引用溯源</b>：每条结论必须能在证据集核验，无据引用自动剔除并降置信。</li>
          <li><b>保守置信阈值</b>：医学答错代价极高，低置信不强行作答。</li>
          <li><b>人机协同兜底</b>：高风险（禁忌/影像可疑/低置信）转人工，宁可不答不乱答。</li>
        </ol>
      </el-card>
      <!-- 卡3：人机协同 · 高危双人核对 -->
      <el-card shadow="never" data-testid="arch-dual">
        <template #header><b>人机协同 · 高危双人核对</b></template>
        <ol class="rules">
          <li>医生在线采纳/改写常规答案；仅高风险入「双人核对」队列。</li>
          <li>签发人≠提交人（双控）；<b>三列留痕</b>：AI建议 · 人工裁定 · 最终结果，全程可审计。</li>
          <li>确定性问题用硬规则（如药物禁忌），安全关键不依赖模型。</li>
        </ol>
      </el-card>
      <!-- 卡4：合规红线 -->
      <el-card shadow="never" data-testid="arch-compliance">
        <template #header><b>合规红线</b></template>
        <ol class="rules">
          <li>仅辅助决策，<b>不构成诊断、不开处方</b>；签字权在执业医师/药师。</li>
          <li><b>数据不出院</b>：本地权重 + 自有基础设施，知识库离线摄取（data/kb_docs）。</li>
          <li>PHI 入站脱敏（身份证/手机/邮箱/病案号）+ 访问鉴权 + 全链路可追溯审计。</li>
        </ol>
      </el-card>
    </div>
  </div>
</template>

<script setup>
// 静态内容视图：legacy scr-arch 无任何数据加载/事件绑定，此处等价（无脚本逻辑）
defineOptions({ name: 'ArchView' })
</script>

<style scoped>
/* 六层架构流（legacy .flow 同布局：节点 span + 箭头，窄屏换行） */
.flow {
  display: flex; align-items: center; flex-wrap: wrap; gap: 8px;
  font-size: 12.5px;
}
.flow span {
  background: var(--rail, #f5f7fa); border: 1px solid var(--line);
  border-radius: 6px; padding: 4px 8px; white-space: nowrap;
}
.flow i { color: var(--ink-3, #64748b); font-style: normal }
.rules { margin: 0; padding-left: 20px; line-height: 1.9; font-size: 13px }
</style>
