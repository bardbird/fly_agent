import type { ApiResult } from './swe'

export type { ApiResult }

export type AleRunStatus = 'CREATED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'BLOCKED'

export interface AleOption {
  value: string
  label: string
}

export interface AleOptionsResponse {
  domains: AleOption[]
  disciplines: AleOption[]
  scenarios: AleOption[]
  difficulties: AleOption[]
  inputModes: AleOption[]
  outputModes: AleOption[]
  verificationModes: AleOption[]
  referenceStrategies: AleOption[]
  codexModels: AleOption[]
}

export interface AleRunRequest {
  domain: string
  discipline: string
  scenario: string
  difficulty: string
  inputMode: string
  outputMode: string
  verificationMode: string
  referenceStrategy: string
  targetCount: number
  codexModel: string
}

export interface AleTask {
  id: number
  runId: number
  taskId: string
  title?: string
  domain: string
  discipline?: string
  scenario?: string
  difficulty?: string
  status: string
  score?: number
  taskDir?: string
  evidencePath?: string
  summary?: string
  errorMessage?: string
  createdAt?: string
  updatedAt?: string
}

export interface AleRunSummary {
  runId: number
  runKey: string
  domain: string
  discipline: string
  scenario: string
  difficulty: string
  status: AleRunStatus
  progressPercent: number
  totalTasks: number
  completedTasks: number
  failedTasks: number
  blockedTasks: number
  outputRoot?: string
  logPath?: string
  summaryPath?: string
  errorMessage?: string
  domainStats: Record<string, number>
}

export interface AleRun extends AleRunSummary {
  id: number
  domain: string
  discipline: string
  scenario: string
  difficulty: string
  inputMode: string
  outputMode: string
  verificationMode: string
  referenceStrategy: string
  targetCount: number
  codexModel: string
  startedAt?: string
  finishedAt?: string
  createdAt?: string
  updatedAt?: string
  tasks: AleTask[]
}
