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
import type { AleOptionsResponse, AleRun, AleRunRequest, AleRunSummary } from '@/types/ale'

const emptyRequest: AleRunRequest = {
  domain: '',
  discipline: '',
  scenario: '',
  difficulty: '',
  inputMode: '',
  outputMode: '',
  verificationMode: '',
  referenceStrategy: '',
  targetCount: 8,
  codexModel: 'gpt-5',
}

const TARGET_COUNT_OPTIONS = [
  { value: 4, label: '4' },
  { value: 8, label: '8' },
  { value: 12, label: '12' },
  { value: 16, label: '16' },
]

export function AleStage1Page() {
  const [options, setOptions] = useState<AleOptionsResponse | null>(null)
  const [request, setRequest] = useState<AleRunRequest>(emptyRequest)
  const [runs, setRuns] = useState<AleRunSummary[]>([])
  const [selectedRunId, setSelectedRunId] = useState<number | null>(null)
  const [selectedRun, setSelectedRun] = useState<AleRun | null>(null)
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
    const next = await getAleOptions()
    setOptions(next)
    setRequest((prev) => ({
      ...prev,
      domain: next.domains[0]?.value ?? prev.domain,
      discipline: next.disciplines[0]?.value ?? prev.discipline,
      scenario: next.scenarios[0]?.value ?? prev.scenario,
      difficulty: next.difficulties[1]?.value ?? next.difficulties[0]?.value ?? prev.difficulty,
      inputMode: next.inputModes[0]?.value ?? prev.inputMode,
      outputMode: next.outputModes[0]?.value ?? prev.outputMode,
      verificationMode: next.verificationModes[0]?.value ?? prev.verificationMode,
      referenceStrategy: next.referenceStrategies[0]?.value ?? prev.referenceStrategy,
      codexModel: next.codexModels[0]?.value ?? prev.codexModel,
    }))
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
  const domainStats = activeRun?.domainStats ?? {}

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

          <div className="grid gap-3 md:grid-cols-3 xl:grid-cols-5">
            <SelectField label="领域" value={request.domain} onChange={(value) => setRequest({ ...request, domain: value })} options={options?.domains ?? []} />
            <SelectField label="学科" value={request.discipline} onChange={(value) => setRequest({ ...request, discipline: value })} options={options?.disciplines ?? []} />
            <SelectField label="场景" value={request.scenario} onChange={(value) => setRequest({ ...request, scenario: value })} options={options?.scenarios ?? []} />
            <SelectField label="难度" value={request.difficulty} onChange={(value) => setRequest({ ...request, difficulty: value })} options={options?.difficulties ?? []} />
            <SelectField label="Codex 模型" value={request.codexModel} onChange={(value) => setRequest({ ...request, codexModel: value })} options={options?.codexModels ?? []} />
            <SelectField label="输入形态" value={request.inputMode} onChange={(value) => setRequest({ ...request, inputMode: value })} options={options?.inputModes ?? []} />
            <SelectField label="输出形态" value={request.outputMode} onChange={(value) => setRequest({ ...request, outputMode: value })} options={options?.outputModes ?? []} />
            <SelectField label="验证方式" value={request.verificationMode} onChange={(value) => setRequest({ ...request, verificationMode: value })} options={options?.verificationModes ?? []} />
            <SelectField label="reference 策略" value={request.referenceStrategy} onChange={(value) => setRequest({ ...request, referenceStrategy: value })} options={options?.referenceStrategies ?? []} />
            <SelectNumberField
              label="目标 task 数"
              value={request.targetCount}
              onChange={(value) => setRequest({ ...request, targetCount: value })}
              options={TARGET_COUNT_OPTIONS}
            />
          </div>

          {error ? <div className="text-sm text-error">{error}</div> : null}
        </div>
      </div>

      <div className="mx-auto grid max-w-[1600px] gap-4 px-4 py-4 xl:grid-cols-[1.2fr_1fr]">
        <div className="space-y-4">
          <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
            <Stat label="总 run" value={runs.length} />
            <Stat label="进行中" value={runs.filter((run) => run.status === 'RUNNING').length} />
            <Stat label="完成" value={runs.filter((run) => run.status === 'COMPLETED').length} />
            <Stat label="失败" value={runs.filter((run) => run.status === 'FAILED').length} />
          </div>

          <div className="terminal">
            <div className="flex items-center justify-between border-b border-terminal px-4 py-3">
              <h2 className="font-bold">运行概览</h2>
              <div className="text-xs text-text-secondary">
                {selectedRunSummary ? `${selectedRunSummary.runKey} · ${selectedRunSummary.status}` : '未选择 run'}
              </div>
            </div>
            <div className="divide-y divide-terminal">
              {runs.map((run) => (
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
            </div>
          </div>

          <div className="terminal">
            <div className="border-b border-terminal px-4 py-3">
              <h2 className="font-bold">领域统计</h2>
            </div>
            <div className="grid gap-3 p-4 md:grid-cols-2 xl:grid-cols-3">
              {Object.entries(domainStats).map(([domain, count]) => (
                <div key={domain} className="rounded-lg border border-terminal px-3 py-3">
                  <div className="text-sm font-medium">{domain}</div>
                  <div className="mt-1 text-2xl font-bold">{count}</div>
                </div>
              ))}
              {Object.keys(domainStats).length === 0 ? <div className="text-sm text-text-secondary">暂无统计数据</div> : null}
            </div>
          </div>
        </div>

        <div className="space-y-4">
          <div className="terminal">
            <div className="border-b border-terminal px-4 py-3">
              <h2 className="font-bold">单体任务进度</h2>
            </div>
            <div className="p-4">
              {activeRun ? (
                <div className="space-y-3">
                  <div className="flex items-center justify-between text-sm">
                    <span>进度</span>
                    <span>{activeRun.progressPercent}%</span>
                  </div>
                  <div className="h-2 overflow-hidden rounded-full bg-tertiary">
                    <div className="h-full rounded-full bg-cyan" style={{ width: `${activeRun.progressPercent}%` }} />
                  </div>
                  <div className="grid gap-2 text-sm md:grid-cols-2">
                    <InfoLine label="任务总数" value={activeRun.totalTasks} />
                    <InfoLine label="完成" value={activeRun.completedTasks} />
                    <InfoLine label="失败" value={activeRun.failedTasks} />
                    <InfoLine label="阻塞" value={activeRun.blockedTasks} />
                    <InfoLine label="状态" value={activeRun.status} />
                    <InfoLine label="输出目录" value={activeRun.outputRoot ?? '-'} />
                  </div>
                  {activeRun.errorMessage ? <div className="text-sm text-error">{activeRun.errorMessage}</div> : null}
                </div>
              ) : (
                <div className="text-sm text-text-secondary">选择一个 run 查看进度</div>
              )}
            </div>
          </div>

          <div className="terminal">
            <div className="border-b border-terminal px-4 py-3">
              <h2 className="font-bold">Task 列表</h2>
            </div>
            <div className="max-h-[420px] overflow-y-auto custom-scrollbar">
              {(selectedRun?.tasks ?? []).map((task) => (
                <details key={task.id} className="border-b border-terminal px-4 py-3">
                  <summary className="cursor-pointer list-none">
                    <div className="flex items-center justify-between gap-3">
                      <div className="min-w-0">
                        <div className="truncate font-medium">{task.taskId}</div>
                        <div className="text-xs text-text-secondary">{task.title}</div>
                      </div>
                      <div className="text-right text-xs text-text-secondary">
                        <div>{task.status}</div>
                        <div>{task.score ?? '-'}</div>
                      </div>
                    </div>
                  </summary>
                  <div className="mt-3 grid gap-2 text-sm md:grid-cols-2">
                    <InfoLine label="领域" value={task.domain} />
                    <InfoLine label="学科" value={task.discipline ?? '-'} />
                    <InfoLine label="场景" value={task.scenario ?? '-'} />
                    <InfoLine label="难度" value={task.difficulty ?? '-'} />
                    <InfoLine label="目录" value={task.taskDir ?? '-'} />
                    <InfoLine label="证据" value={task.evidencePath ?? '-'} />
                  </div>
                  {task.summary ? <div className="mt-3 text-sm text-text-secondary">{task.summary}</div> : null}
                  {task.errorMessage ? <div className="mt-2 text-sm text-error">{task.errorMessage}</div> : null}
                </details>
              ))}
              {!selectedRun?.tasks?.length ? <div className="px-4 py-6 text-sm text-text-secondary">暂无 task 详情</div> : null}
            </div>
          </div>

          <div className="terminal">
            <div className="flex items-center justify-between border-b border-terminal px-4 py-3">
              <h2 className="font-bold">Codex 日志</h2>
              <button
                onClick={() => selectedRunId && refreshRunLog(selectedRunId)}
                className="text-xs text-cyan"
              >
                刷新
              </button>
            </div>
            <pre className="max-h-[380px] overflow-y-auto whitespace-pre-wrap px-4 py-3 text-xs leading-6 text-text-primary custom-scrollbar">
              {logLines.length > 0 ? logLines.join('\n') : '暂无日志'}
            </pre>
          </div>
        </div>
      </div>
    </div>
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

function Stat({ label, value }: { label: string; value: number }) {
  return (
    <div className="terminal px-4 py-3">
      <div className="text-xs text-text-secondary">{label}</div>
      <div className="mt-1 text-2xl font-bold">{value}</div>
    </div>
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
