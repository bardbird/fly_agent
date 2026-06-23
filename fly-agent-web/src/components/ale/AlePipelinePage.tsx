import { useEffect, useRef, useState } from 'react'
import { Icon } from '@iconify/react'
import { motion } from 'framer-motion'
import { Button } from '@/components/ui/button'
import { Loading } from '@/components/ui/loading'
import { cn } from '@/lib/utils'
import {
  downloadAleStage2Artifacts,
  downloadAleStage2TaskArtifacts,
  getAleClaudeCodeConfig,
  getAleOptions,
  getAleRun,
  getAleRunLog,
  getAleStage2AgentLog,
  getAleStage2TaskReview,
  listAleRuns,
  saveAleClaudeCodeConfig,
  startAleRun,
  startAleStage2,
  startAleStage2Task,
} from '@/lib/api'
import type { AleClaudeCodeConfig, AleOptionsResponse, AleRun, AleRunRequest, AleRunSummary, AleStage2TaskReview } from '@/types/ale'

// ── constants ────────────────────────────────────────────────────────────────

type PipelineStep = 'stage1' | 'stage2'

const STEPS: Array<{ key: PipelineStep; label: string; icon: string }> = [
  { key: 'stage1', label: 'Stage 1 · 任务生成', icon: 'mdi:hammer-wrench' },
  { key: 'stage2', label: 'Stage 2 · 测评执行', icon: 'mdi:play-circle' },
]

const STATUS_TONE: Record<string, string> = {
  COMPLETED: 'border-emerald-200 bg-emerald-50 text-emerald-700',
  FAILED: 'border-rose-200 bg-rose-50 text-rose-700',
  RUNNING: 'border-blue-200 bg-blue-50 text-blue-700',
  BLOCKED: 'border-amber-200 bg-amber-50 text-amber-700',
  CREATED: 'border-slate-200 bg-slate-50 text-slate-600',
}

const emptyRequest: AleRunRequest = {
  domain: 'computing_math',
  discipline: 'software-engineering',
  scenario: 'task-authoring',
  difficulty: 'medium',
  inputMode: 'brief',
  outputMode: 'task-package',
  verificationMode: 'oracle',
  referenceStrategy: 'hidden-reference',
  targetCount: 1,
  codexModel: 'gpt-5.5',
}

const FALLBACK_DOMAINS = [
  { value: 'computing_math', label: 'Computing Math' },
  { value: 'business_finance', label: 'Business Finance' },
  { value: 'engineering', label: 'Engineering' },
  { value: 'health_medicine', label: 'Health Medicine' },
]
const FALLBACK_MODELS = [{ value: 'gpt-5.5', label: 'gpt-5.5' }]

// ── page ─────────────────────────────────────────────────────────────────────

export function AlePipelinePage() {
  const [step, setStep] = useState<PipelineStep>('stage1')
  const [options, setOptions] = useState<AleOptionsResponse | null>(null)
  const [request, setRequest] = useState<AleRunRequest>(emptyRequest)
  const [runs, setRuns] = useState<AleRunSummary[]>([])
  const [selectedRunId, setSelectedRunId] = useState<number | null>(null)
  const [selectedRun, setSelectedRun] = useState<AleRun | null>(null)
  const [selectedTaskId, setSelectedTaskId] = useState<number | null>(null)
  const [logLines, setLogLines] = useState<string[]>([])
  const [agentLogLines, setAgentLogLines] = useState<string[]>([])
  const [taskReviews, setTaskReviews] = useState<Record<number, AleStage2TaskReview>>({})
  const [claudeConfig, setClaudeConfig] = useState<AleClaudeCodeConfig | null>(null)
  const [claudeDraft, setClaudeDraft] = useState<AleClaudeCodeConfig & { apiKey?: string; authToken?: string }>({})
  const [configSaving, setConfigSaving] = useState(false)
  const [artifactDownloadingTaskId, setArtifactDownloadingTaskId] = useState<number | null>(null)
  const [reviewLoadingTaskId, setReviewLoadingTaskId] = useState<number | null>(null)
  const [rerunningTaskId, setRerunningTaskId] = useState<number | null>(null)
  const [loading, setLoading] = useState(false)
  const [stage2Loading, setStage2Loading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const logRef = useRef<HTMLPreElement>(null)
  const agentLogRef = useRef<HTMLPreElement>(null)

  // ── init + poll ────────────────────────────────────────────────────────

  useEffect(() => {
    getAleOptions().then(setOptions).catch(() => {})
    void refreshClaudeConfig()
    refreshRuns()
  }, [])

  useEffect(() => {
    if (!selectedRunId) return
    void refreshSelectedRun(selectedRunId)
    void refreshRunLog(selectedRunId)
    const timer = window.setInterval(() => {
      void refreshSelectedRun(selectedRunId).then((run) => {
        // auto-switch to stage2 panel when stage2 starts running
        if (run?.stage2Status === 'RUNNING' && step === 'stage1') setStep('stage2')
      })
      void refreshRunLog(selectedRunId)
      void refreshAgentLog(selectedRunId)
      void refreshRuns(false)
      void refreshClaudeConfig(false)
    }, 3000)
    return () => window.clearInterval(timer)
  }, [selectedRunId, step])

  useEffect(() => {
    if (logRef.current) logRef.current.scrollTop = logRef.current.scrollHeight
  }, [logLines])

  useEffect(() => {
    if (agentLogRef.current) agentLogRef.current.scrollTop = agentLogRef.current.scrollHeight
  }, [agentLogLines])

  // ── data ───────────────────────────────────────────────────────────────

  async function refreshRuns(selectLatest = true) {
    const next = await listAleRuns()
    setRuns(next)
    if (selectLatest && !selectedRunId && next[0]) setSelectedRunId(next[0].runId)
  }

  async function refreshSelectedRun(id: number): Promise<AleRun | null> {
    const next = await getAleRun(id)
    setSelectedRun(next)
    setSelectedTaskId((c) => c ?? next.tasks[0]?.id ?? null)
    return next
  }

  async function refreshRunLog(id: number) {
    const lines = await getAleRunLog(id, 400)
    setLogLines(lines)
  }

  async function refreshAgentLog(id: number) {
    const lines = await getAleStage2AgentLog(id, 500)
    setAgentLogLines(lines)
  }

  async function refreshTaskReview(taskId: number) {
    if (!selectedRunId) return
    setReviewLoadingTaskId(taskId)
    try {
      const review = await getAleStage2TaskReview(selectedRunId, taskId)
      setTaskReviews((prev) => ({ ...prev, [taskId]: review }))
    } catch (err) { setError(err instanceof Error ? err.message : 'AI 回顾失败') }
    finally { setReviewLoadingTaskId(null) }
  }

  async function refreshClaudeConfig(syncDraft = true) {
    const next = await getAleClaudeCodeConfig()
    setClaudeConfig(next)
    if (syncDraft) setClaudeDraft(next)
  }

  async function handleSaveClaudeConfig() {
    setConfigSaving(true)
    setError(null)
    try {
      const next = await saveAleClaudeCodeConfig({
        model: claudeDraft.model,
        provider: claudeDraft.provider,
        baseUrl: claudeDraft.baseUrl,
        cliVersion: claudeDraft.cliVersion,
        maxThinkingTokens: claudeDraft.maxThinkingTokens,
        apiKey: claudeDraft.apiKey,
        authToken: claudeDraft.authToken,
      })
      setClaudeConfig(next)
      setClaudeDraft(next)
    } catch (err) { setError(err instanceof Error ? err.message : '保存 Claude Code 配置失败') }
    finally { setConfigSaving(false) }
  }

  async function handleDownloadArtifacts(taskId?: number) {
    if (!selectedRunId) return
    setArtifactDownloadingTaskId(taskId ?? 0)
    setError(null)
    try {
      const blob = taskId
        ? await downloadAleStage2TaskArtifacts(selectedRunId, taskId)
        : await downloadAleStage2Artifacts(selectedRunId)
      downloadBlob(blob, taskId
        ? `ale-stage2-run-${selectedRunId}-task-${taskId}-artifacts.zip`
        : `ale-stage2-run-${selectedRunId}-artifacts.zip`)
    } catch (err) { setError(err instanceof Error ? err.message : '下载产物失败') }
    finally { setArtifactDownloadingTaskId(null) }
  }

  async function handleStartStage1() {
    setLoading(true)
    setError(null)
    try {
      const next = await startAleRun(request)
      await refreshRuns(false)
      setSelectedRunId(next.id)
    } catch (err) { setError(err instanceof Error ? err.message : '启动失败') }
    finally { setLoading(false) }
  }

  async function handleStartStage2() {
    if (!selectedRunId) return
    setStage2Loading(true)
    setError(null)
    try {
      const updated = await startAleStage2(selectedRunId)
      setSelectedRun(updated)
      setTaskReviews({})
      setAgentLogLines([])
      setStep('stage2')
    } catch (err) { setError(err instanceof Error ? err.message : 'Stage2 启动失败') }
    finally { setStage2Loading(false) }
  }

  async function handleRerunTask(taskId: number) {
    if (!selectedRunId) return
    setRerunningTaskId(taskId)
    setError(null)
    try {
      const updated = await startAleStage2Task(selectedRunId, taskId)
      setSelectedRun(updated)
      setTaskReviews((prev) => {
        const next = { ...prev }
        delete next[taskId]
        return next
      })
      setAgentLogLines([])
      setStep('stage2')
    } catch (err) { setError(err instanceof Error ? err.message : 'Task 重跑失败') }
    finally { setRerunningTaskId(null) }
  }

  // ── derived ────────────────────────────────────────────────────────────

  const tasks = selectedRun?.tasks ?? []
  const selectedTask = tasks.find((t) => t.id === selectedTaskId) ?? tasks[0] ?? null
  const domainOptions = options?.domains?.length ? options.domains : FALLBACK_DOMAINS
  const modelOptions = options?.codexModels?.length ? options.codexModels : FALLBACK_MODELS

  // ── render ─────────────────────────────────────────────────────────────

  return (
    <div className="min-h-screen bg-primary text-text-primary">
      {/* header */}
      <div className="border-b border-terminal bg-white/85 backdrop-blur">
        <div className="mx-auto max-w-[1600px] px-4 py-3">
          <h1 className="text-lg font-bold">ALE 任务工厂</h1>
          <p className="text-xs text-text-secondary">Stage 1 生成任务包 → Stage 2 执行测评</p>
        </div>
      </div>

      <div className="mx-auto max-w-[1600px] px-4 py-4">
        {/* step bar */}
        <div className="mb-4 grid gap-2 md:grid-cols-2">
          {STEPS.map((s, i) => (
            <button
              key={s.key}
              onClick={() => setStep(s.key)}
              className={cn(
                'flex min-h-[56px] items-center gap-3 rounded-lg border bg-white px-3 py-2 text-left transition-colors',
                step === s.key ? 'border-cyan bg-primary-50' : 'border-terminal hover:bg-tertiary/40'
              )}
            >
              <span className="flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-lg bg-slate-100 text-xs font-bold text-text-secondary">
                {i + 1}
              </span>
              <span className="flex items-center gap-2 text-sm font-bold">
                <Icon icon={s.icon} className="h-4 w-4 text-cyan" />
                {s.label}
              </span>
            </button>
          ))}
        </div>

        {error && (
          <div className="mb-3 rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-xs text-rose-700">{error}</div>
        )}

        {/* content */}
        {step === 'stage1' ? (
          <Stage1Panel
            request={request}
            onChangeRequest={setRequest}
            domainOptions={domainOptions}
            modelOptions={modelOptions}
            loading={loading}
            onStart={handleStartStage1}
            runs={runs}
            selectedRunId={selectedRunId}
            onSelectRun={setSelectedRunId}
            selectedRun={selectedRun}
            tasks={tasks}
            selectedTask={selectedTask}
            onSelectTask={setSelectedTaskId}
            logLines={logLines}
            logRef={logRef}
            onRefreshLog={() => selectedRunId && refreshRunLog(selectedRunId)}
            stage2Loading={stage2Loading}
            onStartStage2={handleStartStage2}
          />
        ) : (
          <Stage2Panel
            runs={runs}
            selectedRunId={selectedRunId}
            onSelectRun={setSelectedRunId}
            selectedRun={selectedRun}
            tasks={tasks}
            claudeConfig={claudeConfig}
            claudeDraft={claudeDraft}
            onChangeClaudeDraft={setClaudeDraft}
            configSaving={configSaving}
            onRefreshConfig={() => refreshClaudeConfig(true)}
            onSaveConfig={handleSaveClaudeConfig}
            agentLogLines={agentLogLines}
            agentLogRef={agentLogRef}
            onRefreshAgentLog={() => selectedRunId && refreshAgentLog(selectedRunId)}
            taskReviews={taskReviews}
            reviewLoadingTaskId={reviewLoadingTaskId}
            onRefreshTaskReview={refreshTaskReview}
            artifactDownloadingTaskId={artifactDownloadingTaskId}
            onDownloadArtifacts={handleDownloadArtifacts}
            rerunningTaskId={rerunningTaskId}
            onRerunTask={handleRerunTask}
            stage2Loading={stage2Loading}
            onStartStage2={handleStartStage2}
          />
        )}
      </div>
    </div>
  )
}

// ── Stage 1 Panel ────────────────────────────────────────────────────────────

function Stage1Panel({
  request, onChangeRequest, domainOptions, modelOptions,
  loading, onStart,
  runs, selectedRunId, onSelectRun,
  selectedRun, tasks, selectedTask, onSelectTask,
  logLines, logRef, onRefreshLog,
  stage2Loading, onStartStage2,
}: {
  request: AleRunRequest; onChangeRequest: (r: AleRunRequest) => void
  domainOptions: Array<{ value: string; label: string }>
  modelOptions: Array<{ value: string; label: string }>
  loading: boolean; onStart: () => void
  runs: AleRunSummary[]; selectedRunId: number | null; onSelectRun: (id: number) => void
  selectedRun: AleRun | null; tasks: AleRun['tasks']
  selectedTask: AleRun['tasks'][number] | null; onSelectTask: (id: number) => void
  logLines: string[]; logRef: React.RefObject<HTMLPreElement | null>; onRefreshLog: () => void
  stage2Loading: boolean; onStartStage2: () => void
}) {
  return (
    <div className="grid gap-4 lg:grid-cols-[1fr_1.2fr_1.2fr]">
      {/* config + run list */}
      <div className="space-y-3">
        <div className="rounded-lg border border-terminal bg-white p-4">
          <h2 className="mb-3 text-sm font-bold">生成配置</h2>
          <div className="space-y-2">
            <SelectField label="领域" value={request.domain} onChange={(v) => onChangeRequest({ ...request, domain: v })} options={domainOptions} />
            <SelectField label="模型" value={request.codexModel} onChange={(v) => onChangeRequest({ ...request, codexModel: v })} options={modelOptions} />
            <div className="grid grid-cols-2 gap-2">
              <SelectField label="难度" value={request.difficulty} onChange={(v) => onChangeRequest({ ...request, difficulty: v })}
                options={[{ value: 'easy', label: 'Easy' }, { value: 'medium', label: 'Medium' }, { value: 'hard', label: 'Hard' }]} />
              <SelectField label="Task 数" value={String(request.targetCount)} onChange={(v) => onChangeRequest({ ...request, targetCount: Number(v) })}
                options={[{ value: '1', label: '1' }, { value: '4', label: '4' }, { value: '8', label: '8' }]} />
            </div>
          </div>
          <Button size="sm" onClick={onStart} disabled={loading} className="mt-3 w-full">
            {loading ? <Loading size="sm" /> : <Icon icon="mdi:play" className="mr-1 h-4 w-4" />}
            {loading ? '启动中…' : '启动生成'}
          </Button>
        </div>

        {/* run list */}
        <div className="rounded-lg border border-terminal bg-white">
          <div className="border-b border-terminal px-4 py-2 text-xs font-bold">运行记录</div>
          <div className="max-h-[400px] divide-y divide-terminal overflow-y-auto custom-scrollbar">
            {runs.map((run) => (
              <div
                key={run.runId}
                role="button"
                tabIndex={0}
                onClick={() => onSelectRun(run.runId)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' || event.key === ' ') onSelectRun(run.runId)
                }}
                className={cn('w-full cursor-pointer px-3 py-2 text-left hover:bg-primary-50', selectedRunId === run.runId && 'bg-cyan/10')}
              >
                <div className="flex items-center justify-between gap-2">
                  <div className="min-w-0 select-text truncate text-xs" onClick={(event) => event.stopPropagation()}>{run.runKey}</div>
                  <div className="flex shrink-0 items-center gap-1">
                    <CopyButton value={run.runKey} label="复制 run key" />
                    <StatusPill status={run.status} compact />
                  </div>
                </div>
                <div className="select-text text-[10px] text-text-secondary" onClick={(event) => event.stopPropagation()}>
                  {run.domain} · {run.progressPercent}%
                </div>
              </div>
            ))}
            {runs.length === 0 && <div className="px-4 py-6 text-xs text-text-secondary text-center">暂无记录</div>}
          </div>
        </div>
      </div>

      {/* task details + logs */}
      <div className="space-y-3">
        <div className="rounded-lg border border-terminal bg-white p-4">
          <div className="flex items-center justify-between mb-3">
            <h2 className="text-sm font-bold">进度</h2>
            {selectedRun && <StatusPill status={selectedRun.status} />}
          </div>
          {selectedRun ? (
            <div className="space-y-3 text-xs">
              <div className="h-2 rounded-full bg-tertiary">
                <div className="h-full rounded-full bg-cyan" style={{ width: `${selectedRun.progressPercent}%` }} />
              </div>
              <div className="grid grid-cols-3 gap-2">
                <Meta label="完成" value={selectedRun.completedTasks} />
                <Meta label="失败" value={selectedRun.failedTasks} />
                <Meta label="阻塞" value={selectedRun.blockedTasks} />
              </div>
              {selectedRun.errorMessage && <div className="text-rose-600">{selectedRun.errorMessage}</div>}
              {/* quick stage2 start */}
              {selectedRun.status === 'COMPLETED' && (!selectedRun.stage2Status || selectedRun.stage2Status === 'FAILED') && (
                <Button size="sm" onClick={onStartStage2} disabled={stage2Loading} className="w-full mt-2">
                  {stage2Loading ? <Loading size="sm" /> : <Icon icon="mdi:play-circle" className="mr-1 h-4 w-4" />}
                  {stage2Loading ? '…' : '进入 Stage 2 测评'}
                </Button>
              )}
              {selectedRun.stage2Status && (
                <div className="flex items-center gap-2">
                  <span className="text-text-secondary">Stage2:</span>
                  <StatusPill status={selectedRun.stage2Status} compact />
                  {selectedRun.stage2Progress ? <span>{selectedRun.stage2Progress}%</span> : null}
                </div>
              )}
            </div>
          ) : (
            <div className="text-xs text-text-secondary">选择一个 run</div>
          )}
        </div>

        {/* task list */}
        <div className="rounded-lg border border-terminal bg-white">
          <div className="border-b border-terminal px-4 py-2 text-xs font-bold">Task 列表</div>
          <div className="max-h-[300px] divide-y divide-terminal overflow-y-auto custom-scrollbar">
            {tasks.map((task) => (
              <div
                key={task.id}
                role="button"
                tabIndex={0}
                onClick={() => onSelectTask(task.id)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' || event.key === ' ') onSelectTask(task.id)
                }}
                className={cn('w-full cursor-pointer px-3 py-2 text-left hover:bg-primary-50', selectedTask?.id === task.id && 'bg-cyan/10')}
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="select-text truncate text-xs" onClick={(event) => event.stopPropagation()}>{task.taskId}</span>
                  <div className="flex shrink-0 items-center gap-1">
                    <CopyButton value={task.taskId} label="复制 task id" />
                    <StatusPill status={task.status} compact />
                  </div>
                </div>
              </div>
            ))}
            {tasks.length === 0 && <div className="px-4 py-6 text-xs text-text-secondary text-center">暂无 task</div>}
          </div>
        </div>
      </div>

      {/* logs */}
      <div className="flex max-h-[700px] min-h-[420px] min-w-0 flex-col rounded-lg border border-terminal bg-white">
        <div className="flex items-center justify-between border-b border-terminal px-4 py-2">
          <span className="text-xs font-bold">日志</span>
          <button onClick={onRefreshLog} className="text-xs text-cyan flex items-center gap-1">
            <Icon icon="mdi:refresh" className="h-3 w-3" />刷新
          </button>
        </div>
        <pre ref={logRef} className="min-h-0 w-full min-w-0 max-w-full flex-1 overflow-auto whitespace-pre p-3 font-mono text-[11px] leading-5 custom-scrollbar">
          {logLines.length > 0 ? logLines.join('\n') : '暂无日志'}
        </pre>
      </div>
    </div>
  )
}

// ── Stage 2 Panel ────────────────────────────────────────────────────────────

function Stage2Panel({
  runs, selectedRunId, onSelectRun,
  selectedRun, tasks,
  claudeConfig, claudeDraft, onChangeClaudeDraft, configSaving, onRefreshConfig, onSaveConfig,
  agentLogLines, agentLogRef, onRefreshAgentLog,
  taskReviews, reviewLoadingTaskId, onRefreshTaskReview,
  artifactDownloadingTaskId, onDownloadArtifacts,
  rerunningTaskId, onRerunTask,
  stage2Loading, onStartStage2,
}: {
  runs: AleRunSummary[]; selectedRunId: number | null; onSelectRun: (id: number) => void
  selectedRun: AleRun | null; tasks: AleRun['tasks']
  claudeConfig: AleClaudeCodeConfig | null
  claudeDraft: AleClaudeCodeConfig & { apiKey?: string; authToken?: string }
  onChangeClaudeDraft: (draft: AleClaudeCodeConfig & { apiKey?: string; authToken?: string }) => void
  configSaving: boolean; onRefreshConfig: () => void; onSaveConfig: () => void
  agentLogLines: string[]; agentLogRef: React.RefObject<HTMLPreElement | null>; onRefreshAgentLog: () => void
  taskReviews: Record<number, AleStage2TaskReview>
  reviewLoadingTaskId: number | null; onRefreshTaskReview: (taskId: number) => void
  artifactDownloadingTaskId: number | null; onDownloadArtifacts: (taskId?: number) => void
  rerunningTaskId: number | null; onRerunTask: (taskId: number) => void
  stage2Loading: boolean; onStartStage2: () => void
}) {
  const stage2Tasks = tasks.filter((t) => t.stage2Status)
  const completed = stage2Tasks.filter((t) => t.stage2Status === 'completed').length
  const scores = stage2Tasks.map((t) => t.stage2Score ?? 0).filter((s) => s > 0)
  const avg = scores.length ? (scores.reduce((a, b) => a + b, 0) / scores.length).toFixed(3) : '-'

  return (
    <div className="grid gap-4 lg:grid-cols-[1fr_1.5fr_1.5fr]">
      {/* run selector */}
      <div className="space-y-3">
        <div className="rounded-lg border border-terminal bg-white p-4">
          <div className="mb-3 flex items-center justify-between">
            <h2 className="text-sm font-bold">Claude Code 配置</h2>
            <button onClick={onRefreshConfig} className="flex items-center gap-1 text-xs text-cyan">
              <Icon icon="mdi:refresh" className="h-3 w-3" />刷新
            </button>
          </div>
          <div className="space-y-2">
            <TextField label="模型" value={claudeDraft.model ?? ''} onChange={(v) => onChangeClaudeDraft({ ...claudeDraft, model: v })} />
            <TextField label="Base URL" value={claudeDraft.baseUrl ?? ''} onChange={(v) => onChangeClaudeDraft({ ...claudeDraft, baseUrl: v })} />
            <div className="grid grid-cols-2 gap-2">
              <TextField label="Provider" value={claudeDraft.provider ?? 'direct'} onChange={(v) => onChangeClaudeDraft({ ...claudeDraft, provider: v })} />
              <TextField label="Thinking" value={String(claudeDraft.maxThinkingTokens ?? '')} onChange={(v) => onChangeClaudeDraft({ ...claudeDraft, maxThinkingTokens: v ? Number(v) : null })} />
            </div>
            <TextField label="CLI 版本" value={claudeDraft.cliVersion ?? ''} onChange={(v) => onChangeClaudeDraft({ ...claudeDraft, cliVersion: v })} />
            <TextField label={`API Key ${claudeConfig?.apiKeyPreview ? `(${claudeConfig.apiKeyPreview})` : ''}`} value={claudeDraft.apiKey ?? ''} onChange={(v) => onChangeClaudeDraft({ ...claudeDraft, apiKey: v })} password />
            <TextField label={`Auth Token ${claudeConfig?.authTokenPreview ? `(${claudeConfig.authTokenPreview})` : ''}`} value={claudeDraft.authToken ?? ''} onChange={(v) => onChangeClaudeDraft({ ...claudeDraft, authToken: v })} password />
          </div>
          <Button size="sm" onClick={onSaveConfig} disabled={configSaving} className="mt-3 w-full">
            {configSaving ? <Loading size="sm" /> : <Icon icon="mdi:content-save" className="mr-1 h-4 w-4" />}
            保存配置
          </Button>
        </div>

        <div className="rounded-lg border border-terminal bg-white p-4">
          <h2 className="mb-3 text-sm font-bold">选择 Stage 1 Run</h2>
          <div className="max-h-[500px] divide-y divide-terminal overflow-y-auto custom-scrollbar">
            {runs.filter((r) => r.status === 'COMPLETED').map((run) => (
              <div
                key={run.runId}
                role="button"
                tabIndex={0}
                onClick={() => onSelectRun(run.runId)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' || event.key === ' ') onSelectRun(run.runId)
                }}
                className={cn('w-full cursor-pointer px-3 py-2 text-left hover:bg-primary-50', selectedRunId === run.runId && 'bg-cyan/10')}
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="select-text truncate text-xs" onClick={(event) => event.stopPropagation()}>{run.runKey}</span>
                  <div className="flex shrink-0 items-center gap-1">
                    <CopyButton value={run.runKey} label="复制 run key" />
                    <span className="text-[10px] text-text-secondary">{run.totalTasks} tasks</span>
                  </div>
                </div>
              </div>
            ))}
            {runs.filter((r) => r.status === 'COMPLETED').length === 0 && (
              <div className="px-4 py-6 text-xs text-text-secondary text-center">无已完成 run</div>
            )}
          </div>
        </div>
      </div>

      {/* stage2 controls + summary */}
      <div className="space-y-3">
        <div className="rounded-lg border border-terminal bg-white p-4">
          <div className="flex items-center justify-between mb-3">
            <h2 className="text-sm font-bold">Stage 2 测评</h2>
            {selectedRun?.stage2Status && <StatusPill status={selectedRun.stage2Status} />}
          </div>

          {(selectedRun?.status === 'COMPLETED' && !selectedRun.stage2Status) && (
            <Button size="sm" onClick={onStartStage2} disabled={stage2Loading} className="w-full">
              {stage2Loading ? <Loading size="sm" /> : <Icon icon="mdi:play-circle" className="mr-1 h-4 w-4" />}
              {stage2Loading ? '启动中…' : '开始测评'}
            </Button>
          )}

          {selectedRun?.stage2Status === 'RUNNING' && (
            <div className="space-y-2">
              <div className="h-2 rounded-full bg-tertiary">
                <div className="h-full rounded-full bg-green" style={{ width: `${selectedRun.stage2Progress ?? 0}%` }} />
              </div>
              <div className="text-xs text-text-secondary text-center">{selectedRun.stage2Progress ?? 0}%</div>
            </div>
          )}

          {stage2Tasks.length > 0 && (
            <div className="mt-3 grid grid-cols-3 gap-2 text-xs">
              <Meta label="Task 数" value={stage2Tasks.length} />
              <Meta label="完成" value={completed} />
              <Meta label="均分" value={avg} />
            </div>
          )}

          {!selectedRun && (
            <div className="text-xs text-text-secondary text-center py-6">选择左侧已完成的 run</div>
          )}
          {selectedRun && selectedRun.status !== 'COMPLETED' && !selectedRun.stage2Status && (
            <div className="text-xs text-text-secondary text-center py-6">Stage 1 未完成，无法测评</div>
          )}
        </div>

        {/* per-task results */}
        {stage2Tasks.length > 0 && (
          <div className="rounded-lg border border-terminal bg-white">
            <div className="border-b border-terminal px-4 py-2 text-xs font-bold">Task 结果</div>
            <div className="max-h-[400px] divide-y divide-terminal overflow-y-auto custom-scrollbar">
              {stage2Tasks.map((task) => (
                <Stage2TaskResult
                  key={task.id}
                  task={task}
                  review={taskReviews[task.id]}
                  reviewLoading={reviewLoadingTaskId === task.id}
                  downloading={artifactDownloadingTaskId === task.id}
                  rerunning={rerunningTaskId === task.id}
                  stage2Running={selectedRun?.stage2Status === 'RUNNING'}
                  onReview={() => onRefreshTaskReview(task.id)}
                  onDownload={() => onDownloadArtifacts(task.id)}
                  onRerun={() => onRerunTask(task.id)}
                />
              ))}
            </div>
          </div>
        )}
      </div>

      {/* agent log placeholder */}
      <div className="flex max-h-[820px] min-h-[520px] min-w-0 flex-col rounded-lg border border-terminal bg-white">
        <div className="flex items-center justify-between border-b border-terminal px-4 py-2">
          <h2 className="text-sm font-bold">Agent 日志</h2>
          <button onClick={onRefreshAgentLog} className="flex items-center gap-1 text-xs text-cyan">
            <Icon icon="mdi:refresh" className="h-3 w-3" />刷新
          </button>
        </div>
        <pre ref={agentLogRef} className="min-h-0 w-full min-w-0 max-w-full flex-1 overflow-auto whitespace-pre p-3 font-mono text-[11px] leading-5 custom-scrollbar">
          {agentLogLines.length > 0 ? agentLogLines.join('\n') : '暂无 agent 日志'}
        </pre>
      </div>
    </div>
  )
}

function Stage2TaskResult({
  task, review, reviewLoading, downloading, rerunning, stage2Running,
  onReview, onDownload, onRerun,
}: {
  task: AleRun['tasks'][number]
  review?: AleStage2TaskReview
  reviewLoading: boolean
  downloading: boolean
  rerunning: boolean
  stage2Running: boolean
  onReview: () => void
  onDownload: () => void
  onRerun: () => void
}) {
  const score = task.stage2Score
  const needsAttention = task.stage2Status !== 'completed' || score == null || score < 0.95 || Boolean(task.stage2Error)
  const hasStage2Artifact = Boolean(task.stage2Status || task.stage2ResultDir)
  const fixes = review?.suggestedFixes ?? []
  return (
    <div className="px-3 py-2 space-y-2">
      <div className="flex items-center justify-between gap-2">
        <span className="select-text min-w-0 truncate text-xs">{task.taskId}</span>
        <div className="flex shrink-0 items-center gap-1">
          <CopyButton value={task.taskId} label="复制 task id" />
          <StatusPill status={task.stage2Status ?? '-'} compact />
        </div>
      </div>
      <div className="flex gap-3 text-[10px] text-text-secondary">
        <span>得分: {score ?? '-'}</span>
        <span>耗时: {task.stage2DurationS != null ? `${task.stage2DurationS}s` : '-'}</span>
      </div>
      {task.stage2Error && <div className="text-[10px] text-rose-600 break-words">{task.stage2Error}</div>}

      {hasStage2Artifact && (
        <div className="flex flex-wrap gap-2">
          {needsAttention && (
            <Button size="sm" variant="outline" onClick={onReview} disabled={reviewLoading || stage2Running} className="h-7 px-2 text-[11px]">
              {reviewLoading ? <TinyButtonLoading /> : <Icon icon="mdi:brain" className="mr-1 h-3.5 w-3.5" />}
              回顾
            </Button>
          )}
          <Button size="sm" variant="outline" onClick={onDownload} disabled={downloading} className="h-7 px-2 text-[11px]">
            {downloading ? <TinyButtonLoading /> : <Icon icon="mdi:download" className="mr-1 h-3.5 w-3.5" />}
            下载
          </Button>
          {needsAttention && (
            <Button size="sm" onClick={onRerun} disabled={rerunning || stage2Running} className="h-7 px-2 text-[11px]">
              {rerunning ? <TinyButtonLoading /> : <Icon icon="mdi:restart" className="mr-1 h-3.5 w-3.5" />}
              重跑
            </Button>
          )}
        </div>
      )}

      {review && needsAttention && (
        <div className="rounded-md border border-amber-200 bg-amber-50 p-2 text-[11px]">
          <div className="break-words text-text-primary">{review.summary ?? '暂无回顾摘要'}</div>
          {review.evidence?.length ? (
            <div className="mt-1 break-words text-text-secondary">{review.evidence[0]}</div>
          ) : null}
          {fixes.length > 0 && (
            <div className="mt-2 space-y-1">
              {dedupeStrings(fixes).slice(0, 2).map((item, idx) => (
                <div key={idx} className="break-words text-text-secondary">{item}</div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

// ── shared components ────────────────────────────────────────────────────────

function TinyButtonLoading() {
  return (
    <span className="mr-1 inline-flex h-3 w-4 items-center justify-center gap-0.5">
      {[0, 1, 2].map((i) => (
        <motion.span
          key={i}
          animate={{ y: [0, -2, 0], opacity: [0.55, 1, 0.55] }}
          transition={{ duration: 0.6, repeat: Infinity, delay: i * 0.1 }}
          className="h-[3px] w-[3px] rounded-full bg-cyan"
        />
      ))}
    </span>
  )
}

function StatusPill({ status, compact }: { status: string; compact?: boolean }) {
  const tone = STATUS_TONE[status] ?? 'border-terminal bg-white text-text-secondary'
  return (
    <span className={cn('rounded-full border px-2 py-0.5 text-[10px] font-medium', tone, compact && 'shrink-0')}>
      {status === 'completed' ? '完成' : status === 'failed' ? '失败' : status === 'RUNNING' ? '执行中' : status}
    </span>
  )
}

function CopyButton({ value, label }: { value: string; label: string }) {
  return (
    <button
      type="button"
      title={label}
      aria-label={label}
      onClick={(event) => {
        event.stopPropagation()
        void navigator.clipboard?.writeText(value)
      }}
      className="flex h-6 w-6 shrink-0 items-center justify-center rounded-md text-text-secondary hover:bg-primary-50 hover:text-cyan"
    >
      <Icon icon="mdi:content-copy" className="h-3.5 w-3.5" />
    </button>
  )
}

function Meta({ label, value }: { label: string; value: string | number }) {
  return (
    <div className="rounded-md border border-terminal px-2 py-1.5">
      <div className="text-[10px] text-text-secondary">{label}</div>
      <div className="text-xs font-medium">{value}</div>
    </div>
  )
}

function SelectField({
  label, value, onChange, options,
}: {
  label: string; value: string; onChange: (v: string) => void
  options: Array<{ value: string; label: string }>
}) {
  return (
    <label className="flex flex-col gap-1 text-xs">
      <span className="text-text-secondary">{label}</span>
      <select
        className="h-9 rounded-lg border border-terminal bg-white px-3 text-xs outline-none focus:border-cyan"
        value={value} onChange={(e) => onChange(e.target.value)}
      >
        {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>
    </label>
  )
}

function TextField({
  label, value, onChange, password,
}: {
  label: string; value: string; onChange: (v: string) => void; password?: boolean
}) {
  return (
    <label className="flex flex-col gap-1 text-xs">
      <span className="text-text-secondary">{label}</span>
      <input
        type={password ? 'password' : 'text'}
        className="h-9 min-w-0 rounded-lg border border-terminal bg-white px-3 text-xs outline-none focus:border-cyan"
        value={value}
        onChange={(event) => onChange(event.target.value)}
      />
    </label>
  )
}

function downloadBlob(blob: Blob, filename: string) {
  const url = window.URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  window.URL.revokeObjectURL(url)
}

function dedupeStrings(values: string[]) {
  return Array.from(new Set(values.filter(Boolean)))
}
