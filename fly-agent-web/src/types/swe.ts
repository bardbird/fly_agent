export type PipelineStatus =
  | 'CREATED'
  | 'RUNNING'
  | 'COMPLETED'
  | 'FAILED'
  | 'SKIPPED'
  | 'DELIVERED'

export interface ApiResult<T> {
  code: string
  message: string
  data: T
  timestamp: number
}

export interface SweTaskCreateRequest {
  taskName: string
  candidateId?: number
  repo?: string
  sourceUrl?: string
  baseCommit?: string
  fixCommit?: string
  repoLanguage?: string
  issueSpecificity?: string
  issueCategories?: string
  samplePath?: string
}

export interface SwePipelineStartRequest {
  taskId: number
  resumeRunId?: number
  resumeFromStage?: string
  samplePath?: string
  workspacePath?: string
}

export interface SweRuntimeSetting {
  key: string
  label: string
  value?: string
  maskedValue?: string
  configured: boolean
  secret: boolean
  description?: string
}

export interface SweRuntimeSettingsResponse {
  settings: SweRuntimeSetting[]
}

export interface SweRuntimeSettingsRequest {
  values: Record<string, string>
}

export interface SweTaskFromCandidateRequest {
  candidateId: number
  taskName?: string
  workspacePath?: string
}

export interface SweArtifact {
  id: number
  runId: number
  artifactType: string
  artifactName: string
  artifactPath: string
  fileSize?: number
  checksum?: string
  createdAt?: string
}

export interface SweStage {
  id: number
  runId: number
  stageCode: string
  stageName: string
  status: PipelineStatus
  sortOrder: number
  resultSummary?: string
  errorMessage?: string
  startedAt?: string
  finishedAt?: string
}

export interface SwePipelineRun {
  id: number
  taskId: number
  candidateId?: number
  status: PipelineStatus
  currentStage?: string
  workspacePath?: string
  errorMessage?: string
  startedAt?: string
  finishedAt?: string
  createdAt?: string
  updatedAt?: string
  stages: SweStage[]
  artifacts: SweArtifact[]
}

export interface SweModelIoResponse {
  apiCallIndex?: number
  timestamp?: number
  configuredModel?: string
  provider?: string
  responseId?: string
  responseModel?: string
  finishReason?: string
  promptTokens?: number
  completionTokens?: number
  totalTokens?: number
  assistantContent?: string
  rawJson?: string
}

export interface SweModelIoAttempt {
  evaluationName: string
  attempt?: number
  runDir: string
  status?: string
  error?: string
  rawResponsePath?: string
  rawResponseLines?: number
  rawResponseBytes?: number
  sweAgentOutputPath?: string
  sweAgentOutputBytes?: number
  sweAgentOutputTail?: string
  modelInputBlocks: string[]
  responses: SweModelIoResponse[]
}

export interface SweModelIoConsole {
  runId: number
  taskId: number
  packagePath?: string
  problemStatementPath?: string
  problemStatement?: string
  guardConfigPath?: string
  guardConfig?: string
  attempts: SweModelIoAttempt[]
}

export interface SweTask {
  id: number
  candidateId?: number
  taskName: string
  repo: string
  sourceUrl?: string
  baseCommit?: string
  fixCommit?: string
  repoLanguage?: string
  issueSpecificity?: string
  issueCategories?: string
  samplePath?: string
  status: PipelineStatus
  createdAt?: string
  updatedAt?: string
  recentRuns: SwePipelineRun[]
}

export type GithubLanguage =
  | 'c'
  | 'c++'
  | 'ruby'
  | 'rust'
  | 'go'
  | 'javascript'
  | 'php'
  | 'ts'
  | 'python'
  | 'java'

export type GithubSortOrder = 'asc' | 'desc'

export interface GithubRepository {
  githubId: number
  name: string
  fullName: string
  htmlUrl: string
  description?: string
  language?: string
  stargazersCount?: number
  forksCount?: number
  openIssuesCount?: number
  defaultBranch?: string
  pushedAt?: string
  archived?: boolean
  disabled?: boolean
  topics: string[]
  productionScore?: number
  candidateGrade?: 'A' | 'B' | 'C'
  gradeReason?: string
  strengths?: string[]
  risks?: string[]
  precheckPlan?: string
}

export interface GithubRepositorySearchResponse {
  language: GithubLanguage
  githubLanguage: string
  totalCount?: number
  incompleteResults?: boolean
  page: number
  perPage: number
  repositories: GithubRepository[]
}

export interface GithubPullCandidate {
  id?: number
  candidateId: string
  repo: string
  number: number
  title?: string
  prUrl: string
  baseCommit?: string
  fixCommit?: string
  mergeCommit?: string
  mergedAt?: string
  updatedAt?: string
  primaryLanguage?: string
  secondaryLanguages?: string
  patchFiles?: number
  sourceFiles?: number
  insertions?: number
  deletions?: number
  totalChanged?: number
  goldPatchFiles?: number
  goldSourceFiles?: number
  goldInsertions?: number
  goldDeletions?: number
  goldTotalChanged?: number
  testPatchFiles?: number
  testInsertions?: number
  testDeletions?: number
  testTotalChanged?: number
  generatedOrI18nRatio?: number
  score?: number
  candidateGrade?: 'A' | 'B' | 'C'
  gradeReason?: string
  candidateStatus?: string
  duplicateStatus?: string
  strengths?: string[]
  risks?: string[]
  precheckPlan?: string
  sampleFiles?: string[]
}

export interface GithubPullScanResponse {
  repo: string
  days: number
  limit: number
  page?: number
  perPage?: number
  nextPage?: number
  hasMore?: boolean
  scannedPulls: number
  mergedPulls: number
  skippedUnmerged: number
  skippedOutOfRange: number
  skippedByFilter: number
  skippedDelivered?: number
  minGoldSourceFiles: number
  maxGoldSourceFiles: number
  minGoldLines: number
  maxGoldLines: number
  candidates: GithubPullCandidate[]
}

export interface GithubPullCandidateListResponse {
  page: number
  perPage: number
  total: number
  totalPages: number
  candidates: GithubPullCandidate[]
}
