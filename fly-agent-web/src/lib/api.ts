import axios from 'axios'
import type { SendMessageRequest } from '@/types/chat'
import type {
  ApiResult,
  GithubLanguage,
  GithubPullCandidateListResponse,
  GithubPullScanResponse,
  GithubRepositorySearchResponse,
  GithubSortOrder,
  SweModelIoConsole,
  SwePipelineRun,
  SwePipelineStartRequest,
  SweTask,
  SweTaskCreateRequest,
  SweTaskFromCandidateRequest,
} from '@/types/swe'

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
