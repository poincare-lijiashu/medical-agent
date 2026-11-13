<template>
  <div class="panel">
    <!-- 课程与题库 -->
    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">培训课程与题库</span>
        <span class="badge badge--neutral">{{ courses.length }}</span>
        <span class="spacer" />
        <span class="sub">考试按课程开考；成绩自动判分，通过率按唯一学员统计</span>
        <button v-if="canManage" class="btn btn--sm btn--primary" type="button" @click="openCourse">
          <AppIcon name="plus" :size="13" /> 新建课程
        </button>
      </div>
      <TableSkeleton v-if="coursesLoading" :rows="3" />
      <LoadError v-else-if="coursesError" :message="coursesError" :retrying="coursesLoading" @retry="loadCourses" />
      <EmptyState
        v-else-if="courses.length === 0"
        title="还没有培训课程"
        desc="课程是考核与通过率的口径来源；题库至少两题才能开考。"
      />
      <div v-else class="table-wrap">
        <table class="table table--compact">
          <thead>
            <tr>
              <th>编码</th>
              <th>名称</th>
              <th>类别</th>
              <th>必修</th>
              <th>时长</th>
              <th>题目</th>
              <th>状态</th>
              <th v-if="canManage" style="width: 100px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="c in courses" :key="c.id">
              <td class="mono" data-primary>{{ c.code }}</td>
              <td data-label="名称">{{ c.title }}</td>
              <td data-label="类别">{{ categoryText(c.category) }}</td>
              <td data-label="必修">{{ c.required ? '是' : '否' }}</td>
              <td data-label="时长">{{ c.durationMinutes }} 分</td>
              <td data-label="题目">{{ c.questions.length }}</td>
              <td data-label="状态">
                <span class="badge" :class="c.status === 'ACTIVE' ? 'badge--ok' : 'badge--neutral'">
                  {{ c.status === 'ACTIVE' ? '启用' : '停用' }}
                </span>
              </td>
              <td v-if="canManage" data-label="操作">
                <button class="btn btn--sm" type="button" @click="toggleCourse(c)">
                  {{ c.status === 'ACTIVE' ? '停用' : '启用' }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 项目考试 -->
    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">项目考试与通过率</span>
        <span class="spacer" />
        <label class="field f-grow">
          <span class="label">实施项目</span>
          <select v-model="selectedProjectId" class="input" @change="loadExams">
            <option value="">请选择项目</option>
            <option v-for="p in projects" :key="p.id" :value="p.id">
              {{ p.projectNo }} · {{ p.hospitalName }}
            </option>
          </select>
        </label>
        <button v-if="canManage && selectedProjectId" class="btn btn--sm btn--primary" type="button" @click="openExam">
          <AppIcon name="plus" :size="13" /> 开考
        </button>
      </div>
      <p v-if="!selectedProjectId" class="hint">选择项目后查看该项目的考试与成绩；课程通过率会进入上线就绪判定。</p>
      <template v-else>
        <TableSkeleton v-if="examsLoading" :rows="3" />
        <LoadError v-else-if="examsError" :message="examsError" :retrying="examsLoading" @retry="loadExams" />
        <EmptyState
          v-else-if="exams.length === 0"
          title="该项目还没有考试"
          desc="按项目必修课程开考，录入成绩后自动计算通过率。"
        />
        <div v-else class="table-wrap">
          <table class="table table--compact">
            <thead>
              <tr>
                <th>考试号</th>
                <th>课程</th>
                <th>标题</th>
                <th>及格分</th>
                <th>状态</th>
                <th>学员</th>
                <th>通过</th>
                <th>通过率</th>
                <th style="width: 150px">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="e in exams" :key="e.id">
                <td class="mono" data-primary>{{ e.examNo }}</td>
                <td data-label="课程">{{ e.courseCode }}</td>
                <td data-label="标题">{{ e.title }}</td>
                <td data-label="及格分">{{ e.passScore }}</td>
                <td data-label="状态">
                  <span class="badge" :class="e.status === 'OPEN' ? 'badge--ok' : 'badge--neutral'">
                    {{ e.status === 'OPEN' ? '进行中' : '已结束' }}
                  </span>
                </td>
                <td data-label="学员">{{ e.stats.attempts }}</td>
                <td data-label="通过">{{ e.stats.passed }}</td>
                <td data-label="通过率">
                  <span class="badge" :class="e.stats.passRate >= 95 ? 'badge--ok' : 'badge--warn'">
                    {{ e.stats.passRate }}%
                  </span>
                </td>
                <td data-label="操作">
                  <button class="btn btn--sm" type="button" @click="openExamDetail(e)">成绩</button>
                  <button v-if="canManage && e.status === 'OPEN'" class="btn btn--sm" type="button" @click="closeExam(e)">
                    结束
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </div>

    <!-- 考试详情与成绩录入 -->
    <div v-if="detail" class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">{{ detail.examNo }} · {{ detail.title }}</span>
        <span class="badge" :class="detail.status === 'OPEN' ? 'badge--ok' : 'badge--neutral'">
          {{ detail.status === 'OPEN' ? '进行中' : '已结束' }}
        </span>
        <span class="spacer" />
        <span class="sub">及格 {{ detail.passScore }} 分 · 通过率 {{ detail.stats.passRate }}%</span>
        <button
          v-if="canManage && detail.status === 'OPEN'"
          class="btn btn--sm btn--primary"
          type="button"
          @click="openAttempt"
        >
          录入成绩
        </button>
      </div>
      <TableSkeleton v-if="attemptsLoading" :rows="3" />
      <EmptyState
        v-else-if="detail.attempts.length === 0"
        title="还没有成绩"
        desc="录入学员成绩后自动判分；同一学员重考保留最新成绩并累加次数。"
      />
      <div v-else class="table-wrap">
        <table class="table table--compact">
          <thead>
            <tr>
              <th>学员</th>
              <th>分数</th>
              <th>结果</th>
              <th>考试次数</th>
              <th>录入人</th>
              <th>更新时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="a in detail.attempts" :key="a.id">
              <td data-primary>{{ a.trainee }}</td>
              <td>{{ a.score }}</td>
              <td>
                <span class="badge" :class="a.passed ? 'badge--ok' : 'badge--danger'">
                  {{ a.passed ? '通过' : '未通过' }}
                </span>
              </td>
              <td>{{ a.attemptCount }}</td>
              <td>{{ a.submittedBy }}</td>
              <td>{{ fmtTime(a.updatedAt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 新建课程 -->
    <AppModal v-model="courseOpen" title="新建培训课程" width="860px">
      <div class="form-row">
        <label class="field f-q">
          <span class="label">编码 <span class="req">*</span></span>
          <input v-model.trim="courseForm.code" class="input" maxlength="64" placeholder="ROLE-REGISTRAR" />
        </label>
        <label class="field f-grow">
          <span class="label">名称 <span class="req">*</span></span>
          <input v-model.trim="courseForm.title" class="input" maxlength="128" />
        </label>
        <label class="field f-q">
          <span class="label">类别 <span class="req">*</span></span>
          <select v-model="courseForm.category" class="input">
            <option value="SYSTEM">系统</option>
            <option value="ROLE">角色</option>
            <option value="PROCESS">流程</option>
          </select>
        </label>
      </div>
      <div class="form-row">
        <label class="field f-q">
          <span class="label">时长（分钟）</span>
          <input v-model.number="courseForm.durationMinutes" class="input" type="number" min="1" max="1440" />
        </label>
        <label class="field f-q check-field">
          <span class="label">是否必修</span>
          <label class="check-row"><input v-model="courseForm.required" type="checkbox" /> 必修</label>
        </label>
        <label class="field f-grow">
          <span class="label">说明</span>
          <input v-model.trim="courseForm.description" class="input" maxlength="1000" />
        </label>
      </div>

      <div class="question-editor">
        <div class="question-head">
          <span class="label">题目</span>
          <span class="sub">选项用逗号分隔，正确选项填 A/B/C…；不填题目则暂不能开考</span>
          <span class="spacer" />
          <button class="btn btn--sm" type="button" @click="addQuestion">添加题目</button>
        </div>
        <div v-for="(q, index) in courseForm.questions" :key="index" class="question-card">
          <div class="form-row">
            <label class="field f-grow">
              <span class="label">题干 <span class="req">*</span></span>
              <input v-model.trim="q.stem" class="input" maxlength="1000" />
            </label>
            <label class="field f-q">
              <span class="label">分值</span>
              <input v-model.number="q.score" class="input" type="number" min="1" max="100" />
            </label>
            <span class="spacer" />
            <button class="btn btn--sm" type="button" @click="removeQuestion(index)">移除</button>
          </div>
          <div class="form-row">
            <label class="field f-grow">
              <span class="label">选项（逗号分隔） <span class="req">*</span></span>
              <input v-model.trim="q.optionsText" class="input" placeholder="腕带核对,只看床号,口头确认" />
            </label>
            <label class="field f-q">
              <span class="label">正确选项 <span class="req">*</span></span>
              <input v-model.trim="q.answerKey" class="input" maxlength="4" placeholder="A" />
            </label>
          </div>
        </div>
      </div>

      <template #footer>
        <button class="btn" type="button" :disabled="courseBusy" @click="courseOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="courseBusy" @click="submitCourse">
          {{ courseBusy ? '保存中…' : '创建课程' }}
        </button>
      </template>
    </AppModal>

    <!-- 开考 -->
    <AppModal v-model="examOpen" title="按课程开考" width="620px">
      <div class="form-row">
        <label class="field f-grow">
          <span class="label">课程 <span class="req">*</span></span>
          <select v-model="examForm.courseCode" class="input">
            <option value="">请选择</option>
            <option v-for="c in activeCourses" :key="c.code" :value="c.code">{{ c.code }} · {{ c.title }}</option>
          </select>
        </label>
        <label class="field f-q">
          <span class="label">及格分</span>
          <input v-model.number="examForm.passScore" class="input" type="number" min="1" max="100" />
        </label>
      </div>
      <label class="field">
        <span class="label">考试标题</span>
        <input v-model.trim="examForm.title" class="input" maxlength="200" placeholder="默认使用课程名" />
      </label>
      <template #footer>
        <button class="btn" type="button" :disabled="examBusy" @click="examOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="examBusy" @click="submitExam">
          {{ examBusy ? '创建中…' : '开考' }}
        </button>
      </template>
    </AppModal>

    <!-- 成绩录入 -->
    <AppModal v-model="attemptOpen" title="录入考试成绩" width="760px">
      <label class="field">
        <span class="label">学员 <span class="req">*</span></span>
        <input v-model.trim="attemptForm.trainee" class="input" maxlength="64" placeholder="姓名 / 工号" />
      </label>
      <div v-for="q in courseQuestions" :key="q.id" class="attempt-question">
        <div class="q-stem">{{ q.questionNo }}. {{ q.stem }}（{{ q.score }} 分）</div>
        <div class="q-options">
          <label v-for="(label, key) in q.options" :key="key" class="q-option">
            <input v-model="attemptForm.answers[q.id]" type="radio" :value="key" />
            <span>{{ key }}. {{ label }}</span>
          </label>
        </div>
      </div>
      <p v-if="courseQuestions.length === 0" class="hint">该课程没有可用题目，无法录入成绩。</p>
      <template #footer>
        <button class="btn" type="button" :disabled="attemptBusy" @click="attemptOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="attemptBusy || courseQuestions.length === 0" @click="submitAttempt">
          {{ attemptBusy ? '判分中…' : '提交并判分' }}
        </button>
      </template>
    </AppModal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import AppModal from '@/components/ui/AppModal.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import LoadError from '@/components/ui/LoadError.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import {
  implApi,
  type ImplCourse,
  type ImplExam,
  type ImplProject,
  type ImplQuestion,
  type ImplQuestionInput,
} from '@/api/impl'
import { errorText } from '@/api/http'
import { hasPermission } from '@/stores/session'
import { toast } from '@/stores/toast'

const emit = defineEmits<{ changed: [] }>()
const canManage = hasPermission('ops:impl:manage')

const courses = ref<ImplCourse[]>([])
const coursesLoading = ref(false)
const coursesError = ref('')

const projects = ref<ImplProject[]>([])
const selectedProjectId = ref('')
const exams = ref<ImplExam[]>([])
const examsLoading = ref(false)
const examsError = ref('')

const detail = ref<ImplExam | null>(null)
const attemptsLoading = ref(false)
const courseQuestions = ref<ImplQuestion[]>([])

const courseOpen = ref(false)
const courseBusy = ref(false)
const courseForm = reactive({
  code: '',
  title: '',
  category: 'ROLE' as 'ROLE' | 'SYSTEM' | 'PROCESS',
  required: true,
  durationMinutes: 60,
  description: '',
  questions: [] as { stem: string; optionsText: string; answerKey: string; score: number }[],
})

const examOpen = ref(false)
const examBusy = ref(false)
const examForm = reactive({ courseCode: '', passScore: 80, title: '' })

const attemptOpen = ref(false)
const attemptBusy = ref(false)
const attemptForm = reactive({ trainee: '', answers: {} as Record<string, string> })

const activeCourses = computed(() => courses.value.filter((c) => c.status === 'ACTIVE'))

onMounted(async () => {
  await Promise.all([loadCourses(), loadProjects()])
})

async function loadCourses() {
  coursesLoading.value = true
  coursesError.value = ''
  try {
    courses.value = await implApi.courses(false)
    for (const c of courses.value) {
      if (c.questions.length === 0) {
        const full = await implApi.course(c.id)
        c.questions = full.questions
      }
    }
  } catch (e) {
    coursesError.value = errorText(e)
  } finally {
    coursesLoading.value = false
  }
}

async function loadProjects() {
  try {
    const res = await implApi.projects({ page: 1, size: 100 })
    projects.value = res.items.filter((p) => p.status !== 'CANCELLED')
  } catch {
    // 项目列表失败不阻塞课程维护
  }
}

async function loadExams() {
  detail.value = null
  if (!selectedProjectId.value) {
    exams.value = []
    return
  }
  examsLoading.value = true
  examsError.value = ''
  try {
    exams.value = await implApi.exams(selectedProjectId.value)
  } catch (e) {
    examsError.value = errorText(e)
  } finally {
    examsLoading.value = false
  }
}

async function openExamDetail(exam: ImplExam) {
  attemptsLoading.value = true
  try {
    detail.value = await implApi.exam(exam.id)
    const course = await implApi.course(detail.value.courseId)
    courseQuestions.value = course.questions.filter((q) => q.status === 'ACTIVE')
  } catch (e) {
    toast.error('成绩加载失败', errorText(e))
  } finally {
    attemptsLoading.value = false
  }
}

function openCourse() {
  courseForm.code = ''
  courseForm.title = ''
  courseForm.category = 'ROLE'
  courseForm.required = true
  courseForm.durationMinutes = 60
  courseForm.description = ''
  courseForm.questions = [{ stem: '', optionsText: '', answerKey: 'A', score: 50 }]
  courseOpen.value = true
}

function addQuestion() {
  courseForm.questions.push({ stem: '', optionsText: '', answerKey: 'A', score: 50 })
}

function removeQuestion(index: number) {
  courseForm.questions.splice(index, 1)
}

async function submitCourse() {
  if (!courseForm.code || !courseForm.title) {
    toast.warn('请填写课程编码与名称')
    return
  }
  const questions: ImplQuestionInput[] = []
  let no = 1
  for (const q of courseForm.questions) {
    if (!q.stem.trim()) continue
    const options: Record<string, string> = {}
    const labels = q.optionsText.split(/[,，]/).map((s) => s.trim()).filter(Boolean)
    labels.forEach((label, index) => {
      options[String.fromCharCode(65 + index)] = label
    })
    if (labels.length < 2) {
      toast.warn(`题目「${q.stem}」至少需要两个选项`)
      return
    }
    if (!options[q.answerKey.toUpperCase()]) {
      toast.warn(`题目「${q.stem}」的正确选项不在选项中`)
      return
    }
    questions.push({
      questionNo: no++,
      stem: q.stem,
      options,
      answerKey: q.answerKey.toUpperCase(),
      score: q.score,
    })
  }
  courseBusy.value = true
  try {
    await implApi.createCourse({
      code: courseForm.code,
      title: courseForm.title,
      category: courseForm.category,
      required: courseForm.required,
      durationMinutes: courseForm.durationMinutes,
      description: courseForm.description || undefined,
      questions,
    })
    courseOpen.value = false
    toast.ok('课程已创建', courseForm.code)
    await loadCourses()
    emit('changed')
  } catch (e) {
    toast.error('创建失败', errorText(e))
  } finally {
    courseBusy.value = false
  }
}

async function toggleCourse(course: ImplCourse) {
  try {
    await implApi.courseStatus(course.id, course.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE')
    toast.ok(course.status === 'ACTIVE' ? '课程已停用' : '课程已启用', course.code)
    await loadCourses()
    emit('changed')
  } catch (e) {
    toast.error('操作失败', errorText(e))
  }
}

function openExam() {
  examForm.courseCode = activeCourses.value[0]?.code ?? ''
  examForm.passScore = 80
  examForm.title = ''
  examOpen.value = true
}

async function submitExam() {
  if (!selectedProjectId.value || !examForm.courseCode) {
    toast.warn('请选择项目与课程')
    return
  }
  examBusy.value = true
  try {
    await implApi.createExam(selectedProjectId.value, {
      courseCode: examForm.courseCode,
      passScore: examForm.passScore,
      title: examForm.title || undefined,
    })
    examOpen.value = false
    toast.ok('已开考', examForm.courseCode)
    await loadExams()
    emit('changed')
  } catch (e) {
    toast.error('开考失败', errorText(e))
  } finally {
    examBusy.value = false
  }
}

async function closeExam(exam: ImplExam) {
  try {
    await implApi.closeExam(exam.id)
    toast.ok('考试已结束', exam.examNo)
    await loadExams()
    if (detail.value?.id === exam.id) {
      detail.value = await implApi.exam(exam.id)
    }
  } catch (e) {
    toast.error('操作失败', errorText(e))
  }
}

function openAttempt() {
  attemptForm.trainee = ''
  attemptForm.answers = {}
  attemptOpen.value = true
}

async function submitAttempt() {
  if (!detail.value) return
  if (!attemptForm.trainee) {
    toast.warn('请填写学员')
    return
  }
  for (const q of courseQuestions.value) {
    if (!attemptForm.answers[q.id]) {
      toast.warn(`题目 ${q.questionNo} 未作答`)
      return
    }
  }
  attemptBusy.value = true
  try {
    detail.value = await implApi.recordAttempt(detail.value.id, {
      trainee: attemptForm.trainee,
      answers: attemptForm.answers,
    })
    attemptOpen.value = false
    toast.ok('成绩已录入', attemptForm.trainee)
    await loadExams()
    emit('changed')
  } catch (e) {
    toast.error('录入失败', errorText(e))
  } finally {
    attemptBusy.value = false
  }
}

function categoryText(category: 'ROLE' | 'SYSTEM' | 'PROCESS'): string {
  return category === 'ROLE' ? '角色' : category === 'SYSTEM' ? '系统' : '流程'
}

function fmtTime(value?: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}
</script>

<style scoped>
.panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.sheet {
  padding: 14px 16px;
}
.spacer {
  flex: 1;
}
.sub {
  font-size: var(--fs-small);
  color: var(--muted);
}
.f-grow {
  flex: 1 1 220px;
  min-width: 160px;
}
.f-q {
  flex: 0 1 150px;
  min-width: 120px;
}
.form-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: flex-end;
}
.req {
  color: var(--danger-ink, #b91c1c);
}
.hint {
  margin: 6px 0 8px;
  color: var(--muted);
  font-size: var(--fs-small);
  line-height: 1.6;
}
.check-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.check-row {
  display: flex;
  align-items: center;
  gap: 6px;
}
.question-editor {
  margin-top: 10px;
}
.question-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.question-card {
  border: 1px solid var(--line, #e5e7eb);
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 10px;
}
.attempt-question {
  margin-bottom: 12px;
}
.q-stem {
  font-weight: 600;
  margin-bottom: 6px;
}
.q-options {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
}
.q-option {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-body);
}
</style>
