import axios from 'axios'
import type { SendMessageRequest } from '@/types/chat'
import type {
  ApiResult,
  GithubTokenPoolResponse,
  GithubTokenPoolSaveRequest,
  GithubLanguage,
  GithubPullCandidateListResponse,
  GithubPullScanResponse,
  GithubRepositorySearchResponse,
  GithubSortOrder,
  SweAllowedRepoListResponse,
  SweModelIoConsole,
  SwePipelineRun,
  SwePipelineStartRequest,
  SweRuntimeSettingsRequest,
  SweRuntimeSettingsResponse,
  SweTask,
  SweTaskCreateRequest,
  SweTaskFromCandidateRequest,
} from '@/types/swe'
import type {
  Tb20ApiResult,
  Tb20Blueprint,
  Tb20ConfigRequest,
  Tb20ConfigResponse,
  Tb20DatasetRunRequest,
  Tb20DependencyStatus,
  Tb20ExecutionRunRequest,
  Tb20InspectRequest,
  Tb20PipelineResponse,
  Tb20Run,
  Tb20Task,
} from '@/types/tb20'

export const api = axios.create({
  baseURL: '/api/v1',
  timeout: 30000,
})

const GITHUB_PULL_SCAN_TIMEOUT_MS = 5 * 60 * 1000

function unwrapResult<T>(response: { data: ApiResult<T> }): T {
  if (response.data.code !== 'SUCCESS') {
    throw new Error(response.data.message || '请求失败')
  }
  return response.data.data
}

function unwrapTb20Result<T>(response: { data: Tb20ApiResult<T> }): T {
  if (response.data.code !== 'SUCCESS') {
    throw new Error(response.data.message || '请求失败')
  }
  return response.data.data
}

function normalizeRun(run: SwePipelineRun | null | undefined): SwePipelineRun {
  return {
    id: run?.id ?? 0,
    taskId: run?.taskId ?? 0,
    status: run?.status ?? 'CREATED',
    ...run,
    stages: Array.isArray(run?.stages) ? run.stages : [],
    artifacts: Array.isArray(run?.artifacts) ? run.artifacts : [],
  }
}

function normalizeTask(task: SweTask | null | undefined): SweTask {
  return {
    id: task?.id ?? 0,
    taskName: task?.taskName ?? '',
    repo: task?.repo ?? '',
    status: task?.status ?? 'CREATED',
    ...task,
    recentRuns: Array.isArray(task?.recentRuns) ? task.recentRuns.map(normalizeRun) : [],
  }
}

function normalizeGithubTokenPool(response: GithubTokenPoolResponse | null | undefined): GithubTokenPoolResponse {
  const tokens = Array.isArray(response?.tokens) ? response.tokens : []
  return {
    tokens,
    totalCount: response?.totalCount ?? tokens.length,
    availableCount: response?.availableCount ?? tokens.filter((token) => token.available).length,
    inUseCount: response?.inUseCount ?? tokens.filter((token) => token.inUse).length,
    unavailableTodayCount:
      response?.unavailableTodayCount ?? tokens.filter((token) => token.unavailableToday).length,
  }
}

// 请求拦截器
api.interceptors.request.use((config) => {
  // 添加认证 token(如果需要)
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 响应拦截器
api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      // 跳转登录
      window.location.href = '/login'
    }
    const message = error.response?.data?.message || error.message
    return Promise.reject(new Error(message))
  }
)

// 发送消息(非流式)
export async function sendMessage(data: SendMessageRequest): Promise<string> {
  const response = await api.post('/chat/completions', data)
  return response.data
}

// 流式发送消息
export async function sendMessageStream(
  data: SendMessageRequest,
  onChunk: (chunk: string, isFullContent: boolean) => void,
  onComplete: () => void,
  onError: (error: Error) => void
) {
  try {
    const response = await fetch('/api/v1/chat/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(data),
    })

    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`)
    }

    const reader = response.body?.getReader()
    const decoder = new TextDecoder()

    if (!reader) {
      throw new Error('Response body is null')
    }

    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })

      // 处理 SSE 格式的数据
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''

      for (const line of lines) {
        if (line.startsWith('data:')) {
          const data = line.slice(5).trim()
          if (data === '[DONE]') {
            onComplete()
            return
          }
          // 检查是否是 JSON 格式（完整内容）
          if (data.startsWith('{') && data.endsWith('}')) {
            try {
              const jsonData = JSON.parse(data) as { isLast: boolean; content: string }
              // 还原转义字符：\\n -> \n, \\r -> \r, \\t -> \t
              const decodedContent = jsonData.content
                .replace(/\\n/g, '\n')
                .replace(/\\r/g, '\r')
                .replace(/\\t/g, '\t')
                .replace(/\\\\/g, '\\')
              // 发送完整内容和 isLast 标记
              onChunk(decodedContent, true)
            } catch (e) {
              // JSON 解析失败，当作普通文本处理
              onChunk(data, false)
            }
          } else if (data) {
            // 普通增量文本
            onChunk(data, false)
          }
        }
      }
    }

    onComplete()
  } catch (error) {
    onError(error as Error)
  }
}

// 获取对话历史
export async function getConversationHistory(conversationId: string) {
  const response = await api.get('/conversations/messages', {
    params: { conversationId },
  })
  return response.data
}

// 获取会话列表
export async function getConversations() {
  const response = await api.get('/conversations')
  return response.data
}

// 创建会话
export async function createConversation(): Promise<{ sessionId: string }> {
  const response = await api.post('/chat/conversations')
  return response.data
}

// 删除会话
export async function deleteConversation(id: string) {
  const response = await api.delete('/conversations', { data: { id } })
  return response.data
}

export async function listSweTasks(): Promise<SweTask[]> {
  const tasks = unwrapResult(await api.get<ApiResult<SweTask[]>>('/swe/tasks'))
  return Array.isArray(tasks) ? tasks.map(normalizeTask) : []
}

export async function getSweTask(id: number): Promise<SweTask> {
  return normalizeTask(unwrapResult(
    await api.get<ApiResult<SweTask>>('/swe/tasks/detail', { params: { id } })
  ))
}

export async function createSweTask(
  data: SweTaskCreateRequest
): Promise<SweTask> {
  return normalizeTask(unwrapResult(await api.post<ApiResult<SweTask>>('/swe/tasks', data)))
}

export async function createSweTaskFromCandidate(
  data: SweTaskFromCandidateRequest
): Promise<SweTask> {
  return normalizeTask(
    unwrapResult(await api.post<ApiResult<SweTask>>('/swe/tasks/from-candidate', data))
  )
}

export async function startSweRun(
  data: SwePipelineStartRequest
): Promise<SwePipelineRun> {
  return normalizeRun(unwrapResult(
    await api.post<ApiResult<SwePipelineRun>>('/swe/runs/start', data)
  ))
}

export async function listSweRuns(taskId?: number): Promise<SwePipelineRun[]> {
  const runs = unwrapResult(
    await api.get<ApiResult<SwePipelineRun[]>>('/swe/runs', {
      params: taskId ? { taskId } : undefined,
    })
  )
  return Array.isArray(runs) ? runs.map(normalizeRun) : []
}

export async function getSweRun(runId: number): Promise<SwePipelineRun> {
  return normalizeRun(unwrapResult(
    await api.get<ApiResult<SwePipelineRun>>('/swe/runs/detail', {
      params: { runId },
    })
  ))
}

export async function getSweModelIoConsole(runId: number): Promise<SweModelIoConsole> {
  const console = unwrapResult(
    await api.get<ApiResult<SweModelIoConsole>>('/swe/runs/model-io', {
      params: { runId },
    })
  )
  return {
    ...console,
    attempts: Array.isArray(console?.attempts)
      ? console.attempts.map((attempt) => ({
          ...attempt,
          modelInputBlocks: Array.isArray(attempt.modelInputBlocks) ? attempt.modelInputBlocks : [],
          responses: Array.isArray(attempt.responses) ? attempt.responses : [],
        }))
      : [],
  }
}

export async function getSweRuntimeSettings(): Promise<SweRuntimeSettingsResponse> {
  const response = unwrapResult(
    await api.get<ApiResult<SweRuntimeSettingsResponse>>('/swe/settings')
  )
  return {
    settings: Array.isArray(response?.settings) ? response.settings : [],
  }
}

export async function saveSweRuntimeSettings(
  data: SweRuntimeSettingsRequest
): Promise<SweRuntimeSettingsResponse> {
  const response = unwrapResult(
    await api.post<ApiResult<SweRuntimeSettingsResponse>>('/swe/settings', data)
  )
  return {
    settings: Array.isArray(response?.settings) ? response.settings : [],
  }
}

export async function getGithubTokenPool(): Promise<GithubTokenPoolResponse> {
  return normalizeGithubTokenPool(
    unwrapResult(await api.get<ApiResult<GithubTokenPoolResponse>>('/swe/github/tokens'))
  )
}

export async function addGithubTokens(
  data: GithubTokenPoolSaveRequest
): Promise<GithubTokenPoolResponse> {
  return normalizeGithubTokenPool(
    unwrapResult(await api.post<ApiResult<GithubTokenPoolResponse>>('/swe/github/tokens', data))
  )
}

export async function deleteGithubTokens(ids: string[]): Promise<GithubTokenPoolResponse> {
  return normalizeGithubTokenPool(
    unwrapResult(await api.post<ApiResult<GithubTokenPoolResponse>>('/swe/github/tokens/delete', { ids }))
  )
}

export async function enableGithubTokens(ids: string[]): Promise<GithubTokenPoolResponse> {
  return normalizeGithubTokenPool(
    unwrapResult(await api.post<ApiResult<GithubTokenPoolResponse>>('/swe/github/tokens/enable', { ids }))
  )
}

export async function disableGithubTokens(ids: string[]): Promise<GithubTokenPoolResponse> {
  return normalizeGithubTokenPool(
    unwrapResult(await api.post<ApiResult<GithubTokenPoolResponse>>('/swe/github/tokens/disable', { ids }))
  )
}

export async function resetGithubTokenTodayStatus(ids: string[]): Promise<GithubTokenPoolResponse> {
  return normalizeGithubTokenPool(
    unwrapResult(await api.post<ApiResult<GithubTokenPoolResponse>>('/swe/github/tokens/reset-today', { ids }))
  )
}

export async function searchGithubRepositories(params: {
  language: GithubLanguage
  keyword?: string
  minStars?: number
  maxStars?: number
  page?: number
  perPage?: number
  sort?: string
  order?: GithubSortOrder
}): Promise<GithubRepositorySearchResponse> {
  const response = unwrapResult(
    await api.get<ApiResult<GithubRepositorySearchResponse>>(
      '/swe/github/repositories/search',
      { params }
    )
  )
  return {
    ...response,
    repositories: Array.isArray(response?.repositories) ? response.repositories : [],
  }
}

export async function scanGithubMergedPullCandidates(params: {
  repo: string
  limit?: number
  days?: number
  minGoldSourceFiles?: number
  maxGoldSourceFiles?: number
  minGoldLines?: number
  maxGoldLines?: number
  page?: number
  perPage?: number
}): Promise<GithubPullScanResponse> {
  const response = unwrapResult(
    await api.get<ApiResult<GithubPullScanResponse>>(
      '/swe/github/pulls/merged-candidates',
      { params, timeout: GITHUB_PULL_SCAN_TIMEOUT_MS }
    )
  )
  return {
    ...response,
    candidates: Array.isArray(response?.candidates) ? response.candidates : [],
  }
}

export async function listSweCandidates(params?: {
  page?: number
  perPage?: number
  candidateStatus?: string
  duplicateStatus?: string
  language?: string
  dateField?: string
  dateFrom?: string
  dateTo?: string
}): Promise<GithubPullCandidateListResponse> {
  const response = unwrapResult(
    await api.get<ApiResult<GithubPullCandidateListResponse>>('/swe/candidates', { params })
  )
  return {
    page: response?.page ?? params?.page ?? 1,
    perPage: response?.perPage ?? params?.perPage ?? 10,
    total: response?.total ?? 0,
    totalPages: response?.totalPages ?? 1,
    candidates: Array.isArray(response?.candidates) ? response.candidates : [],
  }
}

export async function exportSweCandidates(params?: {
  candidateStatus?: string
  duplicateStatus?: string
  language?: string
  dateField?: string
  dateFrom?: string
  dateTo?: string
}): Promise<Blob> {
  const response = await api.get('/swe/candidates/export', {
    params,
    responseType: 'blob',
  })
  return response.data
}

export async function listSweAllowedRepos(params?: {
  page?: number
  perPage?: number
  language?: string
  inCandidate?: boolean
  checkedFrom?: string
  checkedTo?: string
}): Promise<SweAllowedRepoListResponse> {
  const response = unwrapResult(
    await api.get<ApiResult<SweAllowedRepoListResponse>>('/swe/sca-report/allowed-repos', {
      params,
    })
  )
  return {
    page: response?.page ?? params?.page ?? 1,
    perPage: response?.perPage ?? params?.perPage ?? 20,
    total: response?.total ?? 0,
    totalPages: response?.totalPages ?? 1,
    repositories: Array.isArray(response?.repositories) ? response.repositories : [],
  }
}

export async function exportSweAllowedRepos(params?: {
  language?: string
  inCandidate?: boolean
  checkedFrom?: string
  checkedTo?: string
}): Promise<Blob> {
  const response = await api.get('/swe/sca-report/allowed-repos/export', {
    params,
    responseType: 'blob',
  })
  return response.data
}

function normalizeTb20Response(response: Tb20PipelineResponse): Tb20PipelineResponse {
  return {
    ...response,
    summary: response?.summary ?? {},
    tasks: Array.isArray(response?.tasks) ? response.tasks : [],
    dependencies: Array.isArray(response?.dependencies) ? response.dependencies : [],
  }
}

export async function getTb20Blueprint(params?: {
  harborRoot?: string
  terminalBenchRoot?: string
}): Promise<Tb20Blueprint> {
  const response = unwrapTb20Result(
    await api.get<Tb20ApiResult<Tb20Blueprint>>('/tb20/blueprint', { params })
  )
  return {
    ...response,
    requiredTaskFiles: Array.isArray(response?.requiredTaskFiles) ? response.requiredTaskFiles : [],
    optionalDeliveryLogs: Array.isArray(response?.optionalDeliveryLogs) ? response.optionalDeliveryLogs : [],
    stages: Array.isArray(response?.stages) ? response.stages : [],
    nonAutomatableBoundaries: Array.isArray(response?.nonAutomatableBoundaries)
      ? response.nonAutomatableBoundaries
      : [],
    aiScaleOutControls: Array.isArray(response?.aiScaleOutControls)
      ? response.aiScaleOutControls
      : [],
    dependencies: Array.isArray(response?.dependencies) ? response.dependencies : [],
  }
}

export async function checkTb20Dependencies(params?: {
  harborRoot?: string
  terminalBenchRoot?: string
}): Promise<Tb20DependencyStatus[]> {
  const response = unwrapTb20Result(
    await api.get<Tb20ApiResult<Tb20DependencyStatus[]>>('/tb20/dependencies/check', { params })
  )
  return Array.isArray(response) ? response : []
}

export async function inspectTb20Dataset(data: Tb20InspectRequest): Promise<Tb20PipelineResponse> {
  return normalizeTb20Response(
    unwrapTb20Result(await api.post<Tb20ApiResult<Tb20PipelineResponse>>('/tb20/inspect', data))
  )
}

export async function runTb20Single(data: Tb20InspectRequest): Promise<Tb20PipelineResponse> {
  return normalizeTb20Response(
    unwrapTb20Result(
      await api.post<Tb20ApiResult<Tb20PipelineResponse>>('/tb20/runs/single', data, {
        timeout: 120000,
      })
    )
  )
}

export async function runTb20Batch(data: Tb20InspectRequest): Promise<Tb20PipelineResponse> {
  return normalizeTb20Response(
    unwrapTb20Result(
      await api.post<Tb20ApiResult<Tb20PipelineResponse>>('/tb20/runs/batch', data, {
        timeout: 120000,
      })
    )
  )
}

function normalizeTb20Run(run: Tb20Run | null | undefined): Tb20Run {
  return {
    runId: run?.runId ?? '',
    kind: run?.kind ?? 'DATASET_PRODUCTION',
    status: run?.status ?? 'RUNNING',
    skillName: run?.skillName ?? '',
    workspace: run?.workspace ?? '',
    outputRoot: run?.outputRoot ?? '',
    logPath: run?.logPath,
    command: Array.isArray(run?.command) ? run.command : [],
    stages: Array.isArray(run?.stages) ? run.stages : [],
    artifacts: Array.isArray(run?.artifacts) ? run.artifacts : [],
    startedAt: run?.startedAt,
    finishedAt: run?.finishedAt,
    exitCode: run?.exitCode,
    errorMessage: run?.errorMessage,
  }
}

export async function getTb20Config(data: Tb20ConfigRequest): Promise<Tb20ConfigResponse> {
  const response = unwrapTb20Result(
    await api.post<Tb20ApiResult<Tb20ConfigResponse>>('/tb20/config/get', data)
  )
  return {
    scope: response?.scope ?? data.scope,
    values: response?.values ?? {},
  }
}

export async function saveTb20Config(data: Tb20ConfigRequest): Promise<Tb20ConfigResponse> {
  const response = unwrapTb20Result(
    await api.post<Tb20ApiResult<Tb20ConfigResponse>>('/tb20/config/save', data)
  )
  return {
    scope: response?.scope ?? data.scope,
    values: response?.values ?? {},
  }
}

export async function startTb20DatasetRun(data: Tb20DatasetRunRequest): Promise<Tb20Run> {
  return normalizeTb20Run(
    unwrapTb20Result(await api.post<Tb20ApiResult<Tb20Run>>('/tb20/dataset-runs/start', data, {
      timeout: 120000,
    }))
  )
}

export async function startTb20ExecutionRun(data: Tb20ExecutionRunRequest): Promise<Tb20Run> {
  return normalizeTb20Run(
    unwrapTb20Result(await api.post<Tb20ApiResult<Tb20Run>>('/tb20/execution-runs/start', data, {
      timeout: 120000,
    }))
  )
}

export async function listTb20Runs(): Promise<Tb20Run[]> {
  const runs = unwrapTb20Result(await api.post<Tb20ApiResult<Tb20Run[]>>('/tb20/runs/list', {}))
  return Array.isArray(runs) ? runs.map(normalizeTb20Run) : []
}

export async function getTb20Run(runId: string): Promise<Tb20Run> {
  return normalizeTb20Run(
    unwrapTb20Result(await api.post<Tb20ApiResult<Tb20Run>>('/tb20/runs/detail', { runId }))
  )
}

export async function getTb20RunLog(runId: string): Promise<string> {
  return unwrapTb20Result(await api.post<Tb20ApiResult<string>>('/tb20/runs/log', { runId }))
}

export type { Tb20Task }
