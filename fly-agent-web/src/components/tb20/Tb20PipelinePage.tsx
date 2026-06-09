import { useEffect, useMemo, useState } from 'react'
import { Icon } from '@iconify/react'
import { Link } from 'react-router-dom'
import {
  getTb20Config,
  getTb20Run,
  getTb20RunLog,
  listTb20Runs,
  saveTb20Config,
  startTb20DatasetRun,
  startTb20ExecutionRun,
} from '@/lib/api'
import type { Tb20ConfigResponse, Tb20Domain, Tb20Run } from '@/types/tb20'
import { cn } from '@/lib/utils'

type WorkspaceTab = 'dataset' | 'execution'
type ConfigScope = 'dataset-production' | 'batch-execution-delivery'

const DOMAINS: Tb20Domain[] = [
  'software-engineering',
  'system-administration',
  'security',
  'data-science',
  'scientific-computing',
  'file-operations',
  'web-network-services',
  'distributed-systems',
  'performance-optimization',
  'algorithms-and-formats',
]

const CHANNELS: Record<Tb20Domain, string[]> = {
  'software-engineering': ['github-pr-mining', 'software-heritage', 'libraries-io'],
  'system-administration': ['debian-source', 'linux-man-pages', 'systemd-repo', 'kubernetes-repo'],
  security: ['nvd-api', 'cve-cvelist', 'cwe', 'exploit-db', 'vulhub'],
  'data-science': ['uci-ml', 'openml', 'data-gov', 'common-crawl-discovery'],
  'scientific-computing': ['netlib', 'nist-strd', 'suitesparse', 'scipy-numpy-tests'],
  'file-operations': ['coreutils', 'libarchive', 'rsync', 'debian-archive-docs', 'posix-spec'],
  'web-network-services': ['rfc-editor', 'iana-registries', 'w3c-whatwg', 'curl-tests', 'apache-nginx-docs'],
  'distributed-systems': ['cncf-landscape', 'kubernetes-repo', 'etcd-repo', 'prometheus-repo', 'jepsen-analyses'],
  'performance-optimization': ['llvm-test-suite', 'google-benchmark', 'phoronix-test-suite', 'open-polybench'],
  'algorithms-and-formats': ['rfc-iana', 'netlib', 'rosetta-code', 'cp-algorithms', 'format-spec-repos'],
}

export function Tb20PipelinePage() {
  const [tab, setTab] = useState<WorkspaceTab>('dataset')
  const [datasetConfig, setDatasetConfig] = useState<Record<string, string>>({})
  const [executionConfig, setExecutionConfig] = useState<Record<string, string>>({})
  const [domain, setDomain] = useState<Tb20Domain>('software-engineering')
  const [sourceChannel, setSourceChannel] = useState('github-pr-mining')
  const [brief, setBrief] = useState('')
  const [sourceRoot, setSourceRoot] = useState('')
  const [taskPaths, setTaskPaths] = useState('')
  const [activeRun, setActiveRun] = useState<Tb20Run | null>(null)
  const [runs, setRuns] = useState<Tb20Run[]>([])
  const [log, setLog] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const channels = CHANNELS[domain]

  useEffect(() => {
    void bootstrap()
  }, [])

  useEffect(() => {
    if (!channels.includes(sourceChannel)) {
      setSourceChannel(channels[0])
    }
  }, [channels, sourceChannel])

  async function bootstrap() {
    setError(null)
    try {
      const [dataset, execution, nextRuns] = await Promise.all([
        getTb20Config({ scope: 'dataset-production' }),
        getTb20Config({ scope: 'batch-execution-delivery' }),
        listTb20Runs(),
      ])
      setDatasetConfig(stringValues(dataset))
      setExecutionConfig(stringValues(execution))
      setRuns(nextRuns)
      if (dataset.values.defaultDomain && DOMAINS.includes(dataset.values.defaultDomain as Tb20Domain)) {
        setDomain(dataset.values.defaultDomain as Tb20Domain)
      }
      if (dataset.values.defaultSourceChannel) {
        setSourceChannel(String(dataset.values.defaultSourceChannel))
      }
      if (execution.values.sourceRoot) {
        setSourceRoot(String(execution.values.sourceRoot))
      }
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : '加载 TB20 配置失败')
    }
  }

  async function saveConfig(scope: ConfigScope, values: Record<string, string>) {
    setLoading(true)
    setError(null)
    try {
      const response = await saveTb20Config({ scope, values })
      if (scope === 'dataset-production') {
        setDatasetConfig(stringValues(response))
      } else {
        setExecutionConfig(stringValues(response))
      }
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : '保存配置失败')
    } finally {
      setLoading(false)
    }
  }

  async function startDataset() {
    setLoading(true)
    setError(null)
    try {
      const run = await startTb20DatasetRun({
        domain,
        sourceChannel,
        brief,
        outputRoot: datasetConfig.outputRoot,
        workspaceRoot: datasetConfig.workspaceRoot,
        channelConfig: {
          acquisitionMethod: channelMethod(sourceChannel),
          sourceName: datasetConfig.sourceName,
          sourceUrl: datasetConfig.sourceUrl,
          license: datasetConfig.license,
          licenseUrl: datasetConfig.licenseUrl,
          termsUrl: datasetConfig.termsUrl,
          allowedForTaskGeneration: datasetConfig.allowedForTaskGeneration === 'true',
          adapterType: 'codex',
          codexBinary: datasetConfig.codexBinary || 'codex',
          codexModel: datasetConfig.codexModel,
          codexProfile: datasetConfig.codexProfile,
          codexSandbox: datasetConfig.codexSandbox || 'danger-full-access',
          codexSkillSyncMode: datasetConfig.codexSkillSyncMode || 'symlink',
          licenseAllowlist: datasetConfig.licenseAllowlist,
          maxCandidates: datasetConfig.maxCandidates,
          githubApiBase: datasetConfig.githubApiBase,
          ghArchiveBase: datasetConfig.ghArchiveBase,
        },
      })
      await selectRun(run)
      setRuns(await listTb20Runs())
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : '启动数据集生产失败')
    } finally {
      setLoading(false)
    }
  }

  async function startExecution() {
    setLoading(true)
    setError(null)
    try {
      const run = await startTb20ExecutionRun({
        sourceRoot,
        outputRoot: executionConfig.outputRoot,
        workspaceRoot: executionConfig.workspaceRoot,
        agent: executionConfig.agent || 'claude-code',
        model: executionConfig.model,
        concurrency: Number(executionConfig.concurrency || 1),
        failFast: executionConfig.failFast === 'true',
        taskPaths: taskPaths.split('\n').map((item) => item.trim()).filter(Boolean),
        executionConfig: {
          dockerRegistryMirrors: executionConfig.dockerRegistryMirrors,
          aptMirror: executionConfig.aptMirror,
          pythonIndexUrl: executionConfig.pythonIndexUrl,
        },
      })
      await selectRun(run)
      setRuns(await listTb20Runs())
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : '启动执行交付失败')
    } finally {
      setLoading(false)
    }
  }

  async function selectRun(run: Tb20Run) {
    setActiveRun(run)
    try {
      const [fresh, nextLog] = await Promise.all([getTb20Run(run.runId), getTb20RunLog(run.runId)])
      setActiveRun(fresh)
      setLog(nextLog)
    } catch {
      setLog('')
    }
  }

  const visibleRuns = useMemo(
    () => runs.filter((run) => (tab === 'dataset' ? run.kind === 'DATASET_PRODUCTION' : run.kind === 'BATCH_EXECUTION_DELIVERY')),
    [runs, tab]
  )

  return (
    <div className="h-screen overflow-y-auto bg-primary custom-scrollbar">
      <div className="sticky top-0 z-20 border-b border-terminal bg-white/90 px-4 py-3 backdrop-blur-lg">
        <div className="mx-auto flex max-w-7xl items-center justify-between gap-3">
          <div className="flex min-w-0 items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-cyan text-white">
              <Icon icon="mdi:console-line" className="h-6 w-6" />
            </div>
            <div className="min-w-0">
              <h1 className="truncate text-lg font-bold text-text-primary">Terminal-Bench 2.0 Workbench</h1>
              <p className="truncate text-xs text-text-secondary">两个 skill：数据集生产与批量执行交付</p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <Link to="/swe" className="inline-flex h-9 items-center gap-2 rounded-lg border border-terminal bg-white px-3 text-sm font-bold text-text-primary hover:bg-tertiary/50">
              <Icon icon="mdi:source-branch-sync" className="h-4 w-4 text-cyan" />
              SWE-Pro
            </Link>
            <Link to="/" className="inline-flex h-9 items-center gap-2 rounded-lg border border-terminal bg-white px-3 text-sm font-bold text-text-primary hover:bg-tertiary/50">
              <Icon icon="mdi:arrow-left" className="h-4 w-4 text-cyan" />
              返回
            </Link>
          </div>
        </div>
      </div>

      <div className="mx-auto max-w-7xl p-4 lg:p-6">
        {error && <Banner text={error} />}

        <div className="mb-4 grid gap-2 md:grid-cols-2">
          <ModeButton
            active={tab === 'dataset'}
            icon="mdi:database-edit-outline"
            title="Dataset Production"
            desc="固定领域与合法渠道，产出 instruction.md 和 test-generation-brief.md"
            onClick={() => setTab('dataset')}
          />
          <ModeButton
            active={tab === 'execution'}
            icon="mdi:package-variant-closed-check"
            title="Batch Execution Delivery"
            desc="执行已有 TB2.0 dataset，收集真实 agent-logs 并打包"
            onClick={() => setTab('execution')}
          />
        </div>

        <div className="grid gap-4 xl:grid-cols-[420px_minmax(0,1fr)]">
          {tab === 'dataset' ? (
            <DatasetPanel
              config={datasetConfig}
              domain={domain}
              sourceChannel={sourceChannel}
              brief={brief}
              loading={loading}
              onConfigChange={setDatasetConfig}
              onSave={() => saveConfig('dataset-production', datasetConfig)}
              onDomainChange={setDomain}
              onSourceChannelChange={setSourceChannel}
              onBriefChange={setBrief}
              onStart={startDataset}
            />
          ) : (
            <ExecutionPanel
              config={executionConfig}
              sourceRoot={sourceRoot}
              taskPaths={taskPaths}
              loading={loading}
              onConfigChange={setExecutionConfig}
              onSave={() => saveConfig('batch-execution-delivery', executionConfig)}
              onSourceRootChange={(value) => {
                setSourceRoot(value)
                setExecutionConfig((current) => ({ ...current, sourceRoot: value }))
              }}
              onTaskPathsChange={setTaskPaths}
              onStart={startExecution}
            />
          )}

          <div className="space-y-4">
            <RunList runs={visibleRuns} activeRunId={activeRun?.runId} onSelect={selectRun} />
            <RunDetail run={activeRun} log={log} />
          </div>
        </div>
      </div>
    </div>
  )
}

function DatasetPanel(props: {
  config: Record<string, string>
  domain: Tb20Domain
  sourceChannel: string
  brief: string
  loading: boolean
  onConfigChange: (value: Record<string, string>) => void
  onSave: () => void
  onDomainChange: (value: Tb20Domain) => void
  onSourceChannelChange: (value: string) => void
  onBriefChange: (value: string) => void
  onStart: () => void
}) {
  return (
    <section className="space-y-4">
      <Card title="生产参数" icon="mdi:tune-variant">
        <Select label="领域" value={props.domain} options={DOMAINS} onChange={(value) => props.onDomainChange(value as Tb20Domain)} />
        <Select label="素材渠道" value={props.sourceChannel} options={CHANNELS[props.domain]} onChange={props.onSourceChannelChange} />
        <TextArea label="brief/spec input" value={props.brief} onChange={props.onBriefChange} rows={5} />
        <ActionButton label="启动受控生产流程" icon="mdi:play" loading={props.loading} onClick={props.onStart} />
      </Card>
      <Card title="渠道配置" icon="mdi:source-branch">
        <Info text="GitHub token 复用 SWE-Pro GitHub Token Pool；这里不单独维护密钥。" />
        <Field label="workspaceRoot" value={props.config.workspaceRoot || ''} onChange={(v) => props.onConfigChange({ ...props.config, workspaceRoot: v })} />
        <Field label="outputRoot" value={props.config.outputRoot || ''} onChange={(v) => props.onConfigChange({ ...props.config, outputRoot: v })} />
        <Field label="sourceName" value={props.config.sourceName || ''} onChange={(v) => props.onConfigChange({ ...props.config, sourceName: v })} />
        <Field label="sourceUrl" value={props.config.sourceUrl || ''} onChange={(v) => props.onConfigChange({ ...props.config, sourceUrl: v })} />
        <Field label="license" value={props.config.license || ''} onChange={(v) => props.onConfigChange({ ...props.config, license: v })} />
        <Field label="licenseUrl" value={props.config.licenseUrl || ''} onChange={(v) => props.onConfigChange({ ...props.config, licenseUrl: v })} />
        <Field label="termsUrl" value={props.config.termsUrl || ''} onChange={(v) => props.onConfigChange({ ...props.config, termsUrl: v })} />
        <Select label="allowedForTaskGeneration" value={props.config.allowedForTaskGeneration || 'false'} options={['false', 'true']} onChange={(v) => props.onConfigChange({ ...props.config, allowedForTaskGeneration: v })} />
        <Field label="codexBinary" value={props.config.codexBinary || 'codex'} onChange={(v) => props.onConfigChange({ ...props.config, codexBinary: v })} />
        <Field label="codexModel" value={props.config.codexModel || ''} onChange={(v) => props.onConfigChange({ ...props.config, codexModel: v })} />
        <Field label="codexProfile" value={props.config.codexProfile || ''} onChange={(v) => props.onConfigChange({ ...props.config, codexProfile: v })} />
        <Select label="codexSandbox" value={props.config.codexSandbox || 'danger-full-access'} options={['danger-full-access', 'workspace-write', 'read-only']} onChange={(v) => props.onConfigChange({ ...props.config, codexSandbox: v })} />
        <Select label="codexSkillSyncMode" value={props.config.codexSkillSyncMode || 'symlink'} options={['symlink', 'copy', 'off']} onChange={(v) => props.onConfigChange({ ...props.config, codexSkillSyncMode: v })} />
        <Field label="githubApiBase" value={props.config.githubApiBase || ''} onChange={(v) => props.onConfigChange({ ...props.config, githubApiBase: v })} />
        <Field label="ghArchiveBase" value={props.config.ghArchiveBase || ''} onChange={(v) => props.onConfigChange({ ...props.config, ghArchiveBase: v })} />
        <Field label="licenseAllowlist" value={props.config.licenseAllowlist || ''} onChange={(v) => props.onConfigChange({ ...props.config, licenseAllowlist: v })} />
        <Field label="maxCandidates" value={props.config.maxCandidates || ''} onChange={(v) => props.onConfigChange({ ...props.config, maxCandidates: v })} />
        <SecondaryButton label="保存生产配置" icon="mdi:content-save-outline" onClick={props.onSave} />
      </Card>
    </section>
  )
}

function ExecutionPanel(props: {
  config: Record<string, string>
  sourceRoot: string
  taskPaths: string
  loading: boolean
  onConfigChange: (value: Record<string, string>) => void
  onSave: () => void
  onSourceRootChange: (value: string) => void
  onTaskPathsChange: (value: string) => void
  onStart: () => void
}) {
  return (
    <section className="space-y-4">
      <Card title="执行参数" icon="mdi:run-fast">
        <Field label="sourceRoot" value={props.sourceRoot} onChange={props.onSourceRootChange} />
        <TextArea label="taskPaths（可选，每行一个）" value={props.taskPaths} onChange={props.onTaskPathsChange} rows={4} />
        <Field label="agent" value={props.config.agent || 'claude-code'} onChange={(v) => props.onConfigChange({ ...props.config, agent: v })} />
        <Field label="model" value={props.config.model || ''} onChange={(v) => props.onConfigChange({ ...props.config, model: v })} />
        <ActionButton label="启动脚本化执行交付" icon="mdi:playlist-play" loading={props.loading} onClick={props.onStart} />
      </Card>
      <Card title="执行环境配置" icon="mdi:cog-outline">
        <Field label="workspaceRoot" value={props.config.workspaceRoot || ''} onChange={(v) => props.onConfigChange({ ...props.config, workspaceRoot: v })} />
        <Field label="outputRoot" value={props.config.outputRoot || ''} onChange={(v) => props.onConfigChange({ ...props.config, outputRoot: v })} />
        <Field label="concurrency" value={props.config.concurrency || '1'} onChange={(v) => props.onConfigChange({ ...props.config, concurrency: v })} />
        <Select label="failFast" value={props.config.failFast || 'false'} options={['false', 'true']} onChange={(v) => props.onConfigChange({ ...props.config, failFast: v })} />
        <Field label="dockerRegistryMirrors" value={props.config.dockerRegistryMirrors || ''} onChange={(v) => props.onConfigChange({ ...props.config, dockerRegistryMirrors: v })} />
        <Field label="aptMirror" value={props.config.aptMirror || ''} onChange={(v) => props.onConfigChange({ ...props.config, aptMirror: v })} />
        <Field label="pythonIndexUrl" value={props.config.pythonIndexUrl || ''} onChange={(v) => props.onConfigChange({ ...props.config, pythonIndexUrl: v })} />
        <SecondaryButton label="保存执行配置" icon="mdi:content-save-outline" onClick={props.onSave} />
      </Card>
    </section>
  )
}

function RunList({ runs, activeRunId, onSelect }: { runs: Tb20Run[]; activeRunId?: string; onSelect: (run: Tb20Run) => void }) {
  return (
    <Card title="运行记录" icon="mdi:history">
      <div className="max-h-[300px] overflow-y-auto custom-scrollbar">
        {runs.length === 0 ? (
          <div className="text-sm text-text-secondary">暂无运行记录</div>
        ) : runs.map((run) => (
          <button
            key={run.runId}
            type="button"
            onClick={() => onSelect(run)}
            className={cn('mb-2 w-full rounded-lg border p-3 text-left text-sm', activeRunId === run.runId ? 'border-cyan bg-primary-50' : 'border-terminal bg-primary hover:bg-tertiary/40')}
          >
            <div className="flex items-center justify-between gap-2">
              <span className="font-bold text-text-primary">{run.runId}</span>
              <StatusPill status={run.status} />
            </div>
            <div className="mt-1 text-xs text-text-secondary">{run.skillName}</div>
          </button>
        ))}
      </div>
    </Card>
  )
}

function RunDetail({ run, log }: { run: Tb20Run | null; log: string }) {
  if (!run) {
    return <Card title="运行详情" icon="mdi:file-document-outline"><div className="text-sm text-text-secondary">选择或启动一个 run。</div></Card>
  }
  return (
    <Card title="运行详情" icon="mdi:file-document-outline">
      <div className="mb-3 grid gap-2 md:grid-cols-2">
        <Metric label="Status" value={run.status} />
        <Metric label="Exit" value={run.exitCode === undefined ? '-' : String(run.exitCode)} />
        <Metric label="Workspace" value={run.workspace} />
        <Metric label="Output" value={run.outputRoot} />
      </div>
      <div className="mb-3 grid gap-2">
        {run.stages.map((stage) => (
          <div key={stage.code} className="rounded-lg border border-terminal bg-primary p-3">
            <div className="flex items-center justify-between gap-2">
              <span className="text-sm font-bold text-text-primary">{stage.name}</span>
              <span className="text-xs text-text-secondary">{stage.status}</span>
            </div>
            <p className="mt-1 text-xs text-text-muted">{stage.note}</p>
          </div>
        ))}
      </div>
      <div className="mb-3 grid gap-2 md:grid-cols-2">
        {run.artifacts.map((artifact) => (
          <div key={`${artifact.name}-${artifact.path}`} className="rounded-lg border border-terminal bg-primary p-3">
            <div className="flex items-center justify-between gap-2">
              <span className="text-xs font-bold text-text-secondary">{artifact.name}</span>
              <StatusPill status={artifact.present ? 'PRESENT' : 'MISSING'} />
            </div>
            <p className="mt-1 break-all text-xs text-text-muted">{artifact.path}</p>
          </div>
        ))}
      </div>
      <pre className="max-h-[360px] overflow-auto rounded-lg border border-terminal bg-zinc-950 p-3 text-xs text-zinc-100 custom-scrollbar">
        {log || '日志尚未写入'}
      </pre>
    </Card>
  )
}

function ModeButton(props: { active: boolean; icon: string; title: string; desc: string; onClick: () => void }) {
  return (
    <button type="button" onClick={props.onClick} className={cn('rounded-lg border bg-white p-4 text-left', props.active ? 'border-cyan bg-primary-50' : 'border-terminal hover:bg-tertiary/40')}>
      <div className="flex items-center gap-2 text-sm font-bold text-text-primary">
        <Icon icon={props.icon} className="h-5 w-5 text-cyan" />
        {props.title}
      </div>
      <p className="mt-1 text-xs text-text-secondary">{props.desc}</p>
    </button>
  )
}

function Card({ title, icon, children }: { title: string; icon: string; children: React.ReactNode }) {
  return (
    <section className="rounded-lg border border-terminal bg-white p-4">
      <h3 className="mb-3 flex items-center gap-2 text-sm font-bold text-text-primary">
        <Icon icon={icon} className="h-4 w-4 text-cyan" />
        {title}
      </h3>
      <div className="space-y-3">{children}</div>
    </section>
  )
}

function Field({ label, value, onChange }: { label: string; value: string; onChange: (value: string) => void }) {
  return (
    <label className="block">
      <span className="mb-1 block text-xs font-bold text-text-secondary">{label}</span>
      <input value={value} onChange={(event) => onChange(event.target.value)} className="h-10 w-full rounded-lg border border-terminal bg-white px-3 text-sm text-text-primary outline-none focus:border-cyan" />
    </label>
  )
}

function TextArea({ label, value, rows, onChange }: { label: string; value: string; rows: number; onChange: (value: string) => void }) {
  return (
    <label className="block">
      <span className="mb-1 block text-xs font-bold text-text-secondary">{label}</span>
      <textarea value={value} rows={rows} onChange={(event) => onChange(event.target.value)} className="w-full rounded-lg border border-terminal bg-white px-3 py-2 text-sm text-text-primary outline-none focus:border-cyan" />
    </label>
  )
}

function Select({ label, value, options, onChange }: { label: string; value: string; options: string[]; onChange: (value: string) => void }) {
  return (
    <label className="block">
      <span className="mb-1 block text-xs font-bold text-text-secondary">{label}</span>
      <select value={value} onChange={(event) => onChange(event.target.value)} className="h-10 w-full rounded-lg border border-terminal bg-white px-3 text-sm text-text-primary outline-none focus:border-cyan">
        {options.map((option) => <option key={option} value={option}>{option}</option>)}
      </select>
    </label>
  )
}

function ActionButton({ label, icon, loading, onClick }: { label: string; icon: string; loading: boolean; onClick: () => void }) {
  return (
    <button type="button" disabled={loading} onClick={onClick} className="inline-flex h-10 w-full items-center justify-center gap-2 rounded-lg bg-cyan px-3 text-sm font-bold text-white disabled:opacity-60">
      <Icon icon={loading ? 'mdi:loading' : icon} className={cn('h-4 w-4', loading && 'animate-spin')} />
      {label}
    </button>
  )
}

function SecondaryButton({ label, icon, onClick }: { label: string; icon: string; onClick: () => void }) {
  return (
    <button type="button" onClick={onClick} className="inline-flex h-10 w-full items-center justify-center gap-2 rounded-lg border border-terminal bg-white px-3 text-sm font-bold text-text-primary hover:bg-tertiary/40">
      <Icon icon={icon} className="h-4 w-4 text-cyan" />
      {label}
    </button>
  )
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg border border-terminal bg-primary p-3">
      <div className="text-xs text-text-muted">{label}</div>
      <div className="mt-1 break-all text-sm font-bold text-text-primary">{value}</div>
    </div>
  )
}

function StatusPill({ status }: { status: string }) {
  const ok = ['COMPLETED', 'PRESENT', 'READY'].includes(status)
  const bad = ['FAILED', 'MISSING', 'BLOCKED'].includes(status)
  return (
    <span className={cn('rounded-full border px-2 py-0.5 text-xs font-bold', ok ? 'border-emerald-200 bg-emerald-50 text-emerald-700' : bad ? 'border-rose-200 bg-rose-50 text-rose-700' : 'border-amber-200 bg-amber-50 text-amber-700')}>
      {status}
    </span>
  )
}

function Banner({ text }: { text: string }) {
  return (
    <div className="mb-4 flex items-start gap-2 rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-sm text-rose-700">
      <Icon icon="mdi:alert-circle-outline" className="mt-0.5 h-4 w-4 flex-shrink-0" />
      <span>{text}</span>
    </div>
  )
}

function Info({ text }: { text: string }) {
  return (
    <div className="rounded-lg border border-cyan/20 bg-cyan/5 px-3 py-2 text-xs text-text-secondary">
      {text}
    </div>
  )
}

function stringValues(response: Tb20ConfigResponse): Record<string, string> {
  const values: Record<string, string> = {}
  Object.entries(response.values ?? {}).forEach(([key, value]) => {
    values[key] = value == null ? '' : String(value)
  })
  return values
}

function channelMethod(channel: string) {
  if (channel.includes('github')) return 'GitHub API + repo metadata'
  if (channel.includes('rfc') || channel.includes('iana')) return 'official spec index'
  if (channel.includes('debian')) return 'official source mirror'
  return 'official API/archive/repository'
}
