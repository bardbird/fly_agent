import { useEffect, useMemo, useState } from 'react'
import { Icon } from '@iconify/react'
import { Link } from 'react-router-dom'
import {
  getTb20Blueprint,
  inspectTb20Dataset,
  runTb20Batch,
  runTb20Single,
} from '@/lib/api'
import type {
  Tb20Blueprint,
  Tb20DependencyStatus,
  Tb20PipelineResponse,
  Tb20Stage,
  Tb20Task,
} from '@/types/tb20'
import { cn } from '@/lib/utils'

const DEFAULT_SOURCE_ROOT = '/Users/liuyifei/Downloads/terminal_bench_2.0_demo_20260528'
const DEFAULT_OUTPUT_ROOT = '/Users/liuyifei/Liu/hub/fly_agent/tb20-output/demo-delivery'
const DEFAULT_HARBOR_ROOT = '/Users/liuyifei/Liu/hub/harbor'
const DEFAULT_TERMINAL_BENCH_ROOT = '/Users/liuyifei/Liu/hub/terminal-bench-main'

type TabKey = 'operate' | 'stages' | 'boundaries' | 'delivery'

const TABS: Array<{ key: TabKey; label: string; icon: string }> = [
  { key: 'operate', label: '真实触发', icon: 'mdi:play-box-outline' },
  { key: 'stages', label: '流水线', icon: 'mdi:timeline-text-outline' },
  { key: 'boundaries', label: '不可自动化', icon: 'mdi:account-check-outline' },
  { key: 'delivery', label: '交付包', icon: 'mdi:package-variant-closed' },
]

export function Tb20PipelinePage() {
  const [activeTab, setActiveTab] = useState<TabKey>('operate')
  const [sourceRoot, setSourceRoot] = useState(DEFAULT_SOURCE_ROOT)
  const [outputRoot, setOutputRoot] = useState(DEFAULT_OUTPUT_ROOT)
  const [harborRoot, setHarborRoot] = useState(DEFAULT_HARBOR_ROOT)
  const [terminalBenchRoot, setTerminalBenchRoot] = useState(DEFAULT_TERMINAL_BENCH_ROOT)
  const [copyTasks, setCopyTasks] = useState(false)
  const [selectedTaskPath, setSelectedTaskPath] = useState('')
  const [blueprint, setBlueprint] = useState<Tb20Blueprint | null>(null)
  const [result, setResult] = useState<Tb20PipelineResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const tasks = Array.isArray(result?.tasks) ? result.tasks : []
  const dependencies = blueprint?.dependencies?.length ? blueprint.dependencies : result?.dependencies ?? []
  const selectedTask = useMemo(
    () => tasks.find((task) => task.relativePath === selectedTaskPath) ?? tasks[0] ?? null,
    [selectedTaskPath, tasks]
  )

  useEffect(() => {
    refreshBlueprint().catch((requestError: unknown) =>
      setError(requestError instanceof Error ? requestError.message : '加载 TB 2.0 蓝图失败')
    )
  }, [])

  async function refreshBlueprint() {
    const next = await getTb20Blueprint({ harborRoot, terminalBenchRoot })
    setBlueprint(next)
  }

  async function handleInspect() {
    await execute(() =>
      inspectTb20Dataset({
        sourceRoot,
        harborRoot,
        terminalBenchRoot,
      })
    )
  }

  async function handleSingle() {
    const taskPath = selectedTaskPath || selectedTask?.relativePath
    if (!taskPath) {
      setError('请先扫描数据集并选择一个 task')
      return
    }
    await execute(() =>
      runTb20Single({
        sourceRoot,
        outputRoot,
        taskPaths: [taskPath],
        copyTasks,
        harborRoot,
        terminalBenchRoot,
      })
    )
  }

  async function handleBatch() {
    await execute(() =>
      runTb20Batch({
        sourceRoot,
        outputRoot,
        copyTasks,
        harborRoot,
        terminalBenchRoot,
      })
    )
  }

  async function execute(action: () => Promise<Tb20PipelineResponse>) {
    setError(null)
    setLoading(true)
    try {
      const next = await action()
      setResult(next)
      if (next.tasks[0] && !selectedTaskPath) {
        setSelectedTaskPath(next.tasks[0].relativePath)
      }
      if (!blueprint) {
        await refreshBlueprint()
      }
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : 'TB 2.0 流水线执行失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="h-screen overflow-y-auto bg-primary custom-scrollbar">
      <div className="sticky top-0 z-20 border-b border-terminal bg-white/90 px-4 py-3 backdrop-blur-lg">
        <div className="mx-auto flex max-w-7xl items-center justify-between gap-3">
          <div className="flex min-w-0 items-center gap-3">
            <div className="flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-lg bg-gradient-to-br from-cyan to-green shadow-lg shadow-cyan/20">
              <Icon icon="mdi:console-line" className="h-6 w-6 text-white" />
            </div>
            <div className="min-w-0">
              <h1 className="truncate text-lg font-bold text-text-primary">Terminal-Bench 2.0 Pipeline</h1>
              <p className="truncate text-xs text-text-secondary">真实样例数据读取、单体触发与批量交付</p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <Link
              to="/swe"
              className="inline-flex h-9 items-center gap-2 rounded-lg border border-terminal bg-white px-3 text-sm font-bold text-text-primary transition-colors hover:bg-tertiary/50"
            >
              <Icon icon="mdi:source-branch-sync" className="h-4 w-4 text-cyan" />
              SWE-Pro
            </Link>
            <Link
              to="/"
              className="inline-flex h-9 items-center gap-2 rounded-lg border border-terminal bg-white px-3 text-sm font-bold text-text-primary transition-colors hover:bg-tertiary/50"
            >
              <Icon icon="mdi:arrow-left" className="h-4 w-4 text-cyan" />
              返回聊天
            </Link>
          </div>
        </div>
      </div>

      <div className="mx-auto max-w-7xl p-4 lg:p-6">
        <HeaderStats result={result} />

        {error && (
          <div className="mb-4 flex items-start gap-2 rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-sm text-rose-700">
            <Icon icon="mdi:alert-circle-outline" className="mt-0.5 h-4 w-4 flex-shrink-0" />
            <span>{error}</span>
          </div>
        )}

        <div className="mb-4 grid gap-2 md:grid-cols-4">
          {TABS.map((tab) => (
            <button
              key={tab.key}
              type="button"
              onClick={() => setActiveTab(tab.key)}
              className={cn(
                'flex min-h-[56px] items-center gap-3 rounded-lg border bg-white px-3 py-2 text-left transition-colors',
                activeTab === tab.key ? 'border-cyan bg-primary-50' : 'border-terminal hover:bg-tertiary/40'
              )}
            >
              <Icon icon={tab.icon} className="h-5 w-5 flex-shrink-0 text-cyan" />
              <span className="text-sm font-bold text-text-primary">{tab.label}</span>
            </button>
          ))}
        </div>

        {activeTab === 'operate' && (
          <div className="grid gap-4 xl:grid-cols-[420px_minmax(0,1fr)]">
            <ControlPanel
              sourceRoot={sourceRoot}
              outputRoot={outputRoot}
              harborRoot={harborRoot}
              terminalBenchRoot={terminalBenchRoot}
              copyTasks={copyTasks}
              loading={loading}
              selectedTaskPath={selectedTaskPath}
              tasks={tasks}
              onSourceRootChange={setSourceRoot}
              onOutputRootChange={setOutputRoot}
              onHarborRootChange={setHarborRoot}
              onTerminalBenchRootChange={setTerminalBenchRoot}
              onCopyTasksChange={setCopyTasks}
              onSelectedTaskPathChange={setSelectedTaskPath}
              onInspect={handleInspect}
              onSingle={handleSingle}
              onBatch={handleBatch}
              onRefreshBlueprint={refreshBlueprint}
            />
            <section className="space-y-4">
              <DependencyPanel dependencies={dependencies} />
              <TaskTable tasks={tasks} selectedTaskPath={selectedTask?.relativePath} onSelect={setSelectedTaskPath} />
              <TaskDetail task={selectedTask} />
            </section>
          </div>
        )}

        {activeTab === 'stages' && <StageBoard stages={blueprint?.stages ?? []} />}

        {activeTab === 'boundaries' && (
          <BoundariesPanel
            boundaries={blueprint?.nonAutomatableBoundaries ?? []}
            controls={blueprint?.aiScaleOutControls ?? []}
          />
        )}

        {activeTab === 'delivery' && (
          <DeliveryPanel
            result={result}
            requiredFiles={blueprint?.requiredTaskFiles ?? []}
            optionalLogs={blueprint?.optionalDeliveryLogs ?? []}
          />
        )}
      </div>
    </div>
  )
}

function HeaderStats({ result }: { result: Tb20PipelineResponse | null }) {
  const summary = result?.summary ?? {}
  return (
    <div className="mb-5 flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
      <div>
        <h2 className="text-xl font-bold text-text-primary">TB 2.0 标准交付包自动化</h2>
        <p className="mt-1 text-sm text-text-secondary">
          从出题、参考解、测试、Harbor 验收到 delivery manifest 的页面触发流水线
        </p>
      </div>
      <div className="grid grid-cols-2 gap-2 text-xs text-text-secondary sm:grid-cols-4">
        <Stat label="任务" value={summary.taskCount ?? 0} />
        <Stat label="合规" value={summary.compliantTaskCount ?? 0} />
        <Stat label="Reward=1" value={summary.rewardOneCount ?? 0} />
        <Stat label="Tests" value={summary.totalTests ?? 0} />
      </div>
    </div>
  )
}

function Stat({ label, value }: { label: string; value: number }) {
  return (
    <span className="rounded-full border border-terminal bg-white px-3 py-1">
      {label} {value.toLocaleString()}
    </span>
  )
}

function ControlPanel({
  sourceRoot,
  outputRoot,
  harborRoot,
  terminalBenchRoot,
  copyTasks,
  loading,
  selectedTaskPath,
  tasks,
  onSourceRootChange,
  onOutputRootChange,
  onHarborRootChange,
  onTerminalBenchRootChange,
  onCopyTasksChange,
  onSelectedTaskPathChange,
  onInspect,
  onSingle,
  onBatch,
  onRefreshBlueprint,
}: {
  sourceRoot: string
  outputRoot: string
  harborRoot: string
  terminalBenchRoot: string
  copyTasks: boolean
  loading: boolean
  selectedTaskPath: string
  tasks: Tb20Task[]
  onSourceRootChange: (value: string) => void
  onOutputRootChange: (value: string) => void
  onHarborRootChange: (value: string) => void
  onTerminalBenchRootChange: (value: string) => void
  onCopyTasksChange: (value: boolean) => void
  onSelectedTaskPathChange: (value: string) => void
  onInspect: () => void
  onSingle: () => void
  onBatch: () => void
  onRefreshBlueprint: () => void
}) {
  return (
    <section className="rounded-lg border border-terminal bg-white p-4">
      <h3 className="mb-3 flex items-center gap-2 text-sm font-bold text-text-primary">
        <Icon icon="mdi:tune-variant" className="h-4 w-4 text-cyan" />
        真实数据路径
      </h3>
      <div className="space-y-3">
        <Field label="TB 2.0 样例目录" value={sourceRoot} onChange={onSourceRootChange} />
        <Field label="交付输出目录" value={outputRoot} onChange={onOutputRootChange} />
        <Field label="Harbor 根目录" value={harborRoot} onChange={onHarborRootChange} placeholder="下载完成后填写" />
        <Field
          label="Terminal-Bench 根目录"
          value={terminalBenchRoot}
          onChange={onTerminalBenchRootChange}
          placeholder="下载完成后填写"
        />
        <label className="flex items-center justify-between rounded-lg border border-terminal bg-primary px-3 py-2 text-sm">
          <span className="font-bold text-text-primary">打包时复制 task 文件</span>
          <input
            type="checkbox"
            checked={copyTasks}
            onChange={(event) => onCopyTasksChange(event.target.checked)}
            className="h-4 w-4 accent-green"
          />
        </label>
        <label className="block">
          <span className="mb-1 block text-xs font-bold text-text-secondary">单体 task</span>
          <select
            value={selectedTaskPath}
            onChange={(event) => onSelectedTaskPathChange(event.target.value)}
            className="h-10 w-full rounded-lg border border-terminal bg-white px-3 text-sm text-text-primary outline-none focus:border-cyan"
          >
            <option value="">扫描后选择</option>
            {tasks.map((task) => (
              <option key={task.relativePath} value={task.relativePath}>
                {task.relativePath}
              </option>
            ))}
          </select>
        </label>
      </div>
      <div className="mt-4 grid gap-2">
        <ActionButton icon="mdi:file-search-outline" label="扫描真实样例" loading={loading} onClick={onInspect} />
        <ActionButton icon="mdi:play-outline" label="单体触发交付" loading={loading} onClick={onSingle} />
        <ActionButton icon="mdi:playlist-play" label="批量触发交付" loading={loading} onClick={onBatch} />
        <button
          type="button"
          onClick={onRefreshBlueprint}
          className="inline-flex h-10 items-center justify-center gap-2 rounded-lg border border-terminal bg-white px-3 text-sm font-bold text-text-primary transition-colors hover:bg-tertiary/40"
        >
          <Icon icon="mdi:refresh" className="h-4 w-4 text-cyan" />
          检查依赖
        </button>
      </div>
    </section>
  )
}

function Field({
  label,
  value,
  placeholder,
  onChange,
}: {
  label: string
  value: string
  placeholder?: string
  onChange: (value: string) => void
}) {
  return (
    <label className="block">
      <span className="mb-1 block text-xs font-bold text-text-secondary">{label}</span>
      <input
        value={value}
        placeholder={placeholder}
        onChange={(event) => onChange(event.target.value)}
        className="h-10 w-full rounded-lg border border-terminal bg-white px-3 text-sm text-text-primary outline-none focus:border-cyan"
      />
    </label>
  )
}

function ActionButton({
  icon,
  label,
  loading,
  onClick,
}: {
  icon: string
  label: string
  loading: boolean
  onClick: () => void
}) {
  return (
    <button
      type="button"
      disabled={loading}
      onClick={onClick}
      className="inline-flex h-10 items-center justify-center gap-2 rounded-lg bg-gradient-to-r from-cyan to-green px-3 text-sm font-bold text-white shadow-sm shadow-cyan/20 transition-opacity disabled:opacity-60"
    >
      <Icon icon={loading ? 'mdi:loading' : icon} className={cn('h-4 w-4', loading && 'animate-spin')} />
      {label}
    </button>
  )
}

function DependencyPanel({ dependencies }: { dependencies: Tb20DependencyStatus[] }) {
  return (
    <section className="rounded-lg border border-terminal bg-white p-4">
      <h3 className="mb-3 flex items-center gap-2 text-sm font-bold text-text-primary">
        <Icon icon="mdi:graph-outline" className="h-4 w-4 text-cyan" />
        核心外部依赖
      </h3>
      <div className="grid gap-2 md:grid-cols-2">
        {dependencies.map((dep) => (
          <div key={dep.name} className="rounded-lg border border-terminal bg-primary p-3">
            <div className="flex items-center justify-between gap-2">
              <span className="text-sm font-bold text-text-primary">{dep.name}</span>
              <span
                className={cn(
                  'rounded-full border px-2 py-0.5 text-xs font-bold',
                  dep.present
                    ? 'border-emerald-200 bg-emerald-50 text-emerald-700'
                    : 'border-amber-200 bg-amber-50 text-amber-700'
                )}
              >
                {dep.status}
              </span>
            </div>
            <p className="mt-1 text-xs text-text-secondary">{dep.role}</p>
            <p className="mt-2 break-all text-xs text-text-muted">{dep.configuredPath || dep.note}</p>
          </div>
        ))}
      </div>
    </section>
  )
}

function TaskTable({
  tasks,
  selectedTaskPath,
  onSelect,
}: {
  tasks: Tb20Task[]
  selectedTaskPath?: string
  onSelect: (path: string) => void
}) {
  return (
    <section className="rounded-lg border border-terminal bg-white p-4">
      <h3 className="mb-3 flex items-center gap-2 text-sm font-bold text-text-primary">
        <Icon icon="mdi:format-list-checks" className="h-4 w-4 text-cyan" />
        真实任务清单
      </h3>
      <div className="max-h-[360px] overflow-auto rounded-lg border border-terminal custom-scrollbar">
        <table className="min-w-full text-left text-xs">
          <thead className="sticky top-0 bg-primary text-text-secondary">
            <tr>
              <th className="px-3 py-2">Task</th>
              <th className="px-3 py-2">难度</th>
              <th className="px-3 py-2">测试</th>
              <th className="px-3 py-2">Reward</th>
              <th className="px-3 py-2">轨迹</th>
            </tr>
          </thead>
          <tbody>
            {tasks.map((task) => (
              <tr
                key={task.relativePath}
                onClick={() => onSelect(task.relativePath)}
                className={cn(
                  'cursor-pointer border-t border-terminal hover:bg-primary-50',
                  selectedTaskPath === task.relativePath && 'bg-primary-50'
                )}
              >
                <td className="px-3 py-2 font-bold text-text-primary">{task.relativePath}</td>
                <td className="px-3 py-2 text-text-secondary">{task.difficulty}</td>
                <td className="px-3 py-2 text-text-secondary">{task.testCount ?? 0}</td>
                <td className="px-3 py-2 text-text-secondary">{task.reward ?? ''}</td>
                <td className="px-3 py-2 text-text-secondary">{task.trajectorySteps ?? 0}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}

function TaskDetail({ task }: { task: Tb20Task | null }) {
  if (!task) {
    return (
      <section className="rounded-lg border border-terminal bg-white p-4 text-sm text-text-secondary">
        扫描后选择一个真实 task 查看结构、参考解和 verifier 数据。
      </section>
    )
  }
  return (
    <section className="rounded-lg border border-terminal bg-white p-4">
      <h3 className="mb-3 text-sm font-bold text-text-primary">{task.relativePath}</h3>
      <div className="grid gap-2 md:grid-cols-3">
        <Metric label="Category" value={task.category || ''} />
        <Metric label="Docker" value={task.dockerImage || ''} />
        <Metric label="Checksum" value={(task.contentChecksum || '').slice(0, 16)} />
        <Metric label="Instruction" value={`${task.lineCounts?.instruction ?? 0} 行`} />
        <Metric label="Solution" value={`${task.lineCounts?.solution ?? 0} 行`} />
        <Metric label="Tests" value={`${task.lineCounts?.tests ?? 0} 行`} />
      </div>
      <div className="mt-3 flex flex-wrap gap-2">
        {(task.tags ?? []).map((tag) => (
          <span key={tag} className="rounded-full border border-terminal bg-primary px-2 py-1 text-xs text-text-secondary">
            {tag}
          </span>
        ))}
      </div>
    </section>
  )
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg border border-terminal bg-primary p-3">
      <div className="text-xs text-text-muted">{label}</div>
      <div className="mt-1 truncate text-sm font-bold text-text-primary">{value}</div>
    </div>
  )
}

function StageBoard({ stages }: { stages: Tb20Stage[] }) {
  return (
    <div className="grid gap-3 lg:grid-cols-2">
      {stages.map((stage, index) => (
        <section key={stage.code} className="rounded-lg border border-terminal bg-white p-4">
          <div className="mb-2 flex items-center justify-between gap-2">
            <h3 className="text-sm font-bold text-text-primary">
              {index + 1}. {stage.name}
            </h3>
            <span className="rounded-full border border-terminal bg-primary px-2 py-1 text-xs text-text-secondary">
              {stage.triggerMode}
            </span>
          </div>
          <p className="text-xs font-bold text-cyan">{stage.automationLevel}</p>
          <div className="mt-3 grid gap-2 text-xs text-text-secondary">
            <p><b>执行者：</b>{stage.owner}</p>
            <p><b>输入：</b>{stage.input}</p>
            <p><b>输出：</b>{stage.output}</p>
            <p><b>门禁：</b>{stage.gate}</p>
          </div>
        </section>
      ))}
    </div>
  )
}

function BoundariesPanel({ boundaries, controls }: { boundaries: string[]; controls: string[] }) {
  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <ListPanel title="不能完全自动化的步骤" icon="mdi:shield-alert-outline" items={boundaries} />
      <ListPanel title="AI 工具规模化替代方式" icon="mdi:robot-outline" items={controls} />
    </div>
  )
}

function ListPanel({ title, icon, items }: { title: string; icon: string; items: string[] }) {
  return (
    <section className="rounded-lg border border-terminal bg-white p-4">
      <h3 className="mb-3 flex items-center gap-2 text-sm font-bold text-text-primary">
        <Icon icon={icon} className="h-4 w-4 text-cyan" />
        {title}
      </h3>
      <div className="space-y-2">
        {items.map((item) => (
          <div key={item} className="rounded-lg border border-terminal bg-primary p-3 text-sm text-text-secondary">
            {item}
          </div>
        ))}
      </div>
    </section>
  )
}

function DeliveryPanel({
  result,
  requiredFiles,
  optionalLogs,
}: {
  result: Tb20PipelineResponse | null
  requiredFiles: string[]
  optionalLogs: string[]
}) {
  return (
    <div className="grid gap-4 xl:grid-cols-[420px_minmax(0,1fr)]">
      <section className="rounded-lg border border-terminal bg-white p-4">
        <h3 className="mb-3 flex items-center gap-2 text-sm font-bold text-text-primary">
          <Icon icon="mdi:file-document-check-outline" className="h-4 w-4 text-cyan" />
          标准 TB 2.0 结构
        </h3>
        <FileList title="必备核心文件" files={requiredFiles} />
        <FileList title="增强交付日志" files={optionalLogs} />
      </section>
      <section className="rounded-lg border border-terminal bg-white p-4">
        <h3 className="mb-3 flex items-center gap-2 text-sm font-bold text-text-primary">
          <Icon icon="mdi:package-variant" className="h-4 w-4 text-cyan" />
          本次交付输出
        </h3>
        <div className="space-y-3 text-sm text-text-secondary">
          <PathRow label="Manifest" value={result?.manifestPath} />
          <PathRow label="Index" value={result?.deliveryIndexPath} />
          <PathRow label="Output" value={result?.outputRoot} />
        </div>
        <pre className="mt-4 max-h-[420px] overflow-auto rounded-lg border border-terminal bg-primary p-3 text-xs text-text-secondary custom-scrollbar">
          {JSON.stringify(result?.summary ?? {}, null, 2)}
        </pre>
      </section>
    </div>
  )
}

function FileList({ title, files }: { title: string; files: string[] }) {
  return (
    <div className="mb-4">
      <div className="mb-2 text-xs font-bold text-text-secondary">{title}</div>
      <div className="space-y-1">
        {files.map((file) => (
          <div key={file} className="rounded border border-terminal bg-primary px-2 py-1 text-xs text-text-secondary">
            {file}
          </div>
        ))}
      </div>
    </div>
  )
}

function PathRow({ label, value }: { label: string; value?: string }) {
  return (
    <div className="rounded-lg border border-terminal bg-primary p-3">
      <div className="text-xs font-bold text-text-muted">{label}</div>
      <div className="mt-1 break-all text-sm text-text-primary">{value || '尚未生成'}</div>
    </div>
  )
}
