import { get, post } from './http'
import type { PageResult } from './types'

/**
 * 实施交付与培训考核 API（§298）。
 *
 * 操作人由网关注入；上线/验收的"非负责人"由后端强制，前端只做可读提示。
 */
export type ImplProjectStatus = 'PLANNED' | 'IN_PROGRESS' | 'GO_LIVE' | 'ACCEPTED' | 'CANCELLED'
export type ImplTaskStatus = 'PENDING' | 'IN_PROGRESS' | 'BLOCKED' | 'DONE' | 'WAIVED'

export interface ImplTemplateTask {
  id: string
  templateId: string
  phaseId: string
  taskNo: number
  title: string
  description: string | null
  required: boolean
  ownerRole: string | null
  evidenceHint: string | null
}

export interface ImplPhase {
  id: string
  templateId: string
  phaseNo: number
  name: string
  weight: number
  description: string | null
  tasks: ImplTemplateTask[]
}

export interface ImplTemplate {
  id: string
  code: string
  name: string
  description: string | null
  status: 'ACTIVE' | 'INACTIVE'
  createdBy: string
  version: number
  createdAt: string
  updatedAt: string
  phases: ImplPhase[]
}

export interface ImplProjectTask {
  id: string
  projectId: string
  phaseNo: number
  phaseName: string
  phaseWeight: number
  taskNo: number
  title: string
  required: boolean
  ownerRole: string | null
  evidenceHint: string | null
  status: ImplTaskStatus
  assignee: string | null
  dueDate: string | null
  evidence: string | null
  blockedReason: string | null
  waiverReason: string | null
  waiverBy: string | null
  waiverAt: string | null
  completedBy: string | null
  completedAt: string | null
  version: number
}

export interface ImplPhaseProgress {
  phaseNo: number
  name: string
  weight: number
  total: number
  done: number
  ratio: number
  weightedScore: number
}

export interface ImplCoursePass {
  courseCode: string
  title: string
  attempts: number
  passed: number
  passRate: number
  met: boolean
  examCreated: boolean
}

export interface ImplReadiness {
  projectNo: string
  ready: boolean
  score: number
  minScore: number
  trainingThreshold: number
  trainingPassRate: number
  missingRequiredTasks: string[]
  blockedTasks: string[]
  phases: ImplPhaseProgress[]
  training: ImplCoursePass[]
  waivers: number
  warnings: string[]
  generatedAt: string
}

export interface ImplProject {
  id: string
  projectNo: string
  templateId: string
  templateCode: string
  templateName: string
  tenantCode: string | null
  hospitalName: string
  status: ImplProjectStatus
  owner: string
  startDate: string | null
  targetDate: string | null
  goLiveAt: string | null
  approvedBy: string | null
  acceptanceEvidence: string | null
  acceptedAt: string | null
  cancelReason: string | null
  requiredCourseCodes: string[]
  version: number
  createdAt: string
  updatedAt: string
  tasks: ImplProjectTask[]
  readiness: ImplReadiness | null
}

export interface ImplProjectEvent {
  id: number
  projectId: string
  ts: string
  actor: string
  action: string
  note: string | null
  detail: Record<string, unknown>
}

export interface ImplQuestion {
  id: string
  courseId: string
  questionNo: number
  stem: string
  options: Record<string, string>
  answerKey: string
  score: number
  status: string
}

export interface ImplCourse {
  id: string
  code: string
  title: string
  category: 'ROLE' | 'SYSTEM' | 'PROCESS'
  required: boolean
  durationMinutes: number
  description: string | null
  status: 'ACTIVE' | 'INACTIVE'
  createdBy: string
  version: number
  createdAt: string
  updatedAt: string
  questions: ImplQuestion[]
}

export interface ImplAttempt {
  id: string
  examId: string
  trainee: string
  answers: Record<string, string>
  score: number
  passed: boolean
  attemptCount: number
  submittedBy: string
  submittedAt: string
  updatedAt: string
}

export interface ImplExamStats {
  attempts: number
  passed: number
  passRate: number
}

export interface ImplExam {
  id: string
  examNo: string
  projectId: string
  courseId: string
  courseCode: string
  title: string
  passScore: number
  status: 'OPEN' | 'CLOSED'
  createdBy: string
  version: number
  createdAt: string
  updatedAt: string
  attempts: ImplAttempt[]
  stats: ImplExamStats
}

export interface ImplDashboard {
  generatedAt: string
  templates: number
  projects: number
  inProgress: number
  goLive: number
  accepted: number
  blockedTasks: number
  byStatus: Record<string, number>
}

export interface ImplPhaseInput {
  phaseNo: number
  name: string
  weight: number
  description?: string
  tasks: {
    taskNo: number
    title: string
    description?: string
    required?: boolean
    ownerRole?: string
    evidenceHint?: string
  }[]
}

export interface ImplQuestionInput {
  questionNo: number
  stem: string
  options: Record<string, string>
  answerKey: string
  score?: number
}

export const implApi = {
  dashboard: () => get<ImplDashboard>('/ops/impl/dashboard'),

  templates: (onlyActive = true) => get<ImplTemplate[]>('/ops/impl/templates', { onlyActive }),
  template: (id: string) => get<ImplTemplate>(`/ops/impl/templates/${id}`),
  createTemplate: (body: {
    code: string
    name: string
    description?: string
    phases: ImplPhaseInput[]
  }) => post<ImplTemplate>('/ops/impl/templates', body),
  templateStatus: (id: string, status: 'ACTIVE' | 'INACTIVE') =>
    post<ImplTemplate>(`/ops/impl/templates/${id}/status`, { status }),

  projects: (params: { status?: string; page?: number; size?: number }) =>
    get<PageResult<ImplProject>>('/ops/impl/projects', { ...params }),
  project: (id: string) => get<ImplProject>(`/ops/impl/projects/${id}`),
  projectEvents: (id: string, limit = 100) =>
    get<ImplProjectEvent[]>(`/ops/impl/projects/${id}/events`, { limit }),
  readiness: (id: string) => get<ImplReadiness>(`/ops/impl/projects/${id}/readiness`),
  createProject: (body: {
    templateCode: string
    tenantCode?: string
    hospitalName: string
    owner?: string
    startDate?: string
    targetDate?: string
    requiredCourseCodes?: string[]
  }) => post<ImplProject>('/ops/impl/projects', body),
  startProject: (id: string) => post<ImplProject>(`/ops/impl/projects/${id}/start`),
  updateTask: (
    projectId: string,
    taskId: string,
    body: {
      status: 'PENDING' | 'IN_PROGRESS' | 'BLOCKED' | 'DONE'
      assignee?: string
      dueDate?: string
      evidence?: string
      blockedReason?: string
      note?: string
    },
  ) => post<ImplProject>(`/ops/impl/projects/${projectId}/tasks/${taskId}`, body),
  waiveTask: (projectId: string, taskId: string, reason: string) =>
    post<ImplProject>(`/ops/impl/projects/${projectId}/tasks/${taskId}/waive`, { reason }),
  goLive: (id: string, note?: string) =>
    post<ImplProject>(`/ops/impl/projects/${id}/go-live`, { note }),
  accept: (id: string, evidence: string, note?: string) =>
    post<ImplProject>(`/ops/impl/projects/${id}/accept`, { evidence, note }),
  cancel: (id: string, reason: string) =>
    post<ImplProject>(`/ops/impl/projects/${id}/cancel`, { reason }),

  courses: (onlyActive = true) => get<ImplCourse[]>('/ops/impl/courses', { onlyActive }),
  course: (id: string) => get<ImplCourse>(`/ops/impl/courses/${id}`),
  createCourse: (body: {
    code: string
    title: string
    category: 'ROLE' | 'SYSTEM' | 'PROCESS'
    required?: boolean
    durationMinutes?: number
    description?: string
    status?: 'ACTIVE' | 'INACTIVE'
    questions?: ImplQuestionInput[]
  }) => post<ImplCourse>('/ops/impl/courses', body),
  courseStatus: (id: string, status: 'ACTIVE' | 'INACTIVE') =>
    post<ImplCourse>(`/ops/impl/courses/${id}/status`, { status }),

  exams: (projectId: string) => get<ImplExam[]>(`/ops/impl/projects/${projectId}/exams`),
  createExam: (projectId: string, body: { courseCode: string; passScore?: number; title?: string }) =>
    post<ImplExam>(`/ops/impl/projects/${projectId}/exams`, body),
  exam: (id: string) => get<ImplExam>(`/ops/impl/exams/${id}`),
  closeExam: (id: string) => post<ImplExam>(`/ops/impl/exams/${id}/close`),
  recordAttempt: (examId: string, body: { trainee: string; answers: Record<string, string> }) =>
    post<ImplExam>(`/ops/impl/exams/${examId}/attempts`, body),
}
