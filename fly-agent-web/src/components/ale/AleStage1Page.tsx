import { useEffect, useMemo, useState } from 'react'
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
} from '@/lib/api'
import type { AleOptionsResponse, AleRun, AleRunRequest, AleRunStatus, AleRunSummary } from '@/types/ale'

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
  codexModel: 'gpt-5',
}

const FALLBACK_DOMAINS = [
  { value: 'computing_math', label: 'Computing Math' },
  { value: 'business_finance', label: 'Business Finance' },
  { value: 'engineering', label: 'Engineering' },
  { value: 'health_medicine', label: 'Health Medicine' },
  { value: 'life_sciences', label: 'Life Sciences' },
  { value: 'physical_sciences', label: 'Physical Sciences' },
  { value: 'visual_media', label: 'Visual Media' },
]

const FALLBACK_DIFFICULTIES = [
  { value: 'easy', label: 'Easy' },
  { value: 'medium', label: 'Medium' },
  { value: 'hard', label: 'Hard' },
]

const FALLBACK_MODELS = [
  { value: 'gpt-5', label: 'gpt-5' },
  { value: 'gpt-5-mini', label: 'gpt-5-mini' },
  { value: 'gpt-5-codex', label: 'gpt-5-codex' },
]

const TARGET_COUNT_OPTIONS = [
  { value: 1, label: '1' },
  { value: 4, label: '4' },
  { value: 8, label: '8' },
  { value: 12, label: '12' },
]

export function AleStage1Page() {
  const [options, setOptions] = useState<AleOptionsResponse | null>(null)
  const [request, setRequest] = useState<AleRunRequest>(emptyRequest)
  const [runs, setRuns] = useState<AleRunSummary[]>([])
  const [selectedRunId, setSelectedRunId] = useState<number | null>(null)
  const [selectedRun, setSelectedRun] = useState<AleRun | null>(null)
  const [activePanel, setActivePanel] = useState<'progress' | 'tasks' | 'logs'>('progress')
  const [selectedTaskId, setSelectedTaskId] = useState<number | null>(null)
  const [statusFilter, setStatusFilter] = useState<AleRunStatus | 'ALL'>('ALL')
  const [domainFilter, setDomainFilter] = useState<string | null>(null)
  const [logLines, setLogLines] = useState<string[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const selectedRunSummary = useMemo(
    () => runs.find((run) => run.runId === selectedRunId) ?? null,
    [runs, selectedRunId]
  )

  useEffect(() => {
    void loadOptions()
    void refreshRuns()
  }, [])

  useEffect(() => {
    if (!selectedRunId) return
    setActivePanel('progress')
    setSelectedTaskId(null)
    void refreshSelectedRun(selectedRunId)
    void refreshRunLog(selectedRunId)
    const timer = window.setInterval(() => {
      void refreshSelectedRun(selectedRunId)
      void refreshRunLog(selectedRunId)
      void refreshRuns(false)
    }, 3000)
    return () => window.clearInterval(timer)
  }, [selectedRunId])

  async function loadOptions() {
    try {
      const next = await getAleOptions()
      setOptions(next)
      setRequest((prev) => ({
        ...prev,
        domain: next.domains.some((option) => option.value === prev.domain)
          ? prev.domain
          : next.domains[0]?.value ?? prev.domain,
        difficulty: next.difficulties.some((option) => option.value === prev.difficulty)
          ? prev.difficulty
          : next.difficulties[1]?.value ?? next.difficulties[0]?.value ?? prev.difficulty,
        codexModel: next.codexModels.some((option) => option.value === prev.codexModel)
          ? prev.codexModel
          : next.codexModels[0]?.value ?? prev.codexModel,
      }))
    } catch (err) {
      setError(err instanceof Error ? `后端未连接：${err.message}` : '后端未连接')
    }
  }

  async function refreshRuns(selectLatest = true) {
    const next = await listAleRuns()
    setRuns(next)
    if (!selectLatest) return
    if (!selectedRunId && next[0]) {
      setSelectedRunId(next[0].runId)
    }
  }

  async function refreshSelectedRun(id: number) {
    const next = await getAleRun(id)
    setSelectedRun(next)
    setSelectedTaskId((current) => current ?? next.tasks[0]?.id ?? null)
  }

  async function refreshRunLog(id: number) {
    const next = await getAleRunLog(id, 400)
    setLogLines(next)
  }

  async function handleStart() {
    setLoading(true)
    setError(null)
    try {
      const next = await startAleRun(request)
      await refreshRuns(false)
      setSelectedRunId(next.id)
      setSelectedRun(next)
      setLogLines([])
    } catch (err) {
      setError(err instanceof Error ? err.message : '启动失败')
    } finally {
      setLoading(false)
    }
  }

  const activeRun = selectedRun ?? (selectedRunId ? runs.find((run) => run.runId === selectedRunId) ?? null : null)
  const tasks = selectedRun?.tasks ?? []
  const selectedTask = tasks.find((task) => task.id === selectedTaskId) ?? tasks[0] ?? null
  const tasksByDomain = tasks.reduce<Record<string, typeof tasks>>((acc, task) => {
    const key = task.domain || 'unknown'
    acc[key] = [...(acc[key] ?? []), task]
    return acc
  }, {})
  const domainOptions = options?.domains.length ? options.domains : FALLBACK_DOMAINS
  const difficultyOptions = options?.difficulties.length ? options.difficulties : FALLBACK_DIFFICULTIES
  const modelOptions = options?.codexModels.length ? options.codexModels : FALLBACK_MODELS
  const runDomainStats = runs.reduce<Record<string, number>>((acc, run) => {
    acc[run.domain] = (acc[run.domain] ?? 0) + 1
    return acc
  }, {})
  const filteredRuns = runs.filter((run) => {
    if (statusFilter !== 'ALL' && run.status !== statusFilter) return false
    if (domainFilter && run.domain !== domainFilter) return false
    return true
  })

  return (
    <div className="min-h-screen bg-primary text-text-primary">
      <div className="border-b border-terminal bg-white/85 backdrop-blur">
        <div className="mx-auto flex max-w-[1600px] flex-col gap-4 px-4 py-4">
          <div className="flex items-center justify-between gap-4">
            <div>
              <h1 className="text-xl font-bold">ALE Stage 1</h1>
              <p className="text-xs text-text-secondary">一键发起 Codex CLI skill 调用，查看生成进度、任务详情和日志。</p>
            </div>
            <Button onClick={handleStart} disabled={loading} className="shrink-0">
              {loading ? <Loading size="sm" /> : <Icon icon="mdi:play" className="mr-2 h-4 w-4" />}
              启动生成
            </Button>
          </div>

          <div className="grid gap-3 md:grid-cols-4">
            <SelectField label="领域" value={request.domain} onChange={(value) => setRequest({ ...request, domain: value })} options={domainOptions} />
            <SelectField label="难度" value={request.difficulty} onChange={(value) => setRequest({ ...request, difficulty: value })} options={difficultyOptions} />
            <SelectNumberField
              label="Task 数"
              value={request.targetCount}
              onChange={(value) => setRequest({ ...request, targetCount: value })}
              options={TARGET_COUNT_OPTIONS}
            />
            <SelectField label="Codex 模型" value={request.codexModel} onChange={(value) => setRequest({ ...request, codexModel: value })} options={modelOptions} />
          </div>

          {error ? <div className="text-sm text-error">{error}</div> : null}
        </div>
      </div>

      <div className="mx-auto grid max-w-[1600px] gap-4 px-4 py-4 xl:grid-cols-[1.2fr_1fr]">
        <div className="space-y-4">
          <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
            <StatButton active={statusFilter === 'ALL'} label="总 run" value={runs.length} onClick={() => setStatusFilter('ALL')} />
            <StatButton active={statusFilter === 'RUNNING'} label="进行中" value={runs.filter((run) => run.status === 'RUNNING').length} onClick={() => setStatusFilter('RUNNING')} />
            <StatButton active={statusFilter === 'COMPLETED'} label="完成" value={runs.filter((run) => run.status === 'COMPLETED').length} onClick={() => setStatusFilter('COMPLETED')} />
            <StatButton active={statusFilter === 'FAILED'} label="失败" value={runs.filter((run) => run.status === 'FAILED').length} onClick={() => setStatusFilter('FAILED')} />
          </div>

          <div className="terminal">
            <div className="flex items-center justify-between border-b border-terminal px-4 py-3">
              <div>
                <h2 className="font-bold">运行概览</h2>
                <div className="text-xs text-text-secondary">
                  {selectedRunSummary ? `${selectedRunSummary.runKey} · ${selectedRunSummary.status}` : '未选择 run'}
                </div>
              </div>
              <button
                onClick={() => {
                  setStatusFilter('ALL')
                  setDomainFilter(null)
                }}
                className="flex items-center gap-1 text-xs text-cyan"
              >
                <Icon icon="mdi:filter-off-outline" className="h-4 w-4" />
                清除筛选
              </button>
            </div>
            <div className="flex flex-wrap gap-2 border-b border-terminal px-4 py-3">
              {Object.entries(runDomainStats).map(([domain, count]) => (
                <button
                  key={domain}
                  onClick={() => setDomainFilter(domainFilter === domain ? null : domain)}
                  className={cn(
                    'rounded-full border px-3 py-1 text-xs transition-colors',
                    domainFilter === domain ? 'border-cyan bg-cyan text-white' : 'border-terminal bg-white text-text-secondary hover:bg-primary-50'
                  )}
                >
                  {domain} · {count}
                </button>
              ))}
              {Object.keys(runDomainStats).length === 0 ? <span className="text-xs text-text-secondary">暂无领域数据</span> : null}
            </div>
            <div className="divide-y divide-terminal">
              {filteredRuns.map((run) => (
                <button
                  key={run.runId}
                  onClick={() => setSelectedRunId(run.runId)}
                  className={cn(
                    'w-full px-4 py-3 text-left transition-colors hover:bg-primary-50',
                    selectedRunId === run.runId ? 'bg-primary-50' : ''
                  )}
                >
                  <div className="flex items-center justify-between gap-3">
                    <div className="min-w-0">
                      <div className="truncate font-medium">{run.runKey}</div>
                      <div className="text-xs text-text-secondary">
                        {run.domain} · {run.scenario} · {run.difficulty}
                      </div>
                    </div>
                    <div className="text-right text-xs text-text-secondary">
                      <div>{run.status}</div>
                      <div>{run.progressPercent}%</div>
                    </div>
                  </div>
                </button>
              ))}
              {runs.length === 0 ? <div className="px-4 py-6 text-sm text-text-secondary">暂无运行记录</div> : null}
              {runs.length > 0 && filteredRuns.length === 0 ? <div className="px-4 py-6 text-sm text-text-secondary">当前筛选无结果</div> : null}
            </div>
          </div>
        </div>

        <div className="space-y-4">
          <div className="terminal">
            <div className="flex items-center justify-between border-b border-terminal px-4 py-3">
              <h2 className="font-bold">{activeRun ? activeRun.runKey : '运行工作台'}</h2>
              <div className="flex rounded-lg border border-terminal bg-white p-1">
                <PanelTab active={activePanel === 'progress'} icon="mdi:chart-line" label="进度" onClick={() => setActivePanel('progress')} />
                <PanelTab active={activePanel === 'tasks'} icon="mdi:format-list-checks" label="Task" onClick={() => setActivePanel('tasks')} />
                <PanelTab active={activePanel === 'logs'} icon="mdi:console-line" label="日志" onClick={() => setActivePanel('logs')} />
              </div>
            </div>
            {activePanel === 'progress' ? (
              <RunProgress run={activeRun} selectedTask={selectedTask} />
            ) : null}
            {activePanel === 'tasks' ? (
              <TaskExplorer
                tasksByDomain={tasksByDomain}
                selectedTask={selectedTask}
                onSelect={(taskId) => setSelectedTaskId(taskId)}
              />
            ) : null}
            {activePanel === 'logs' ? (
              <RunLogs
                lines={logLines}
                onRefresh={() => selectedRunId && refreshRunLog(selectedRunId)}
              />
            ) : null}
          </div>
        </div>
      </div>
    </div>
  )
}

function PanelTab({
  active,
  icon,
  label,
  onClick,
}: {
  active: boolean
  icon: string
  label: string
  onClick: () => void
}) {
  return (
    <button
      onClick={onClick}
      className={cn(
        'flex h-8 items-center gap-1 rounded-md px-3 text-xs transition-colors',
        active ? 'bg-cyan text-white' : 'text-text-secondary hover:bg-primary-50'
      )}
    >
      <Icon icon={icon} className="h-4 w-4" />
      {label}
    </button>
  )
}

function RunProgress({ run, selectedTask }: { run: AleRun | AleRunSummary | null; selectedTask: AleRun['tasks'][number] | null }) {
  if (!run) {
    return <div className="px-4 py-6 text-sm text-text-secondary">选择一个 run 查看进度</div>
  }
  return (
    <div className="space-y-4 p-4">
      <div className="flex items-center justify-between text-sm">
        <span>整体进度</span>
        <StatusPill status={run.status} />
      </div>
      <div className="h-2 overflow-hidden rounded-full bg-tertiary">
        <div className="h-full rounded-full bg-cyan" style={{ width: `${run.progressPercent}%` }} />
      </div>
      <div className="grid gap-2 text-sm md:grid-cols-2">
        <InfoLine label="进度" value={`${run.progressPercent}%`} />
        <InfoLine label="任务总数" value={run.totalTasks} />
        <InfoLine label="完成" value={run.completedTasks} />
        <InfoLine label="失败" value={run.failedTasks} />
        <InfoLine label="阻塞" value={run.blockedTasks} />
        <InfoLine label="输出目录" value={run.outputRoot ?? '-'} />
      </div>
      <div className="rounded-lg border border-terminal p-3">
        <div className="mb-2 text-xs text-text-secondary">当前 Task</div>
        {selectedTask ? (
          <div className="space-y-2 text-sm">
            <div className="flex items-center justify-between gap-3">
              <span className="truncate font-medium">{selectedTask.taskId}</span>
              <StatusPill status={selectedTask.status} />
            </div>
            <div className="grid gap-2 md:grid-cols-2">
              <InfoLine label="目录" value={selectedTask.taskDir ?? '-'} />
              <InfoLine label="证据" value={selectedTask.evidencePath ?? '-'} />
            </div>
          </div>
        ) : (
          <div className="text-sm text-text-secondary">暂无 task</div>
        )}
      </div>
      {run.errorMessage ? <div className="text-sm text-error">{run.errorMessage}</div> : null}
    </div>
  )
}

function TaskExplorer({
  tasksByDomain,
  selectedTask,
  onSelect,
}: {
  tasksByDomain: Record<string, AleRun['tasks']>
  selectedTask: AleRun['tasks'][number] | null
  onSelect: (taskId: number) => void
}) {
  const domains = Object.keys(tasksByDomain)
  if (domains.length === 0) {
    return <div className="px-4 py-6 text-sm text-text-secondary">暂无 task 详情</div>
  }
  return (
    <div className="grid min-h-[420px] md:grid-cols-[0.9fr_1.1fr]">
      <div className="max-h-[520px] overflow-y-auto border-b border-terminal md:border-b-0 md:border-r custom-scrollbar">
        {domains.map((domain) => (
          <div key={domain} className="border-b border-terminal">
            <div className="bg-primary-50 px-4 py-2 text-xs font-medium text-text-secondary">{domain}</div>
            {tasksByDomain[domain].map((task) => (
              <button
                key={task.id}
                onClick={() => onSelect(task.id)}
                className={cn(
                  'w-full px-4 py-3 text-left transition-colors hover:bg-primary-50',
                  selectedTask?.id === task.id ? 'bg-cyan/10' : ''
                )}
              >
                <div className="flex items-center justify-between gap-3">
                  <div className="min-w-0">
                    <div className="truncate text-sm font-medium">{task.taskId}</div>
                    <div className="truncate text-xs text-text-secondary">{task.title}</div>
                  </div>
                  <StatusPill status={task.status} compact />
                </div>
              </button>
            ))}
          </div>
        ))}
      </div>
      <div className="p-4">
        {selectedTask ? (
          <div className="space-y-3">
            <div className="flex items-center justify-between gap-3">
              <div className="min-w-0">
                <div className="truncate font-medium">{selectedTask.taskId}</div>
                <div className="text-xs text-text-secondary">{selectedTask.title}</div>
              </div>
              <StatusPill status={selectedTask.status} />
            </div>
            <div className="grid gap-2 text-sm md:grid-cols-2">
              <InfoLine label="领域" value={selectedTask.domain} />
              <InfoLine label="学科" value={selectedTask.discipline ?? '-'} />
              <InfoLine label="场景" value={selectedTask.scenario ?? '-'} />
              <InfoLine label="难度" value={selectedTask.difficulty ?? '-'} />
              <InfoLine label="目录" value={selectedTask.taskDir ?? '-'} />
              <InfoLine label="证据" value={selectedTask.evidencePath ?? '-'} />
            </div>
            {selectedTask.summary ? <div className="text-sm text-text-secondary">{selectedTask.summary}</div> : null}
            {selectedTask.errorMessage ? <div className="text-sm text-error">{selectedTask.errorMessage}</div> : null}
          </div>
        ) : (
          <div className="text-sm text-text-secondary">选择一个 task 查看详情</div>
        )}
      </div>
    </div>
  )
}

function RunLogs({ lines, onRefresh }: { lines: string[]; onRefresh: () => void }) {
  return (
    <div>
      <div className="flex justify-end border-b border-terminal px-4 py-2">
        <button onClick={onRefresh} className="flex items-center gap-1 text-xs text-cyan">
          <Icon icon="mdi:refresh" className="h-4 w-4" />
          刷新
        </button>
      </div>
      <pre className="max-h-[560px] overflow-y-auto whitespace-pre-wrap px-4 py-3 text-xs leading-6 text-text-primary custom-scrollbar">
        {lines.length > 0 ? lines.join('\n') : '暂无日志'}
      </pre>
    </div>
  )
}

function StatusPill({ status, compact = false }: { status: string; compact?: boolean }) {
  const tone =
    status === 'COMPLETED' ? 'border-success/40 bg-success/10 text-success'
      : status === 'FAILED' ? 'border-error/40 bg-error/10 text-error'
        : status === 'RUNNING' ? 'border-cyan/40 bg-cyan/10 text-cyan'
          : 'border-terminal bg-white text-text-secondary'
  return (
    <span className={cn('rounded-full border px-2 py-0.5 text-xs', tone, compact ? 'shrink-0' : '')}>
      {status}
    </span>
  )
}

function SelectField({
  label,
  value,
  onChange,
  options,
}: {
  label: string
  value: string
  onChange: (value: string) => void
  options: Array<{ value: string; label: string }>
}) {
  return (
    <label className="flex flex-col gap-1 text-xs">
      <span className="text-text-secondary">{label}</span>
      <select
        className="h-10 rounded-lg border border-terminal bg-white px-3 text-sm outline-none focus:border-cyan"
        value={value}
        onChange={(e) => onChange(e.target.value)}
      >
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </label>
  )
}

function SelectNumberField({
  label,
  value,
  onChange,
  options,
}: {
  label: string
  value: number
  onChange: (value: number) => void
  options: Array<{ value: number; label: string }>
}) {
  return (
    <label className="flex flex-col gap-1 text-xs">
      <span className="text-text-secondary">{label}</span>
      <select
        className="h-10 rounded-lg border border-terminal bg-white px-3 text-sm outline-none focus:border-cyan"
        value={value}
        onChange={(e) => onChange(Number(e.target.value))}
      >
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </label>
  )
}

function StatButton({
  active,
  label,
  value,
  onClick,
}: {
  active: boolean
  label: string
  value: number
  onClick: () => void
}) {
  return (
    <button
      onClick={onClick}
      className={cn(
        'terminal px-4 py-3 text-left transition-colors hover:bg-primary-50',
        active ? 'border-cyan bg-cyan/10' : ''
      )}
    >
      <div className="text-xs text-text-secondary">{label}</div>
      <div className="mt-1 text-2xl font-bold">{value}</div>
    </button>
  )
}

function InfoLine({ label, value }: { label: string; value: string | number }) {
  return (
    <div className="rounded-md border border-terminal px-3 py-2">
      <div className="text-xs text-text-secondary">{label}</div>
      <div className="truncate text-sm">{String(value)}</div>
    </div>
  )
}
