import { useEffect, useRef, useState } from 'react'
import { Icon } from '@iconify/react'
import { Button } from '@/components/ui/button'
import { Loading } from '@/components/ui/loading'
import { cn } from '@/lib/utils'
import {
  getAleOptions,
  getAleRun,
  getAleRunLog,
  listAleRuns,
  startAleRun,
  startAleStage2,
} from '@/lib/api'
import type { AleOptionsResponse, AleRun, AleRunRequest, AleRunSummary } from '@/types/ale'

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
  const [loading, setLoading] = useState(false)
  const [stage2Loading, setStage2Loading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const logRef = useRef<HTMLPreElement>(null)

  // ── init + poll ────────────────────────────────────────────────────────

  useEffect(() => {
    getAleOptions().then(setOptions).catch(() => {})
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
      void refreshRuns(false)
    }, 3000)
    return () => window.clearInterval(timer)
  }, [selectedRunId, step])

  useEffect(() => {
    if (logRef.current) logRef.current.scrollTop = logRef.current.scrollHeight
  }, [logLines])

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
      setStep('stage2')
    } catch (err) { setError(err instanceof Error ? err.message : 'Stage2 启动失败') }
    finally { setStage2Loading(false) }
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
              <button
                key={run.runId}
                onClick={() => onSelectRun(run.runId)}
                className={cn('w-full px-3 py-2 text-left hover:bg-primary-50', selectedRunId === run.runId && 'bg-cyan/10')}
              >
                <div className="flex items-center justify-between gap-2">
                  <div className="min-w-0 text-xs truncate">{run.runKey}</div>
                  <StatusPill status={run.status} compact />
                </div>
                <div className="text-[10px] text-text-secondary">{run.domain} · {run.progressPercent}%</div>
              </button>
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
              <button
                key={task.id}
                onClick={() => onSelectTask(task.id)}
                className={cn('w-full px-3 py-2 text-left hover:bg-primary-50', selectedTask?.id === task.id && 'bg-cyan/10')}
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="text-xs truncate">{task.taskId}</span>
                  <StatusPill status={task.status} compact />
                </div>
              </button>
            ))}
            {tasks.length === 0 && <div className="px-4 py-6 text-xs text-text-secondary text-center">暂无 task</div>}
          </div>
        </div>
      </div>

      {/* logs */}
      <div className="rounded-lg border border-terminal bg-white flex flex-col max-h-[700px]">
        <div className="flex items-center justify-between border-b border-terminal px-4 py-2">
          <span className="text-xs font-bold">日志</span>
          <button onClick={onRefreshLog} className="text-xs text-cyan flex items-center gap-1">
            <Icon icon="mdi:refresh" className="h-3 w-3" />刷新
          </button>
        </div>
        <pre ref={logRef} className="flex-1 overflow-y-auto whitespace-pre-wrap break-words p-3 font-mono text-[11px] leading-5 custom-scrollbar">
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
  stage2Loading, onStartStage2,
}: {
  runs: AleRunSummary[]; selectedRunId: number | null; onSelectRun: (id: number) => void
  selectedRun: AleRun | null; tasks: AleRun['tasks']
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
          <h2 className="mb-3 text-sm font-bold">选择 Stage 1 Run</h2>
          <div className="max-h-[500px] divide-y divide-terminal overflow-y-auto custom-scrollbar">
            {runs.filter((r) => r.status === 'COMPLETED').map((run) => (
              <button
                key={run.runId}
                onClick={() => onSelectRun(run.runId)}
                className={cn('w-full px-3 py-2 text-left hover:bg-primary-50', selectedRunId === run.runId && 'bg-cyan/10')}
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="text-xs truncate">{run.runKey}</span>
                  <span className="text-[10px] text-text-secondary">{run.totalTasks} tasks</span>
                </div>
              </button>
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

          {(selectedRun?.status === 'COMPLETED' && (!selectedRun?.stage2Status || selectedRun.stage2Status === 'FAILED')) && (
            <Button size="sm" onClick={onStartStage2} disabled={stage2Loading} className="w-full">
              {stage2Loading ? <Loading size="sm" /> : <Icon icon="mdi:play-circle" className="mr-1 h-4 w-4" />}
              {selectedRun.stage2Status === 'FAILED' ? '重新测评' : stage2Loading ? '启动中…' : '开始测评'}
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
                <div key={task.id} className="px-3 py-2 space-y-1">
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-xs truncate">{task.taskId}</span>
                    <StatusPill status={task.stage2Status ?? '-'} compact />
                  </div>
                  <div className="flex gap-3 text-[10px] text-text-secondary">
                    <span>得分: {task.stage2Score ?? '-'}</span>
                    <span>耗时: {task.stage2DurationS != null ? `${task.stage2DurationS}s` : '-'}</span>
                  </div>
                  {task.stage2Error && <div className="text-[10px] text-rose-600 truncate">{task.stage2Error}</div>}
                </div>
              ))}
            </div>
          </div>
        )}
      </div>

      {/* agent log placeholder */}
      <div className="rounded-lg border border-terminal bg-white p-4">
        <h2 className="text-sm font-bold mb-3">Agent 日志</h2>
        <div className="text-xs text-text-secondary text-center py-12">
          完成 Stage 2 测评后可查看 agent 执行日志
        </div>
      </div>
    </div>
  )
}

// ── shared components ────────────────────────────────────────────────────────

function StatusPill({ status, compact }: { status: string; compact?: boolean }) {
  const tone = STATUS_TONE[status] ?? 'border-terminal bg-white text-text-secondary'
  return (
    <span className={cn('rounded-full border px-2 py-0.5 text-[10px] font-medium', tone, compact && 'shrink-0')}>
      {status === 'completed' ? '完成' : status === 'failed' ? '失败' : status === 'RUNNING' ? '执行中' : status}
    </span>
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
