// 前端纯函数单测（评测 P0-5 / P1-4）：此前 frontend-vue 完全没有行为测试，
// 只有 Python 侧的"关键字符串必须存在"标记锁——把逻辑改坏而不改字符串时 CI 全绿。
// 这里只测零依赖/低依赖的纯函数（转义、Markdown、置信度、来源、会诊拆分、审计人话、滚动目标）。
import { describe, it, expect, vi } from 'vitest'

// assist.js 顶部 import { errText } from '../api'（会牵出 axios/element-plus/router）；
// 单测只关心纯函数，mock 掉该模块避免加载整条前端依赖链。
vi.mock('../../src/api', () => ({ errText: () => '网络异常' }))

import {
  esc, md, confMeta, srcItems, splitConsultQuestion, splitAiByDept, fmtTs, detailText,
} from '../../src/utils/assist'
import { humanizeAudit, fmtLogSize, fmtUp } from '../../src/utils/audit'
import { scrollContentTop } from '../../src/utils/scroll'

describe('esc / md：先转义再渲染（XSS 面）', () => {
  it('esc 转义五个 HTML 元字符', () => {
    expect(esc(`<a href="x">&'`)).toBe('&lt;a href=&quot;x&quot;&gt;&amp;&#39;')
  })

  it('md 对脚本标签转义，不产生可执行标签', () => {
    const out = md('<script>alert(1)</script>')
    expect(out).not.toContain('<script>')
    expect(out).toContain('&lt;script&gt;')
  })

  it('md 渲染粗体/标题/列表/换行与 LaTeX 清理', () => {
    expect(md('**重点**')).toBe('<b>重点</b>')
    expect(md('# 标题')).toBe('<b>标题</b>')
    expect(md('- 一条')).toBe('• 一条')
    expect(md('甲\n乙')).toBe('甲<br>乙')
    expect(md('3 \\times 4 $x$')).toBe('3 × 4 x')
  })
})

describe('confMeta：置信度阈值（高 0.75 / 中 0.6）', () => {
  it('按阈值给出类型与文案', () => {
    expect(confMeta(0.9)[0]).toEqual({ type: 'success', label: '高置信 0.90' })
    expect(confMeta(0.7)[0]).toEqual({ type: 'primary', label: '中等 0.70' })
    expect(confMeta(0.5)[0]).toEqual({ type: 'warning', label: '偏低 0.50' })
  })

  it('review=true 追加双人核对标记', () => {
    expect(confMeta(0.9, true)).toHaveLength(2)
    expect(confMeta(0.9, false)).toHaveLength(1)
  })
})

describe('srcItems：来源行（最多 6 条，PMID 链接）', () => {
  it('截断与 PMID 链接', () => {
    const items = srcItems(Array.from({ length: 8 }, (_, i) => `来源${i}`))
    expect(items).toHaveLength(6)
    const pmid = srcItems(['PMID:12345678'])[0]
    expect(pmid.href).toBe('https://pubmed.ncbi.nlm.nih.gov/12345678/')
  })

  it('空输入返回空数组', () => {
    expect(srcItems(null)).toEqual([])
    expect(srcItems([])).toEqual([])
  })
})

describe('会诊文本拆分', () => {
  it('splitConsultQuestion 拆病例与 AI 底稿', () => {
    const q = '病例原文【AI 多智能体会诊底稿】底稿内容'
    expect(splitConsultQuestion(q)).toEqual({ case: '病例原文', draft: '底稿内容' })
    expect(splitConsultQuestion('只有病例')).toEqual({ case: '只有病例', draft: '' })
  })

  it('splitAiByDept 按【科室】拆分，无前缀时单块兜底', () => {
    const parts = splitAiByDept('【内科】甲意见\n\n【外科】乙意见')
    expect(parts).toEqual([{ dept: '内科', text: '甲意见' }, { dept: '外科', text: '乙意见' }])
    expect(splitAiByDept('没有科室前缀')).toEqual([{ dept: '', text: '没有科室前缀' }])
    expect(splitAiByDept('')).toEqual([])
  })
})

describe('fmtTs / detailText', () => {
  it('空值与非法值不抛错，非法值原样转义', () => {
    expect(fmtTs('')).toBe('')
    expect(fmtTs('不是时间')).toBe('不是时间')
    expect(fmtTs('<bad>')).toBe('&lt;bad&gt;')
  })

  it('detailText 优先 pydantic 数组 msg，其次字符串，最后走 errText', () => {
    expect(detailText({ response: { data: { detail: [{ msg: '甲' }, { msg: '乙' }] } } })).toBe('甲；乙')
    expect(detailText({ response: { data: { detail: '直接文案' } } })).toBe('直接文案')
    expect(detailText(new Error('x'))).toBe('网络异常')
  })

  it('跨年时间戳带年份，当年保持紧凑（评测 P2-6 合规可追溯）', () => {
    expect(fmtTs('2019-03-05T10:00:00')).toContain('2019')
    const thisYear = String(new Date().getFullYear())
    expect(fmtTs(new Date().toISOString())).not.toContain(thisYear)
  })
})

describe('humanizeAudit：审计事件人话化', () => {
  it('点号事件按后缀匹配动作', () => {
    expect(humanizeAudit({ event: 'review.approved', payload: { rid: 'r1' } })).toContain('签发')
  })

  it('已知动作映射为中文', () => {
    expect(humanizeAudit({ event: 'review', action: 'list_pending', payload: { n: 3 } }))
      .toContain('待核对列表')
  })

  it('未映射事件走兜底但仍带 event_type（不编造）', () => {
    const out = humanizeAudit({ event: 'unknown.thing', action: 'whatever', payload: { k: 'v' } })
    expect(out).toContain('unknown.thing')
    expect(out).toContain('k=v')
  })
})

describe('fmtLogSize / fmtUp：单位换算', () => {
  it('字节/KB/MB 与秒/分/时', () => {
    expect(fmtLogSize(512)).toBe('512 B')
    expect(fmtLogSize(2048)).toBe('2.0 KB')
    expect(fmtLogSize(3 * 1024 * 1024)).toBe('3.00 MB')
    expect(fmtUp(5)).toBe('5s')
    expect(fmtUp(120)).toBe('2m')
    expect(fmtUp(3720)).toBe('1h2m')
  })
})

describe('scrollContentTop：滚真实容器（评测 D3）', () => {
  const fakeEl = (scrollHeight, clientHeight, top = 320) => ({
    scrollHeight, clientHeight, scrollTop: top,
  })

  it('内容容器可滚动时滚 .content', () => {
    const content = fakeEl(1000, 400)
    vi.stubGlobal('document', {
      querySelector: (sel) => (sel === '.content' ? content : null),
      scrollingElement: fakeEl(400, 400, 0),
      documentElement: fakeEl(400, 400, 0),
    })
    scrollContentTop()
    expect(content.scrollTop).toBe(0)
    vi.unstubAllGlobals()
  })

  it('窄屏（.content 不构成滚动容器）时回落文档根', () => {
    const content = fakeEl(400, 400)
    const root = fakeEl(400, 400, 500)
    vi.stubGlobal('document', {
      querySelector: (sel) => (sel === '.content' ? content : null),
      scrollingElement: root,
      documentElement: root,
    })
    scrollContentTop()
    expect(root.scrollTop).toBe(0)
    vi.unstubAllGlobals()
  })
})
