import { useEffect, useMemo, useState } from 'react'
import type { FormEvent } from 'react'
import { Icon } from '@iconify/react'
import { motion } from 'framer-motion'
import { Link } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import {
  createSweTask,
  createSweTaskFromCandidate,
  exportSweAllowedRepos,
  getSweModelIoConsole,
  getSweRuntimeSettings,
  getSweRun,
  listSweAllowedRepos,
  listSweCandidates,
  listSweRuns,
  listSweTasks,
  scanGithubMergedPullCandidates,
  searchGithubRepositories,
  saveSweRuntimeSettings,
  startSweRun,
} from '@/lib/api'
import type {
  GithubLanguage,
  GithubPullCandidate,
  GithubPullScanResponse,
  GithubRepository,
  GithubSortOrder,
  PipelineStatus,
  SweAllowedRepo,
  SweArtifact,
  SweModelIoAttempt,
  SweModelIoConsole,
  SweModelIoResponse,
  SwePipelineRun,
  SweRuntimeSetting,
  SweStage,
  SweTask,
  SweTaskCreateRequest,
} from '@/types/swe'

const DEFAULT_SAMPLE_PATH = '/Users/liuyifei/Downloads/production-task-new-api-4889'
const PR_SCAN_BATCH_SIZE = 5
const PR_SCAN_BATCHES_PER_CLICK = 12
const PR_SCAN_TARGET_CANDIDATES = 10
type WorkflowStep = 'discover' | 'candidates' | 'tasks' | 'runs'

const WORKFLOW_STEPS: Array<{ key: WorkflowStep; label: string; icon: string }> = [
  { key: 'discover', label: '项目发现', icon: 'mdi:github' },
  { key: 'candidates', label: '候选筛选', icon: 'mdi:source-pull' },
  { key: 'tasks', label: '创建任务', icon: 'mdi:clipboard-text-outline' },
  { key: 'runs', label: '流水线验收', icon: 'mdi:timeline-check-outline' },
]

const GITHUB_LANGUAGES: Array<{ value: GithubLanguage; label: string }> = [
  { value: 'c', label: 'C' },
  { value: 'c++', label: 'C++' },
  { value: 'ruby', label: 'Ruby' },
  { value: 'rust', label: 'Rust' },
  { value: 'go', label: 'Go' },
  { value: 'javascript', label: 'JavaScript' },
  { value: 'php', label: 'PHP' },
  { value: 'ts', label: 'TypeScript' },
  { value: 'python', label: 'Python' },
  { value: 'java', label: 'Java' },
]

const SCA_LANGUAGE_OPTIONS: Array<{ value: string; label: string }> = [
  { value: 'all', label: '全部语言' },
  { value: 'unknown', label: 'Unknown' },
  { value: 'c', label: 'C' },
  { value: 'c++', label: 'C++' },
  { value: 'ruby', label: 'Ruby' },
  { value: 'rust', label: 'Rust' },
  { value: 'go', label: 'Go' },
  { value: 'javascript', label: 'JavaScript' },
  { value: 'php', label: 'PHP' },
  { value: 'typescript', label: 'TypeScript' },
  { value: 'python', label: 'Python' },
  { value: 'java', label: 'Java' },
]

const STATUS_CLASS: Record<PipelineStatus, string> = {
  CREATED: 'bg-slate-100 text-slate-700 border-slate-200',
  RUNNING: 'bg-blue-50 text-blue-700 border-blue-200',
  COMPLETED: 'bg-emerald-50 text-emerald-700 border-emerald-200',
  FAILED: 'bg-rose-50 text-rose-700 border-rose-200',
  SKIPPED: 'bg-zinc-100 text-zinc-600 border-zinc-200',
  DELIVERED: 'bg-purple-50 text-purple-700 border-purple-200',
}

const STATUS_ICON: Record<PipelineStatus, string> = {
  CREATED: 'mdi:clock-outline',
  RUNNING: 'mdi:progress-clock',
  COMPLETED: 'mdi:check-circle-outline',
  FAILED: 'mdi:alert-circle-outline',
  SKIPPED: 'mdi:debug-step-over',
  DELIVERED: 'mdi:package-variant-closed-check',
}

const emptyForm: SweTaskCreateRequest = {
  taskName: 'production-task-new-api-4889',
  repo: '',
  sourceUrl: 'https://github.com/QuantumNous/new-api/pull/4889',
  baseCommit: '',
  fixCommit: '',
  repoLanguage: '',
  issueSpecificity: '',
  issueCategories: '',
  samplePath: DEFAULT_SAMPLE_PATH,
}

export function SwePipelinePage() {
  const [activeStep, setActiveStep] = useState<WorkflowStep>('candidates')
  const [tasks, setTasks] = useState<SweTask[]>([])
  const [runs, setRuns] = useState<SwePipelineRun[]>([])
  const [selectedTaskId, setSelectedTaskId] = useState<number | null>(null)
  const [selectedRun, setSelectedRun] = useState<SwePipelineRun | null>(null)
  const [selectedCandidateId, setSelectedCandidateId] = useState<number | null>(null)
  const [form, setForm] = useState<SweTaskCreateRequest>(emptyForm)
  const [loading, setLoading] = useState(false)
  const [running, setRunning] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [searchLanguage, setSearchLanguage] = useState<GithubLanguage>('go')
  const [searchKeyword, setSearchKeyword] = useState('')
  const [minStars, setMinStars] = useState('100')
  const [maxStars, setMaxStars] = useState('')
  const [starOrder, setStarOrder] = useState<GithubSortOrder>('desc')
  const [githubPage, setGithubPage] = useState(1)
  const [githubPerPage, setGithubPerPage] = useState(20)
  const [searching, setSearching] = useState(false)
  const [githubTotal, setGithubTotal] = useState<number | null>(null)
  const [githubRepos, setGithubRepos] = useState<GithubRepository[]>([])
  const [selectedRepository, setSelectedRepository] = useState<GithubRepository | null>(null)
  const [allowedRepos, setAllowedRepos] = useState<SweAllowedRepo[]>([])
  const [allowedRepoLoading, setAllowedRepoLoading] = useState(false)
  const [allowedRepoExporting, setAllowedRepoExporting] = useState(false)
  const [allowedRepoPage, setAllowedRepoPage] = useState(1)
  const [allowedRepoPerPage, setAllowedRepoPerPage] = useState(20)
  const [allowedRepoTotal, setAllowedRepoTotal] = useState(0)
  const [allowedRepoTotalPages, setAllowedRepoTotalPages] = useState(1)
  const [allowedRepoLanguage, setAllowedRepoLanguage] = useState('all')
  const [allowedRepoCandidateFilter, setAllowedRepoCandidateFilter] = useState('all')
  const [candidates, setCandidates] = useState<GithubPullCandidate[]>([])
  const [candidateLoading, setCandidateLoading] = useState(false)
  const [candidatePage, setCandidatePage] = useState(1)
  const [candidatePerPage, setCandidatePerPage] = useState(10)
  const [candidateTotal, setCandidateTotal] = useState(0)
  const [candidateTotalPages, setCandidateTotalPages] = useState(1)
  const [modelIo, setModelIo] = useState<SweModelIoConsole | null>(null)
  const [modelIoLoading, setModelIoLoading] = useState(false)
  const [modelIoError, setModelIoError] = useState<string | null>(null)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [runtimeSettings, setRuntimeSettings] = useState<SweRuntimeSetting[]>([])
  const [settingsForm, setSettingsForm] = useState<Record<string, string>>({})
  const [settingsLoading, setSettingsLoading] = useState(false)
  const [settingsSaving, setSettingsSaving] = useState(false)
  const [settingsError, setSettingsError] = useState<string | null>(null)
  const [settingsMessage, setSettingsMessage] = useState<string | null>(null)
  const safeTasks = Array.isArray(tasks) ? tasks : []
  const safeRuns = Array.isArray(runs) ? runs : []

  const selectedTask = useMemo(
    () => safeTasks.find((task) => task.id === selectedTaskId) ?? null,
    [selectedTaskId, safeTasks]
  )

  const selectedCandidate = useMemo(
    () => candidates.find((candidate) => candidate.id === selectedCandidateId) ?? null,
    [candidates, selectedCandidateId]
  )

  const resumableRun = useMemo(() => {
    if (selectedRun && isResumableRun(selectedRun)) return selectedRun
    return safeRuns.find(isResumableRun) ?? null
  }, [safeRuns, selectedRun])

  const githubTotalPages = useMemo(() => {
    if (githubTotal === null) return 1
    const searchableTotal = Math.min(githubTotal, 1000)
    return Math.max(1, Math.ceil(searchableTotal / githubPerPage))
  }, [githubPerPage, githubTotal])

  const configuredSettingCount = useMemo(
    () => runtimeSettings.filter((setting) => setting.configured).length,
    [runtimeSettings]
  )

  async function refreshTasks(nextSelectedId?: number) {
    const nextTasks = await listSweTasks()
    setTasks(nextTasks)
    if (nextSelectedId) {
      setSelectedTaskId(nextSelectedId)
      return
    }
    if (!selectedTaskId && nextTasks[0]) {
      setSelectedTaskId(nextTasks[0].id)
    }
  }

  async function refreshRuns(taskId: number) {
    const nextRuns = await listSweRuns(taskId)
    setRuns(nextRuns)
    if (!selectedRun && nextRuns[0]) {
      setSelectedRun(await getSweRun(nextRuns[0].id))
    }
  }

  async function refreshCandidates(nextPage = candidatePage, nextPerPage = candidatePerPage) {
    setCandidateLoading(true)
    try {
      const response = await listSweCandidates({ page: nextPage, perPage: nextPerPage })
      setCandidates(response.candidates)
      if (
        response.candidates.length > 0 &&
        !response.candidates.some((candidate) => candidate.id === selectedCandidateId)
      ) {
        setSelectedCandidateId(response.candidates[0].id ?? null)
      }
      setCandidatePage(response.page)
      setCandidatePerPage(response.perPage)
      setCandidateTotal(response.total)
      setCandidateTotalPages(response.totalPages)
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : '加载候选列表失败')
    } finally {
      setCandidateLoading(false)
    }
  }

  async function refreshAllowedRepos(
    nextPage = allowedRepoPage,
    nextPerPage = allowedRepoPerPage
  ) {
    setAllowedRepoLoading(true)
    try {
      const response = await listSweAllowedRepos({
        page: nextPage,
        perPage: nextPerPage,
        language: allowedRepoLanguage === 'all' ? undefined : allowedRepoLanguage,
        inCandidate: parseCandidateFilter(allowedRepoCandidateFilter),
      })
      setAllowedRepos(response.repositories)
      setAllowedRepoPage(response.page)
      setAllowedRepoPerPage(response.perPage)
      setAllowedRepoTotal(response.total)
      setAllowedRepoTotalPages(response.totalPages)
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : '加载 SCA 放行仓库失败')
    } finally {
      setAllowedRepoLoading(false)
    }
  }

  async function handleExportAllowedRepos() {
    setAllowedRepoExporting(true)
    try {
      const blob = await exportSweAllowedRepos({
        language: allowedRepoLanguage === 'all' ? undefined : allowedRepoLanguage,
        inCandidate: parseCandidateFilter(allowedRepoCandidateFilter),
      })
      downloadBlob(blob, 'swe_sca_allowed_repos.csv')
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : '导出 SCA 放行仓库失败')
    } finally {
      setAllowedRepoExporting(false)
    }
  }

  async function refreshRuntimeSettings(quiet = false) {
    if (!quiet) {
      setSettingsLoading(true)
    }
    setSettingsError(null)
    try {
      const response = await getSweRuntimeSettings()
      setRuntimeSettings(response.settings)
      setSettingsForm(buildSettingsForm(response.settings))
    } catch (requestError) {
      setSettingsError(requestError instanceof Error ? requestError.message : '加载密钥设置失败')
    } finally {
      if (!quiet) {
        setSettingsLoading(false)
      }
    }
  }

  useEffect(() => {
    setLoading(true)
    Promise.all([refreshTasks(), refreshCandidates(), refreshRuntimeSettings(true)])
      .catch((requestError: unknown) =>
        setError(requestError instanceof Error ? requestError.message : '加载任务失败')
      )
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    if (!selectedTaskId) return
    refreshRuns(selectedTaskId).catch((requestError: unknown) =>
      setError(requestError instanceof Error ? requestError.message : '加载运行记录失败')
    )
  }, [selectedTaskId])

  useEffect(() => {
    if (activeStep !== 'discover') return
    refreshAllowedRepos(1, allowedRepoPerPage)
  }, [activeStep, allowedRepoLanguage, allowedRepoCandidateFilter])

  useEffect(() => {
    if (!selectedRun || selectedRun.status !== 'RUNNING') return

    const timer = window.setInterval(() => {
      getSweRun(selectedRun.id)
        .then(setSelectedRun)
        .catch((requestError: unknown) =>
          setError(requestError instanceof Error ? requestError.message : '刷新运行状态失败')
        )
    }, 2000)

    return () => window.clearInterval(timer)
  }, [selectedRun])

  useEffect(() => {
    if (!selectedRun) {
      setModelIo(null)
      return
    }
    refreshModelIo(selectedRun.id)
  }, [selectedRun?.id])

  useEffect(() => {
    if (!selectedRun || selectedRun.status !== 'RUNNING') return
    const timer = window.setInterval(() => {
      refreshModelIo(selectedRun.id, true)
    }, 2500)
    return () => window.clearInterval(timer)
  }, [selectedRun?.id, selectedRun?.status])

  async function refreshModelIo(runId: number, quiet = false) {
    if (!quiet) {
      setModelIoLoading(true)
    }
    setModelIoError(null)
    try {
      setModelIo(await getSweModelIoConsole(runId))
    } catch (requestError) {
      setModelIoError(requestError instanceof Error ? requestError.message : '加载模型 I/O 失败')
    } finally {
      if (!quiet) {
        setModelIoLoading(false)
      }
    }
  }

  async function handleCreateTask(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError(null)
    setLoading(true)
    try {
      const created = await createSweTask(form)
      await refreshTasks(created.id)
      setForm({ ...emptyForm, taskName: `${emptyForm.taskName}-${Date.now()}` })
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : '创建任务失败')
    } finally {
      setLoading(false)
    }
  }

  async function handleStartRun() {
    if (!selectedTask) return
    setError(null)
    setRunning(true)
    try {
      const run = await startSweRun({
        taskId: selectedTask.id,
        samplePath: isGithubPullTask(selectedTask) ? undefined : selectedTask.samplePath,
      })
      setSelectedRun(run)
      setActiveStep('runs')
      await refreshRuns(selectedTask.id)
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : '启动流水线失败')
    } finally {
      setRunning(false)
    }
  }

  async function handleResumeRun() {
    if (!selectedTask || !resumableRun) return
    setError(null)
    setRunning(true)
    try {
      const run = await startSweRun({
        taskId: selectedTask.id,
        resumeRunId: resumableRun.id,
        samplePath: selectedTask.samplePath,
      })
      setSelectedRun(run)
      setActiveStep('runs')
      await refreshRuns(selectedTask.id)
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : '断点续跑失败')
    } finally {
      setRunning(false)
    }
  }

  async function handleCreateAndRunFromCandidate(candidate: GithubPullCandidate) {
    if (!candidate.id) {
      throw new Error('候选 PR 尚未入库，请重新扫描后再创建任务')
    }
    setError(null)
    setLoading(true)
    setRunning(true)
    try {
      const task = await createSweTaskFromCandidate({
        candidateId: candidate.id,
        taskName: `production-task-${candidate.repo.replace('/', '-')}-${candidate.number}`,
      })
      await refreshTasks(task.id)
      const run = await startSweRun({ taskId: task.id })
      setSelectedRun(run)
      setActiveStep('runs')
      await refreshRuns(task.id)
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : '创建并启动候选任务失败')
      throw requestError
    } finally {
      setLoading(false)
      setRunning(false)
    }
  }

  async function handleCreateTaskFromCandidate(candidate: GithubPullCandidate) {
    if (!candidate.id) {
      throw new Error('候选 PR 尚未入库，请重新扫描后再创建任务')
    }
    setError(null)
    setLoading(true)
    try {
      const task = await createSweTaskFromCandidate({
        candidateId: candidate.id,
        taskName: `production-task-${candidate.repo.replace('/', '-')}-${candidate.number}`,
      })
      await refreshTasks(task.id)
      await refreshCandidates(candidatePage, candidatePerPage)
      setActiveStep('tasks')
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : '创建候选任务失败')
      throw requestError
    } finally {
      setLoading(false)
    }
  }

  async function runGithubSearch(nextPage = 1) {
    setError(null)
    setSearching(true)
    try {
      const minStarsValue = parseOptionalNumber(minStars)
      const maxStarsValue = parseOptionalNumber(maxStars)
      if (
        minStarsValue !== undefined &&
        maxStarsValue !== undefined &&
        maxStarsValue < minStarsValue
      ) {
        throw new Error('Stars 上限不能小于下限')
      }
      const response = await searchGithubRepositories({
        language: searchLanguage,
        keyword: searchKeyword,
        minStars: minStarsValue,
        maxStars: maxStarsValue,
        page: nextPage,
        perPage: githubPerPage,
        sort: 'stars',
        order: starOrder,
      })
      setGithubRepos(response.repositories)
      setSelectedRepository(response.repositories[0] ?? null)
      setGithubTotal(response.totalCount ?? 0)
      setGithubPage(response.page ?? nextPage)
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : '搜索 GitHub 项目失败')
    } finally {
      setSearching(false)
    }
  }

  async function handleSearchGithub(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    await runGithubSearch(1)
  }

  function useRepository(repo: GithubRepository) {
    setForm({
      ...form,
      taskName: repo.fullName.replace('/', '__'),
      repo: repo.fullName,
      sourceUrl: repo.htmlUrl,
      repoLanguage: searchLanguage,
      samplePath: '',
    })
    setActiveStep('tasks')
  }

  async function handleSaveSettings(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSettingsError(null)
    setSettingsMessage(null)
    setSettingsSaving(true)
    try {
      const response = await saveSweRuntimeSettings({ values: settingsForm })
      setRuntimeSettings(response.settings)
      setSettingsForm(buildSettingsForm(response.settings))
      setSettingsMessage('已保存到 Redis')
    } catch (requestError) {
      setSettingsError(requestError instanceof Error ? requestError.message : '保存密钥设置失败')
    } finally {
      setSettingsSaving(false)
    }
  }

  return (
    <div className="h-screen overflow-y-auto bg-primary custom-scrollbar">
      <div className="sticky top-0 z-20 border-b border-terminal bg-white/90 px-4 py-3 backdrop-blur-lg">
        <div className="mx-auto flex max-w-7xl items-center justify-between gap-3">
          <div className="flex min-w-0 items-center gap-3">
            <div className="flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-lg bg-gradient-to-br from-cyan to-green shadow-lg shadow-cyan/20">
              <Icon icon="mdi:source-branch-sync" className="h-6 w-6 text-white" />
            </div>
            <div className="min-w-0">
              <h1 className="truncate text-lg font-bold text-text-primary">SWE-Pro Pipeline</h1>
              <p className="truncate text-xs text-text-secondary">独立数据生产与验收工作台</p>
            </div>
          </div>
          <div className="flex shrink-0 items-center gap-2">
            <button
              type="button"
              className="inline-flex h-9 items-center gap-2 rounded-lg border border-terminal bg-white px-3 text-sm font-bold text-text-primary transition-colors hover:bg-tertiary/50"
              onClick={() => {
                setSettingsOpen((value) => !value)
                if (runtimeSettings.length === 0) {
                  refreshRuntimeSettings()
                }
              }}
            >
              <Icon icon="mdi:key-variant" className="h-4 w-4 text-cyan" />
              密钥设置
              {runtimeSettings.length > 0 && (
                <span className="rounded-full bg-tertiary px-1.5 py-0.5 text-[10px] text-text-secondary">
                  {configuredSettingCount}/{runtimeSettings.length}
                </span>
              )}
            </button>
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
        <div className="mb-5 flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
          <div>
            <h2 className="text-xl font-bold text-text-primary">SWE-Pro 数据流水线</h2>
            <p className="mt-1 text-sm text-text-secondary">
              从项目发现到候选筛选、任务制作和流水线验收
            </p>
          </div>
          <div className="flex flex-wrap gap-2 text-xs text-text-secondary">
            <span className="rounded-full border border-terminal bg-white px-3 py-1">候选 {candidateTotal.toLocaleString()}</span>
            <span className="rounded-full border border-terminal bg-white px-3 py-1">任务 {safeTasks.length}</span>
            <span className="rounded-full border border-terminal bg-white px-3 py-1">运行 {safeRuns.length}</span>
          </div>
        </div>

        {error && (
          <div className="mb-4 flex items-start gap-2 rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-sm text-rose-700">
            <Icon icon="mdi:alert-circle-outline" className="mt-0.5 h-4 w-4 flex-shrink-0" />
            <span>{error}</span>
          </div>
        )}

        {settingsOpen && (
          <RuntimeSettingsPanel
            settings={runtimeSettings}
            values={settingsForm}
            loading={settingsLoading}
            saving={settingsSaving}
            error={settingsError}
            message={settingsMessage}
            onChange={(key, value) => {
              setSettingsMessage(null)
              setSettingsForm((current) => ({ ...current, [key]: value }))
            }}
            onRefresh={() => refreshRuntimeSettings()}
            onSubmit={handleSaveSettings}
          />
        )}

        <WorkflowStepper activeStep={activeStep} onChange={setActiveStep} />

        {activeStep === 'discover' && (
          <div className="space-y-4">
            <div className="grid gap-4 xl:grid-cols-[420px_minmax(0,1fr)]">
              <GithubSearchPanel
                language={searchLanguage}
                keyword={searchKeyword}
                minStars={minStars}
                maxStars={maxStars}
                starOrder={starOrder}
                perPage={githubPerPage}
                searching={searching}
                onLanguageChange={setSearchLanguage}
                onKeywordChange={setSearchKeyword}
                onMinStarsChange={setMinStars}
                onMaxStarsChange={setMaxStars}
                onStarOrderChange={setStarOrder}
                onPerPageChange={setGithubPerPage}
                onSubmit={handleSearchGithub}
              />
              <RepositoryResultsPanel
                repositories={githubRepos}
                total={githubTotal}
                page={githubPage}
                totalPages={githubTotalPages}
                searching={searching}
                selectedRepository={selectedRepository}
                onSelectRepository={setSelectedRepository}
                onPageChange={runGithubSearch}
                onUseRepository={useRepository}
                onCreateAndRunCandidate={handleCreateAndRunFromCandidate}
              />
            </div>
            <AllowedRepoRegistryPanel
              repositories={allowedRepos}
              loading={allowedRepoLoading}
              exporting={allowedRepoExporting}
              page={allowedRepoPage}
              perPage={allowedRepoPerPage}
              total={allowedRepoTotal}
              totalPages={allowedRepoTotalPages}
              language={allowedRepoLanguage}
              candidateFilter={allowedRepoCandidateFilter}
              onLanguageChange={setAllowedRepoLanguage}
              onCandidateFilterChange={setAllowedRepoCandidateFilter}
              onRefresh={() => refreshAllowedRepos(allowedRepoPage, allowedRepoPerPage)}
              onPageChange={(page) => refreshAllowedRepos(page, allowedRepoPerPage)}
              onPerPageChange={(perPage) => refreshAllowedRepos(1, perPage)}
              onExport={handleExportAllowedRepos}
            />
          </div>
        )}

        {activeStep === 'candidates' && (
          <div className="grid gap-4 xl:grid-cols-[460px_minmax(0,1fr)]">
            <CandidateRegistryPanel
              candidates={candidates}
              loading={candidateLoading}
              page={candidatePage}
              perPage={candidatePerPage}
              total={candidateTotal}
              totalPages={candidateTotalPages}
              selectedCandidateId={selectedCandidateId}
              onSelectCandidate={setSelectedCandidateId}
              onRefresh={refreshCandidates}
              onPageChange={(page) => refreshCandidates(page, candidatePerPage)}
              onPerPageChange={(perPage) => refreshCandidates(1, perPage)}
            />
            <CandidateDetailPanel
              candidate={selectedCandidate}
              loading={loading}
              onCreateTask={handleCreateTaskFromCandidate}
              onDiscover={() => setActiveStep('discover')}
            />
          </div>
        )}

        {activeStep === 'tasks' && (
          <div className="grid gap-4 xl:grid-cols-[380px_minmax(0,1fr)]">
            <section className="space-y-4">
              <TaskList
                tasks={safeTasks}
                selectedTaskId={selectedTaskId}
                onSelectTask={(taskId) => {
                  setSelectedTaskId(taskId)
                  setSelectedRun(null)
                }}
              />
              <TaskCreatePanel
                form={form}
                loading={loading}
                onChange={setForm}
                onSubmit={handleCreateTask}
              />
            </section>
            <section className="space-y-4">
              <TaskOverview task={selectedTask} />
              <TaskRunActions
                task={selectedTask}
                resumableRun={resumableRun}
                running={running}
                onStartRun={handleStartRun}
                onResumeRun={handleResumeRun}
              />
              <RunTimeline run={selectedRun} title="最近运行阶段进度" />
            </section>
          </div>
        )}

        {activeStep === 'runs' && (
          <div className="grid gap-4 xl:grid-cols-[420px_minmax(0,1fr)]">
            <section className="space-y-4">
              <RunList
                runs={safeRuns}
                selectedRunId={selectedRun?.id ?? null}
                onSelectRun={(runId) => {
                  getSweRun(runId)
                    .then(setSelectedRun)
                    .catch((requestError: unknown) =>
                      setError(
                        requestError instanceof Error ? requestError.message : '加载运行详情失败'
                      )
                    )
                }}
              />
            </section>
            <section className="space-y-4">
              <RunSummaryPanel run={selectedRun} task={selectedTask} onGoTasks={() => setActiveStep('tasks')} />
              <RunTimeline run={selectedRun} />
              <ModelIoConsole
                run={selectedRun}
                console={modelIo}
                loading={modelIoLoading}
                error={modelIoError}
                onRefresh={() => selectedRun && refreshModelIo(selectedRun.id)}
              />
              <ArtifactList artifacts={selectedRun?.artifacts ?? []} />
            </section>
          </div>
        )}
      </div>
    </div>
  )
}

function isGithubPullTask(task: SweTask): boolean {
  return Boolean(task.sourceUrl?.includes('github.com/') && task.sourceUrl.includes('/pull/'))
}

function isResumableRun(run: SwePipelineRun): boolean {
  return run.status === 'FAILED' || run.status === 'CREATED'
}

function buildSettingsForm(settings: SweRuntimeSetting[]): Record<string, string> {
  return settings.reduce<Record<string, string>>((values, setting) => {
    values[setting.key] = setting.secret ? '' : setting.value ?? ''
    return values
  }, {})
}

function RuntimeSettingsPanel({
  settings,
  values,
  loading,
  saving,
  error,
  message,
  onChange,
  onRefresh,
  onSubmit,
}: {
  settings: SweRuntimeSetting[]
  values: Record<string, string>
  loading: boolean
  saving: boolean
  error: string | null
  message: string | null
  onChange: (key: string, value: string) => void
  onRefresh: () => void
  onSubmit: (event: FormEvent<HTMLFormElement>) => void
}) {
  const secretSettings = settings.filter((setting) => setting.secret)
  const modelSettings = settings.filter((setting) => !setting.secret)

  return (
    <form
      onSubmit={onSubmit}
      className="mb-4 rounded-lg border border-terminal bg-white p-4 shadow-sm"
    >
      <div className="mb-4 flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
        <div className="flex items-center gap-2">
          <Icon icon="mdi:key-chain" className="h-5 w-5 text-cyan" />
          <h3 className="text-sm font-bold text-text-primary">SWE-Pro 密钥设置</h3>
        </div>
        <div className="flex gap-2">
          <Button type="button" size="sm" variant="outline" disabled={loading} onClick={onRefresh}>
            刷新
          </Button>
          <Button type="submit" size="sm" disabled={loading || saving || settings.length === 0}>
            {saving ? '保存中' : '保存到 Redis'}
          </Button>
        </div>
      </div>

      {error && (
        <div className="mb-3 rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-xs text-rose-700">
          {error}
        </div>
      )}
      {message && (
        <div className="mb-3 rounded-lg border border-emerald-200 bg-emerald-50 px-3 py-2 text-xs text-emerald-700">
          {message}
        </div>
      )}

      {loading && settings.length === 0 ? (
        <EmptyState icon="mdi:loading" text="加载中" />
      ) : (
        <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
          <section>
            <h4 className="mb-2 text-xs font-bold uppercase text-text-muted">Tokens</h4>
            <div className="grid gap-3 md:grid-cols-2">
              {secretSettings.map((setting) => (
                <SecretSettingField
                  key={setting.key}
                  setting={setting}
                  value={values[setting.key] ?? ''}
                  onChange={(value) => onChange(setting.key, value)}
                />
              ))}
            </div>
          </section>
          <section>
            <h4 className="mb-2 text-xs font-bold uppercase text-text-muted">Models</h4>
            <div className="grid gap-3 md:grid-cols-2">
              {modelSettings.map((setting) => (
                <SettingField
                  key={setting.key}
                  setting={setting}
                  value={values[setting.key] ?? ''}
                  onChange={(value) => onChange(setting.key, value)}
                />
              ))}
            </div>
          </section>
        </div>
      )}
    </form>
  )
}

function SettingField({
  setting,
  value,
  onChange,
}: {
  setting: SweRuntimeSetting
  value: string
  onChange: (value: string) => void
}) {
  return (
    <label className="block">
      <span className="mb-1 flex items-center justify-between gap-2 text-xs font-medium text-text-secondary">
        <span>{setting.label}</span>
        {setting.configured && <span className="text-[10px] font-bold text-emerald-600">已配置</span>}
      </span>
      <input
        className="input-base h-10 text-sm"
        value={value}
        placeholder={setting.description}
        onChange={(event) => onChange(event.target.value)}
      />
    </label>
  )
}

function SecretSettingField({
  setting,
  value,
  onChange,
}: {
  setting: SweRuntimeSetting
  value: string
  onChange: (value: string) => void
}) {
  return (
    <label className="block">
      <span className="mb-1 flex items-center justify-between gap-2 text-xs font-medium text-text-secondary">
        <span>{setting.label}</span>
        <span className={cn('text-[10px] font-bold', setting.configured ? 'text-emerald-600' : 'text-text-muted')}>
          {setting.configured ? setting.maskedValue || '已配置' : '未配置'}
        </span>
      </span>
      <input
        type="password"
        autoComplete="off"
        className="input-base h-10 text-sm"
        value={value}
        placeholder={setting.configured ? '留空保持当前值' : setting.description}
        onChange={(event) => onChange(event.target.value)}
      />
    </label>
  )
}

function WorkflowStepper({
  activeStep,
  onChange,
}: {
  activeStep: WorkflowStep
  onChange: (step: WorkflowStep) => void
}) {
  return (
    <div className="mb-4 grid gap-2 md:grid-cols-4">
      {WORKFLOW_STEPS.map((step, index) => (
        <button
          key={step.key}
          type="button"
          onClick={() => onChange(step.key)}
          className={cn(
            'flex min-h-[64px] items-center gap-3 rounded-lg border bg-white px-3 py-2 text-left transition-colors',
            activeStep === step.key
              ? 'border-cyan bg-primary-50'
              : 'border-terminal hover:bg-tertiary/40'
          )}
        >
          <span className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-lg bg-slate-100 text-xs font-bold text-text-secondary">
            {index + 1}
          </span>
          <span className="min-w-0">
            <span className="flex items-center gap-2 text-sm font-bold text-text-primary">
              <Icon icon={step.icon} className="h-4 w-4 text-cyan" />
              {step.label}
            </span>
          </span>
        </button>
      ))}
    </div>
  )
}

function CandidateDetailPanel({
  candidate,
  loading,
  onCreateTask,
  onDiscover,
}: {
  candidate: GithubPullCandidate | null
  loading: boolean
  onCreateTask: (candidate: GithubPullCandidate) => Promise<void>
  onDiscover: () => void
}) {
  const [actionError, setActionError] = useState<string | null>(null)

  if (!candidate) {
    return (
      <div className="rounded-lg border border-terminal bg-white p-5 shadow-sm">
        <EmptyState icon="mdi:source-pull" text="选择候选 PR 后查看详情" />
        <Button type="button" size="sm" className="mt-4 w-full" onClick={onDiscover}>
          去发现项目
        </Button>
      </div>
    )
  }

  const disabled = loading || !candidate.id || candidate.duplicateStatus === 'DELIVERED'
  return (
    <div className="rounded-lg border border-terminal bg-white p-4 shadow-sm">
      <div className="mb-4 flex items-start justify-between gap-3">
        <div className="min-w-0">
          <a
            href={candidate.prUrl}
            target="_blank"
            rel="noreferrer"
            className="block truncate text-base font-bold text-cyan hover:underline"
          >
            #{candidate.number} {candidate.title || 'Untitled PR'}
          </a>
          <p className="mt-1 truncate text-xs text-text-secondary">{candidate.repo}</p>
        </div>
        <ScoreBadge grade={candidate.candidateGrade} score={candidate.score} />
      </div>
      <div className="grid gap-2 md:grid-cols-4">
        <Meta label="候选ID" value={candidate.id ? `#${candidate.id}` : '-'} />
        <Meta label="状态" value={candidate.candidateStatus || '-'} />
        <Meta label="去重" value={candidate.duplicateStatus || '-'} />
        <Meta label="语言" value={candidate.primaryLanguage || '-'} />
      </div>
      <div className="mt-3 grid gap-2 md:grid-cols-4">
        <Meta label="变更文件" value={String(candidate.patchFiles ?? 0)} />
        <Meta label="源码文件" value={String(candidate.sourceFiles ?? 0)} />
        <Meta label="总变更行" value={String(candidate.totalChanged ?? 0)} />
        <Meta label="生成占比" value={formatRatio(candidate.generatedOrI18nRatio)} />
        <Meta label="Gold 文件" value={String(candidate.goldPatchFiles ?? 0)} />
        <Meta label="Gold 源码" value={String(candidate.goldSourceFiles ?? 0)} />
        <Meta label="Gold 行数" value={String(candidate.goldTotalChanged ?? 0)} />
        <Meta label="测试行数" value={String(candidate.testTotalChanged ?? 0)} />
      </div>
      <p className="mt-4 text-sm leading-6 text-text-primary">{candidate.gradeReason || '-'}</p>
      {actionError && (
        <div className="mt-3 rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-xs text-rose-700">
          {actionError}
        </div>
      )}
      <div className="mt-4 flex justify-end">
        <Button
          type="button"
          size="sm"
          disabled={disabled}
          onClick={async () => {
            setActionError(null)
            try {
              await onCreateTask(candidate)
            } catch (requestError) {
              setActionError(requestError instanceof Error ? requestError.message : '创建候选任务失败')
            }
          }}
        >
          创建 Task
        </Button>
      </div>
    </div>
  )
}

function TaskRunActions({
  task,
  resumableRun,
  running,
  onStartRun,
  onResumeRun,
}: {
  task: SweTask | null
  resumableRun: SwePipelineRun | null
  running: boolean
  onStartRun: () => void
  onResumeRun: () => void
}) {
  return (
    <div className="rounded-lg border border-terminal bg-white p-4 shadow-sm">
      <div className="mb-3">
        <h3 className="text-sm font-bold text-text-primary">流水线操作</h3>
        <p className="mt-1 text-xs text-text-secondary">全新启动会创建新的运行记录；断点续跑会复用失败或中断的运行记录并跳过已完成阶段。</p>
      </div>
      <div className="grid gap-2 sm:grid-cols-2">
        <Button
          size="sm"
          onClick={onStartRun}
          disabled={!task || running}
          className="inline-flex items-center justify-center gap-2"
        >
          <Icon icon="mdi:play" className="h-4 w-4" />
          {running ? '启动中' : '全新启动'}
        </Button>
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={onResumeRun}
          disabled={!task || !resumableRun || running}
          className="inline-flex items-center justify-center gap-2"
        >
          <Icon icon="mdi:restore" className="h-4 w-4 text-cyan" />
          {resumableRun ? `断点续跑 Run #${resumableRun.id}` : '无可续跑记录'}
        </Button>
      </div>
    </div>
  )
}

function RunSummaryPanel({
  run,
  task,
  onGoTasks,
}: {
  run: SwePipelineRun | null
  task: SweTask | null
  onGoTasks: () => void
}) {
  return (
    <div className="rounded-lg border border-terminal bg-white p-4 shadow-sm">
      <div className="mb-3 flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
        <div>
          <h3 className="text-sm font-bold text-text-primary">运行上下文</h3>
          <p className="mt-1 text-xs text-text-secondary">
            {task ? task.taskName : '未选择任务'}
          </p>
        </div>
        {run ? <StatusBadge status={run.status} /> : null}
      </div>
      {run ? (
        <div className="grid gap-3 md:grid-cols-4">
          <Meta label="Run ID" value={`#${run.id}`} />
          <Meta label="Task ID" value={`#${run.taskId}`} />
          <Meta label="候选ID" value={run.candidateId ? `#${run.candidateId}` : '-'} />
          <Meta label="当前阶段" value={run.currentStage || '-'} />
        </div>
      ) : (
        <EmptyState icon="mdi:history" text="选择运行记录后查看验收上下文" />
      )}
      <div className="mt-3 flex justify-end">
        <Button type="button" variant="outline" size="sm" onClick={onGoTasks}>
          回到任务操作
        </Button>
      </div>
    </div>
  )
}

function GithubSearchPanel({
  language,
  keyword,
  minStars,
  maxStars,
  starOrder,
  perPage,
  searching,
  onLanguageChange,
  onKeywordChange,
  onMinStarsChange,
  onMaxStarsChange,
  onStarOrderChange,
  onPerPageChange,
  onSubmit,
}: {
  language: GithubLanguage
  keyword: string
  minStars: string
  maxStars: string
  starOrder: GithubSortOrder
  perPage: number
  searching: boolean
  onLanguageChange: (language: GithubLanguage) => void
  onKeywordChange: (keyword: string) => void
  onMinStarsChange: (stars: string) => void
  onMaxStarsChange: (stars: string) => void
  onStarOrderChange: (order: GithubSortOrder) => void
  onPerPageChange: (perPage: number) => void
  onSubmit: (event: FormEvent<HTMLFormElement>) => void
}) {
  return (
    <div className="rounded-lg border border-terminal bg-white p-4 shadow-sm">
      <div className="mb-4 flex items-center gap-2">
        <Icon icon="mdi:github" className="h-5 w-5 text-cyan" />
        <h3 className="text-sm font-bold text-text-primary">目标项目搜索</h3>
      </div>
      <form onSubmit={onSubmit} className="space-y-3">
        <label className="block">
          <span className="mb-1 block text-xs font-medium text-text-secondary">主语言</span>
          <select
            className="input-base h-10 text-sm"
            value={language}
            onChange={(event) => onLanguageChange(event.target.value as GithubLanguage)}
          >
            {GITHUB_LANGUAGES.map((item) => (
              <option key={item.value} value={item.value}>
                {item.label}
              </option>
            ))}
          </select>
        </label>
        <div className="grid grid-cols-[minmax(0,1fr)_96px] gap-2">
          <Field
            label="关键词"
            value={keyword}
            placeholder="api auth cache ui"
            onChange={onKeywordChange}
          />
          <label className="block">
            <span className="mb-1 block text-xs font-medium text-text-secondary">Stars 下限</span>
            <input
              type="number"
              min={0}
              className="input-base h-10 text-sm"
              value={minStars}
              onChange={(event) => onMinStarsChange(event.target.value)}
            />
          </label>
        </div>
        <div className="grid grid-cols-2 gap-2">
          <label className="block">
            <span className="mb-1 block text-xs font-medium text-text-secondary">Stars 上限</span>
            <input
              type="number"
              min={0}
              className="input-base h-10 text-sm"
              value={maxStars}
              placeholder="不限"
              onChange={(event) => onMaxStarsChange(event.target.value)}
            />
          </label>
          <label className="block">
            <span className="mb-1 block text-xs font-medium text-text-secondary">Stars 排序</span>
            <select
              className="input-base h-10 text-sm"
              value={starOrder}
              onChange={(event) => onStarOrderChange(event.target.value as GithubSortOrder)}
            >
              <option value="desc">Stars 倒序</option>
              <option value="asc">Stars 正序</option>
            </select>
          </label>
        </div>
        <label className="block">
          <span className="mb-1 block text-xs font-medium text-text-secondary">每页数量</span>
          <select
            className="input-base h-10 text-sm"
            value={perPage}
            onChange={(event) => onPerPageChange(Number(event.target.value))}
          >
            <option value={10}>10</option>
            <option value={20}>20</option>
            <option value={50}>50</option>
          </select>
        </label>
        <Button type="submit" size="sm" disabled={searching} className="w-full">
          {searching ? '搜索中' : '搜索 GitHub'}
        </Button>
      </form>
    </div>
  )
}

function RepositoryResultsPanel({
  repositories,
  total,
  page,
  totalPages,
  searching,
  selectedRepository,
  onSelectRepository,
  onPageChange,
  onUseRepository,
  onCreateAndRunCandidate,
}: {
  repositories: GithubRepository[]
  total: number | null
  page: number
  totalPages: number
  searching: boolean
  selectedRepository: GithubRepository | null
  onSelectRepository: (repo: GithubRepository | null) => void
  onPageChange: (page: number) => void
  onUseRepository: (repo: GithubRepository) => void
  onCreateAndRunCandidate: (candidate: GithubPullCandidate) => Promise<void>
}) {
  const [dialogRepository, setDialogRepository] = useState<GithubRepository | null>(null)

  return (
    <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_360px]">
      <div className="rounded-lg border border-terminal bg-white p-4 shadow-sm">
        <div className="mb-3 flex items-center justify-between gap-2">
          <div className="flex items-center gap-2">
            <Icon icon="mdi:format-list-bulleted" className="h-5 w-5 text-cyan" />
            <h3 className="text-sm font-bold text-text-primary">候选项目</h3>
          </div>
          <span className="text-xs text-text-muted">
            {total === null ? '未搜索' : `${total.toLocaleString()} 条`}
          </span>
        </div>
        <div className="max-h-[560px] space-y-2 overflow-y-auto pr-1 custom-scrollbar">
          {repositories.length === 0 ? (
            <EmptyState icon="mdi:github" text="点击搜索后在这里查看候选项目" />
          ) : (
            repositories.map((repo) => (
              <button
                key={repo.githubId}
                type="button"
                onClick={() => onSelectRepository(repo)}
                className={cn(
                  'block w-full rounded-lg border p-3 text-left transition-colors',
                  selectedRepository?.githubId === repo.githubId
                    ? 'border-cyan bg-primary-50'
                    : 'border-terminal bg-slate-50 hover:border-cyan hover:bg-primary-50'
                )}
              >
                <div className="flex items-start justify-between gap-2">
                  <div className="min-w-0">
                    <div className="truncate text-sm font-bold text-cyan">{repo.fullName}</div>
                    <p className="mt-1 line-clamp-2 text-xs text-text-secondary">
                      {repo.description || '暂无描述'}
                    </p>
                  </div>
                  <ScoreBadge grade={repo.candidateGrade} score={repo.productionScore} />
                </div>
                <div className="mt-2 flex flex-wrap gap-2 text-[11px] text-text-secondary">
                  <RepoMetric icon="mdi:star-outline" value={repo.stargazersCount ?? 0} />
                  <RepoMetric icon="mdi:source-fork" value={repo.forksCount ?? 0} />
                  <span className="rounded-full bg-white px-2 py-1">{repo.language || '-'}</span>
                </div>
              </button>
            ))
          )}
        </div>
        {total !== null && (
          <div className="mt-3 flex items-center justify-between gap-2">
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={searching || page <= 1}
              onClick={() => onPageChange(page - 1)}
            >
              上一页
            </Button>
            <span className="text-xs text-text-secondary">第 {page} / {totalPages} 页</span>
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={searching || page >= totalPages || repositories.length === 0}
              onClick={() => onPageChange(page + 1)}
            >
              下一页
            </Button>
          </div>
        )}
      </div>

      <div className="rounded-lg border border-terminal bg-white p-4 shadow-sm">
        {selectedRepository ? (
          <>
            <div className="mb-3 flex items-start justify-between gap-2">
              <div className="min-w-0">
                <h3 className="truncate text-sm font-bold text-text-primary">
                  {selectedRepository.fullName}
                </h3>
                <p className="mt-1 line-clamp-3 text-xs leading-5 text-text-secondary">
                  {selectedRepository.description || '暂无描述'}
                </p>
              </div>
              <ScoreBadge
                grade={selectedRepository.candidateGrade}
                score={selectedRepository.productionScore}
              />
            </div>
            <div className="grid gap-2">
              <Meta label="Stars" value={(selectedRepository.stargazersCount ?? 0).toLocaleString()} />
              <Meta label="Forks" value={(selectedRepository.forksCount ?? 0).toLocaleString()} />
              <Meta label="Open Issues" value={(selectedRepository.openIssuesCount ?? 0).toLocaleString()} />
              <Meta label="最近 Push" value={formatDate(selectedRepository.pushedAt)} />
            </div>
            <p className="mt-3 text-xs leading-5 text-text-primary">
              {selectedRepository.gradeReason || '暂无评分结论'}
            </p>
            <div className="mt-4 grid gap-2">
              <Button type="button" size="sm" onClick={() => setDialogRepository(selectedRepository)}>
                扫描 merged PR
              </Button>
              <Button type="button" variant="outline" size="sm" onClick={() => onUseRepository(selectedRepository)}>
                填入任务表单
              </Button>
            </div>
          </>
        ) : (
          <EmptyState icon="mdi:cursor-default-click-outline" text="选择左侧项目查看详情" />
        )}
      </div>

      {dialogRepository && (
        <RepositoryScoreDialog
          repository={dialogRepository}
          onClose={() => setDialogRepository(null)}
          onUse={() => {
            onUseRepository(dialogRepository)
            setDialogRepository(null)
          }}
          onCreateAndRunCandidate={onCreateAndRunCandidate}
        />
      )}
    </div>
  )
}

function AllowedRepoRegistryPanel({
  repositories,
  loading,
  exporting,
  page,
  perPage,
  total,
  totalPages,
  language,
  candidateFilter,
  onLanguageChange,
  onCandidateFilterChange,
  onRefresh,
  onPageChange,
  onPerPageChange,
  onExport,
}: {
  repositories: SweAllowedRepo[]
  loading: boolean
  exporting: boolean
  page: number
  perPage: number
  total: number
  totalPages: number
  language: string
  candidateFilter: string
  onLanguageChange: (language: string) => void
  onCandidateFilterChange: (filter: string) => void
  onRefresh: () => void
  onPageChange: (page: number) => void
  onPerPageChange: (perPage: number) => void
  onExport: () => void
}) {
  return (
    <div className="rounded-lg border border-terminal bg-white p-4 shadow-sm">
      <div className="mb-4 flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
        <div className="flex items-center gap-2">
          <Icon icon="mdi:shield-check-outline" className="h-5 w-5 text-cyan" />
          <div>
            <h3 className="text-sm font-bold text-text-primary">SCA 放行仓库</h3>
            <p className="mt-1 text-xs text-text-secondary">
              compatibility_status=ALLOW · {total.toLocaleString()} 条
            </p>
          </div>
        </div>
        <div className="grid gap-2 sm:grid-cols-[150px_150px_96px_96px_auto]">
          <select
            className="input-base h-9 text-xs"
            value={language}
            onChange={(event) => onLanguageChange(event.target.value)}
          >
            {SCA_LANGUAGE_OPTIONS.map((item) => (
              <option key={item.value} value={item.value}>
                {item.label}
              </option>
            ))}
          </select>
          <select
            className="input-base h-9 text-xs"
            value={candidateFilter}
            onChange={(event) => onCandidateFilterChange(event.target.value)}
          >
            <option value="all">全部候选状态</option>
            <option value="in">已入候选</option>
            <option value="not_in">未入候选</option>
          </select>
          <select
            className="input-base h-9 text-xs"
            value={perPage}
            onChange={(event) => onPerPageChange(Number(event.target.value))}
          >
            <option value={10}>10/页</option>
            <option value={20}>20/页</option>
            <option value={50}>50/页</option>
            <option value={100}>100/页</option>
          </select>
          <Button type="button" variant="outline" size="sm" disabled={loading} onClick={onRefresh}>
            刷新
          </Button>
          <Button
            type="button"
            size="sm"
            disabled={exporting || total === 0}
            className="inline-flex items-center justify-center gap-2"
            onClick={onExport}
          >
            <Icon icon="mdi:download" className="h-4 w-4" />
            {exporting ? '导出中' : '导出'}
          </Button>
        </div>
      </div>

      <div className="overflow-x-auto">
        <table className="min-w-full text-left text-xs">
          <thead className="border-b border-terminal text-text-muted">
            <tr>
              <th className="whitespace-nowrap px-3 py-2 font-bold">仓库</th>
              <th className="whitespace-nowrap px-3 py-2 font-bold">语言</th>
              <th className="whitespace-nowrap px-3 py-2 font-bold">Stars</th>
              <th className="whitespace-nowrap px-3 py-2 font-bold">许可证</th>
              <th className="whitespace-nowrap px-3 py-2 font-bold">候选</th>
              <th className="whitespace-nowrap px-3 py-2 font-bold">检查时间</th>
            </tr>
          </thead>
          <tbody>
            {repositories.length === 0 ? (
              <tr>
                <td colSpan={6} className="px-3 py-8">
                  <EmptyState icon={loading ? 'mdi:loading' : 'mdi:database-search'} text={loading ? '加载中' : '暂无 SCA 放行仓库'} />
                </td>
              </tr>
            ) : (
              repositories.map((repo) => (
                <tr key={repo.id} className="border-b border-terminal/70 last:border-0">
                  <td className="max-w-[360px] px-3 py-3">
                    <a
                      href={repo.githubUrl}
                      target="_blank"
                      rel="noreferrer"
                      className="block truncate font-bold text-cyan hover:underline"
                    >
                      {repo.repo}
                    </a>
                  </td>
                  <td className="whitespace-nowrap px-3 py-3 text-text-secondary">
                    {repo.primaryLanguage || 'unknown'}
                  </td>
                  <td className="whitespace-nowrap px-3 py-3 text-text-secondary">
                    {(repo.githubStars ?? 0).toLocaleString()}
                  </td>
                  <td className="whitespace-nowrap px-3 py-3">
                    <span className="rounded-full bg-emerald-50 px-2 py-1 font-bold text-emerald-700">
                      {repo.licenseSpdxId || '-'}
                    </span>
                  </td>
                  <td className="whitespace-nowrap px-3 py-3">
                    <span
                      className={cn(
                        'rounded-full px-2 py-1 font-bold',
                        repo.inCandidate
                          ? 'bg-blue-50 text-blue-700'
                          : 'bg-slate-100 text-slate-600'
                      )}
                    >
                      {repo.inCandidate ? '已入库' : '未入库'}
                    </span>
                  </td>
                  <td className="whitespace-nowrap px-3 py-3 text-text-secondary">
                    {formatDate(repo.checkedAt)}
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      <div className="mt-3 flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
        <span className="text-xs text-text-secondary">
          第 {page} / {totalPages} 页
        </span>
        <div className="flex gap-2">
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={loading || page <= 1}
            onClick={() => onPageChange(page - 1)}
          >
            上一页
          </Button>
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={loading || page >= totalPages || repositories.length === 0}
            onClick={() => onPageChange(page + 1)}
          >
            下一页
          </Button>
        </div>
      </div>
    </div>
  )
}

function RepositoryScoreDialog({
  repository,
  onClose,
  onUse,
  onCreateAndRunCandidate,
}: {
  repository: GithubRepository
  onClose: () => void
  onUse: () => void
  onCreateAndRunCandidate: (candidate: GithubPullCandidate) => Promise<void>
}) {
  const [pullScan, setPullScan] = useState<GithubPullScanResponse | null>(null)
  const [scanLoading, setScanLoading] = useState(false)
  const [scanError, setScanError] = useState<string | null>(null)
  const [scanMinSourceFiles, setScanMinSourceFiles] = useState('5')
  const [scanMaxSourceFiles, setScanMaxSourceFiles] = useState('10')
  const [scanMinGoldLines, setScanMinGoldLines] = useState('108')
  const [scanMaxGoldLines, setScanMaxGoldLines] = useState('300')

  function mergePullScan(
    previous: GithubPullScanResponse | null,
    next: GithubPullScanResponse
  ): GithubPullScanResponse {
    if (!previous) return next
    const candidatesByUrl = new Map<string, GithubPullCandidate>()
    for (const candidate of previous.candidates ?? []) {
      candidatesByUrl.set(candidate.prUrl, candidate)
    }
    for (const candidate of next.candidates ?? []) {
      candidatesByUrl.set(candidate.prUrl, candidate)
    }
    return {
      ...next,
      scannedPulls: (previous.scannedPulls ?? 0) + (next.scannedPulls ?? 0),
      mergedPulls: (previous.mergedPulls ?? 0) + (next.mergedPulls ?? 0),
      skippedUnmerged: (previous.skippedUnmerged ?? 0) + (next.skippedUnmerged ?? 0),
      skippedOutOfRange: (previous.skippedOutOfRange ?? 0) + (next.skippedOutOfRange ?? 0),
      skippedByFilter: (previous.skippedByFilter ?? 0) + (next.skippedByFilter ?? 0),
      skippedDelivered: (previous.skippedDelivered ?? 0) + (next.skippedDelivered ?? 0),
      candidates: Array.from(candidatesByUrl.values()).sort(
        (a, b) => (b.score ?? 0) - (a.score ?? 0)
      ),
    }
  }

  async function handleScanPulls(reset = true) {
    setScanLoading(true)
    setScanError(null)
    try {
      const minGoldSourceFiles = parseOptionalNumber(scanMinSourceFiles)
      const maxGoldSourceFiles = parseOptionalNumber(scanMaxSourceFiles)
      const minGoldLines = parseOptionalNumber(scanMinGoldLines)
      const maxGoldLines = parseOptionalNumber(scanMaxGoldLines)
      if (
        minGoldSourceFiles !== undefined &&
        maxGoldSourceFiles !== undefined &&
        maxGoldSourceFiles < minGoldSourceFiles
      ) {
        throw new Error('源码文件数量上限不能小于下限')
      }
      if (minGoldLines !== undefined && maxGoldLines !== undefined && maxGoldLines < minGoldLines) {
        throw new Error('Gold 行数上限不能小于下限')
      }
      let accumulated = reset ? null : pullScan
      let nextPage = reset ? 1 : accumulated?.nextPage ?? 1
      for (let batch = 0; batch < PR_SCAN_BATCHES_PER_CLICK; batch += 1) {
        const response = await scanGithubMergedPullCandidates({
          repo: repository.fullName,
          limit: PR_SCAN_TARGET_CANDIDATES,
          days: 365,
          page: nextPage,
          perPage: PR_SCAN_BATCH_SIZE,
          minGoldSourceFiles,
          maxGoldSourceFiles,
          minGoldLines,
          maxGoldLines,
        })
        accumulated = mergePullScan(accumulated, response)
        setPullScan(accumulated)
        if (
          response.hasMore === false ||
          accumulated.candidates.length >= PR_SCAN_TARGET_CANDIDATES
        ) {
          break
        }
        nextPage = response.nextPage ?? nextPage + 1
      }
    } catch (requestError) {
      setScanError(requestError instanceof Error ? requestError.message : '扫描 merged PR 失败')
    } finally {
      setScanLoading(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-4">
      <motion.div
        initial={{ opacity: 0, scale: 0.98, y: 8 }}
        animate={{ opacity: 1, scale: 1, y: 0 }}
        className="max-h-[86vh] w-full max-w-2xl overflow-y-auto rounded-lg border border-terminal bg-white p-5 shadow-2xl custom-scrollbar"
      >
        <div className="mb-4 flex items-start justify-between gap-3">
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2">
              <h3 className="truncate text-lg font-bold text-text-primary">
                {repository.fullName}
              </h3>
              <ScoreBadge
                grade={repository.candidateGrade}
                score={repository.productionScore}
              />
            </div>
            <p className="mt-2 text-sm leading-6 text-text-secondary">
              {repository.description || '暂无描述'}
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-lg hover:bg-tertiary/70"
            aria-label="关闭"
          >
            <Icon icon="mdi:close" className="h-5 w-5 text-text-secondary" />
          </button>
        </div>

        <div className="grid gap-3 md:grid-cols-4">
          <Meta label="Stars" value={(repository.stargazersCount ?? 0).toLocaleString()} />
          <Meta label="Forks" value={(repository.forksCount ?? 0).toLocaleString()} />
          <Meta label="Open Issues" value={(repository.openIssuesCount ?? 0).toLocaleString()} />
          <Meta label="最近 Push" value={formatDate(repository.pushedAt)} />
        </div>

        <div className="mt-4 rounded-lg border border-terminal bg-slate-50 p-3">
          <div className="mb-2 text-xs font-bold text-text-secondary">评分结论</div>
          <p className="text-sm leading-6 text-text-primary">
            {repository.gradeReason || '暂无评分结论'}
          </p>
        </div>

        <div className="mt-4 grid gap-3 md:grid-cols-2">
          <ScoreList
            title="优势信号"
            icon="mdi:check-circle-outline"
            items={repository.strengths ?? []}
            emptyText="暂无优势信号"
          />
          <ScoreList
            title="风险信号"
            icon="mdi:alert-circle-outline"
            items={repository.risks ?? []}
            emptyText="暂无明显风险"
          />
        </div>

        <div className="mt-4 rounded-lg border border-terminal bg-white p-3">
          <div className="mb-2 text-xs font-bold text-text-secondary">预检计划</div>
          <p className="text-sm leading-6 text-text-primary">
            {repository.precheckPlan || '进入 merged PR 扫描后继续按 diff 规模评分'}
          </p>
        </div>

        <div className="mt-4 rounded-lg border border-terminal bg-white p-3">
          <div className="mb-3 flex flex-col gap-2">
            <div className="flex items-center justify-between gap-3">
              <div className="text-xs font-bold text-text-secondary">Merged PR 候选扫描</div>
              <div className="flex shrink-0 gap-2">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  className="whitespace-nowrap"
                  onClick={() => handleScanPulls(true)}
                  disabled={scanLoading}
                >
                  重新扫描
                </Button>
                <Button
                  type="button"
                  size="sm"
                  className="whitespace-nowrap"
                  onClick={() => handleScanPulls(!pullScan)}
                  disabled={scanLoading || (!!pullScan && pullScan.hasMore === false)}
                >
                  {scanLoading ? '扫描中' : pullScan ? '继续扫描' : '扫描 merged PR'}
                </Button>
              </div>
            </div>
            <div className="min-h-5">
              {pullScan && (
                <div className="text-[11px] leading-5 text-text-muted">
                  已扫描 {pullScan.scannedPulls} 个 closed PR，命中 {pullScan.candidates.length} 个候选，筛掉 {pullScan.skippedByFilter} 个，已交付过滤 {pullScan.skippedDelivered ?? 0} 个
                </div>
              )}
            </div>
          </div>
          <div className="mb-3 grid grid-cols-2 gap-2 md:grid-cols-4">
            <label className="block">
              <span className="mb-1 block text-[11px] font-medium text-text-secondary">源码文件下限</span>
              <input
                type="number"
                min={0}
                className="input-base h-9 text-xs"
                value={scanMinSourceFiles}
                onChange={(event) => setScanMinSourceFiles(event.target.value)}
              />
            </label>
            <label className="block">
              <span className="mb-1 block text-[11px] font-medium text-text-secondary">源码文件上限</span>
              <input
                type="number"
                min={0}
                className="input-base h-9 text-xs"
                value={scanMaxSourceFiles}
                onChange={(event) => setScanMaxSourceFiles(event.target.value)}
              />
            </label>
            <label className="block">
              <span className="mb-1 block text-[11px] font-medium text-text-secondary">Gold 行数下限</span>
              <input
                type="number"
                min={0}
                className="input-base h-9 text-xs"
                value={scanMinGoldLines}
                onChange={(event) => setScanMinGoldLines(event.target.value)}
              />
            </label>
            <label className="block">
              <span className="mb-1 block text-[11px] font-medium text-text-secondary">Gold 行数上限</span>
              <input
                type="number"
                min={0}
                className="input-base h-9 text-xs"
                value={scanMaxGoldLines}
                onChange={(event) => setScanMaxGoldLines(event.target.value)}
              />
            </label>
          </div>
          {scanError && (
            <div className="mb-3 rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-xs text-rose-700">
              {scanError}
            </div>
          )}
          {pullScan ? (
            <>
              <div className="mb-3 rounded-lg border border-terminal bg-slate-50 px-3 py-2 text-[11px] text-text-secondary">
                当前已扫到第 {Math.max((pullScan.nextPage ?? 2) - 1, 1)} 批，每批 {PR_SCAN_BATCH_SIZE} 个 closed PR。
                {pullScan.hasMore === false ? ' 没有更多最近范围内的 PR。' : ' 可继续扫描后续批次。'}
              </div>
              <PullCandidateList
                candidates={pullScan.candidates}
                onCreateAndRunCandidate={onCreateAndRunCandidate}
              />
            </>
          ) : (
            <p className="text-xs leading-5 text-text-secondary">
              每批扫描 {PR_SCAN_BATCH_SIZE} 个 closed PR，一次点击最多连续扫描 {PR_SCAN_BATCHES_PER_CLICK} 批；默认只保留最近 365 天、源码文件 5 到 10 个、Gold 行数 108 到 300 行的候选。
            </p>
          )}
        </div>

        <div className="mt-5 flex flex-col gap-2 sm:flex-row sm:justify-between">
          <a
            href={repository.htmlUrl}
            target="_blank"
            rel="noreferrer"
            className="inline-flex h-9 items-center justify-center gap-2 rounded-lg border border-terminal px-3 text-sm font-bold text-text-primary hover:bg-tertiary/50"
          >
            <Icon icon="mdi:open-in-new" className="h-4 w-4 text-cyan" />
            打开 GitHub
          </a>
          <div className="flex gap-2">
            <Button type="button" variant="outline" size="sm" onClick={onClose}>
              取消
            </Button>
            <Button type="button" size="sm" onClick={onUse}>
              使用该项目
            </Button>
          </div>
        </div>
      </motion.div>
    </div>
  )
}

function PullCandidateList({
  candidates,
  onCreateAndRunCandidate,
}: {
  candidates: GithubPullCandidate[]
  onCreateAndRunCandidate: (candidate: GithubPullCandidate) => Promise<void>
}) {
  const [processingId, setProcessingId] = useState<number | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)

  if (candidates.length === 0) {
    return <EmptyState icon="mdi:source-pull" text="未发现 merged PR 候选" />
  }

  return (
    <div className="max-h-[360px] space-y-2 overflow-y-auto pr-1 custom-scrollbar">
      {actionError && (
        <div className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-xs text-rose-700">
          {actionError}
        </div>
      )}
      {candidates.map((candidate) => (
        <div key={candidate.candidateId} className="rounded-lg border border-terminal bg-slate-50 p-3">
          <div className="flex items-start justify-between gap-2">
            <div className="min-w-0">
              <a
                href={candidate.prUrl}
                target="_blank"
                rel="noreferrer"
                className="block truncate text-sm font-bold text-cyan hover:underline"
              >
                #{candidate.number} {candidate.title || 'Untitled PR'}
              </a>
              <p className="mt-1 text-[11px] text-text-muted">
                merged {formatDate(candidate.mergedAt)} · {candidate.primaryLanguage || 'unknown'}
              </p>
            </div>
            <ScoreBadge grade={candidate.candidateGrade} score={candidate.score} />
          </div>
          <div className="mt-3 grid grid-cols-2 gap-2 text-[11px] text-text-secondary md:grid-cols-4">
            <Meta label="Gold 行数" value={String(candidate.goldTotalChanged ?? 0)} />
            <Meta label="Gold 文件" value={String(candidate.goldPatchFiles ?? 0)} />
            <Meta label="源码文件" value={String(candidate.goldSourceFiles ?? 0)} />
            <Meta label="测试行数" value={String(candidate.testTotalChanged ?? 0)} />
          </div>
          <p className="mt-3 text-xs leading-5 text-text-primary">
            {candidate.gradeReason || '-'}
          </p>
          {candidate.duplicateStatus && (
            <p className="mt-1 text-[11px] text-text-muted">
              去重状态：{candidate.duplicateStatus}
            </p>
          )}
          <div className="mt-2 flex flex-wrap gap-2">
            {(candidate.strengths ?? []).slice(0, 4).map((item) => (
              <span key={item} className="rounded-full bg-white px-2 py-1 text-[11px] text-emerald-700">
                {item}
              </span>
            ))}
            {(candidate.risks ?? []).slice(0, 4).map((item) => (
              <span key={item} className="rounded-full bg-white px-2 py-1 text-[11px] text-rose-700">
                {item}
              </span>
            ))}
          </div>
          <div className="mt-3 flex justify-end">
            <Button
              type="button"
              size="sm"
              disabled={!candidate.id || processingId === candidate.id}
              onClick={async () => {
                if (!candidate.id) return
                setActionError(null)
                setProcessingId(candidate.id)
                try {
                  await onCreateAndRunCandidate(candidate)
                } catch (requestError) {
                  setActionError(
                    requestError instanceof Error ? requestError.message : '创建候选任务失败'
                  )
                } finally {
                  setProcessingId(null)
                }
              }}
            >
              {processingId === candidate.id ? '启动中' : '创建并启动全流程'}
            </Button>
          </div>
        </div>
      ))}
    </div>
  )
}

function CandidateRegistryPanel({
  candidates,
  loading,
  page,
  perPage,
  total,
  totalPages,
  selectedCandidateId,
  onSelectCandidate,
  onRefresh,
  onPageChange,
  onPerPageChange,
}: {
  candidates: GithubPullCandidate[]
  loading: boolean
  page: number
  perPage: number
  total: number
  totalPages: number
  selectedCandidateId: number | null
  onSelectCandidate: (candidateId: number | null) => void
  onRefresh: () => Promise<void>
  onPageChange: (page: number) => Promise<void>
  onPerPageChange: (perPage: number) => Promise<void>
}) {
  return (
    <div className="rounded-lg border border-terminal bg-white p-3 shadow-sm">
      <div className="mb-3 flex items-center justify-between gap-2">
        <div className="flex min-w-0 items-center gap-2">
          <Icon icon="mdi:database-search-outline" className="h-5 w-5 text-cyan" />
          <h3 className="text-sm font-bold text-text-primary">候选 PR 库</h3>
        </div>
        <Button type="button" variant="outline" size="sm" disabled={loading} onClick={() => onRefresh()}>
          {loading ? '刷新中' : '刷新'}
        </Button>
      </div>
      <div className="mb-3 grid grid-cols-[minmax(0,1fr)_92px] items-end gap-2">
        <div className="text-xs text-text-muted">
          共 {total.toLocaleString()} 条 · 第 {page} / {totalPages} 页
        </div>
        <label className="block">
          <span className="mb-1 block text-[11px] font-medium text-text-secondary">每页</span>
          <select
            className="input-base h-9 text-xs"
            value={perPage}
            disabled={loading}
            onChange={(event) => onPerPageChange(Number(event.target.value))}
          >
            <option value={5}>5</option>
            <option value={10}>10</option>
            <option value={20}>20</option>
            <option value={50}>50</option>
          </select>
        </label>
      </div>
      <div className="max-h-[420px] space-y-2 overflow-y-auto pr-1 custom-scrollbar">
        {candidates.length === 0 ? (
          <EmptyState icon="mdi:database-outline" text="暂无入库候选，先扫描 merged PR" />
        ) : (
          candidates.map((candidate) => {
            return (
              <div
                key={candidate.id ?? candidate.candidateId}
                className={cn(
                  'rounded-lg border p-3 transition-colors',
                  selectedCandidateId === candidate.id
                    ? 'border-cyan bg-primary-50'
                    : 'border-terminal bg-slate-50'
                )}
                onClick={() => onSelectCandidate(candidate.id ?? null)}
              >
                <div className="flex items-start justify-between gap-2">
                  <div className="min-w-0">
                    <a
                      href={candidate.prUrl}
                      target="_blank"
                      rel="noreferrer"
                      className="block truncate text-sm font-bold text-cyan hover:underline"
                    >
                      #{candidate.number} {candidate.title || 'Untitled PR'}
                    </a>
                    <p className="mt-1 truncate text-[11px] text-text-muted">
                      {candidate.repo} · {candidate.primaryLanguage || 'unknown'}
                    </p>
                  </div>
                  <ScoreBadge grade={candidate.candidateGrade} score={candidate.score} />
                </div>
                <div className="mt-2 flex flex-wrap gap-2 text-[11px] text-text-secondary">
                  <span className="rounded-full bg-white px-2 py-1">#{candidate.id ?? '-'}</span>
                  <span className="rounded-full bg-white px-2 py-1">{candidate.candidateStatus || '-'}</span>
                  <span className="rounded-full bg-white px-2 py-1">{candidate.duplicateStatus || '-'}</span>
                  <span className="rounded-full bg-white px-2 py-1">Gold {candidate.goldTotalChanged ?? 0}</span>
                  <span className="rounded-full bg-white px-2 py-1">文件 {candidate.goldSourceFiles ?? 0}</span>
                </div>
              </div>
            )
          })
        )}
      </div>
      <div className="mt-3 flex items-center justify-between gap-2">
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={loading || page <= 1}
          onClick={() => onPageChange(page - 1)}
        >
          上一页
        </Button>
        <span className="text-xs text-text-secondary">
          {candidates.length} / {total.toLocaleString()}
        </span>
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={loading || page >= totalPages || candidates.length === 0}
          onClick={() => onPageChange(page + 1)}
        >
          下一页
        </Button>
      </div>
    </div>
  )
}

function ScoreList({
  title,
  icon,
  items,
  emptyText,
}: {
  title: string
  icon: string
  items: string[]
  emptyText: string
}) {
  return (
    <div className="rounded-lg border border-terminal bg-slate-50 p-3">
      <div className="mb-2 flex items-center gap-2 text-xs font-bold text-text-secondary">
        <Icon icon={icon} className="h-4 w-4 text-cyan" />
        {title}
      </div>
      <div className="flex flex-wrap gap-2">
        {items.length === 0 ? (
          <span className="text-xs text-text-muted">{emptyText}</span>
        ) : (
          items.map((item) => (
            <span key={item} className="rounded-full bg-white px-2 py-1 text-xs text-text-secondary">
              {item}
            </span>
          ))
        )}
      </div>
    </div>
  )
}

function ScoreBadge({
  grade,
  score,
}: {
  grade?: 'A' | 'B' | 'C'
  score?: number
}) {
  const resolvedGrade = grade ?? 'C'
  const className = {
    A: 'border-emerald-200 bg-emerald-50 text-emerald-700',
    B: 'border-amber-200 bg-amber-50 text-amber-700',
    C: 'border-rose-200 bg-rose-50 text-rose-700',
  }[resolvedGrade]
  return (
    <span className={cn('inline-flex flex-shrink-0 items-center gap-1 rounded-full border px-2 py-1 text-[11px] font-bold', className)}>
      {resolvedGrade}
      <span>{score ?? 0}</span>
    </span>
  )
}

function RepoMetric({ icon, value }: { icon: string; value: number }) {
  return (
    <span className="inline-flex items-center gap-1 rounded-full bg-white px-2 py-1">
      <Icon icon={icon} className="h-3.5 w-3.5 text-cyan" />
      {value.toLocaleString()}
    </span>
  )
}

function TaskCreatePanel({
  form,
  loading,
  onChange,
  onSubmit,
}: {
  form: SweTaskCreateRequest
  loading: boolean
  onChange: (value: SweTaskCreateRequest) => void
  onSubmit: (event: FormEvent<HTMLFormElement>) => void
}) {
  const [expanded, setExpanded] = useState(false)

  return (
    <div className="rounded-lg border border-dashed border-terminal/70 bg-white p-3 shadow-sm">
      <div className="flex items-center justify-between gap-3">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <Icon icon="mdi:folder-plus-outline" className="h-4 w-4 text-text-secondary" />
            <h3 className="truncate text-sm font-bold text-text-primary">从本地文件夹创建任务</h3>
          </div>
          <p className="mt-1 text-xs text-text-tertiary">辅助入口，主流程建议从候选 PR 创建任务</p>
        </div>
        <button
          type="button"
          className="inline-flex shrink-0 items-center gap-1 rounded-md border border-terminal bg-panel px-2.5 py-1.5 text-xs font-bold text-text-secondary transition hover:border-cyan hover:text-cyan"
          onClick={() => setExpanded((value) => !value)}
        >
          <Icon icon={expanded ? 'mdi:chevron-up' : 'mdi:chevron-down'} className="h-4 w-4" />
          {expanded ? '收起' : '展开'}
        </button>
      </div>

      {expanded && (
        <form onSubmit={onSubmit} className="mt-4 border-t border-terminal pt-4">
          <div className="space-y-3">
            <Field
              label="任务名称"
              value={form.taskName}
              onChange={(taskName) => onChange({ ...form, taskName })}
              required
            />
            <Field
              label="样本成品目录"
              value={form.samplePath ?? ''}
              onChange={(samplePath) => onChange({ ...form, samplePath })}
            />
            <Field
              label="Repo"
              value={form.repo ?? ''}
              placeholder="留空时从 task.json 读取"
              onChange={(repo) => onChange({ ...form, repo })}
            />
            <Field
              label="来源 URL"
              value={form.sourceUrl ?? ''}
              onChange={(sourceUrl) => onChange({ ...form, sourceUrl })}
            />
          </div>
          <Button type="submit" size="sm" disabled={loading} className="mt-4 w-full">
            {loading ? '处理中' : '保存任务'}
          </Button>
        </form>
      )}
    </div>
  )
}

function Field({
  label,
  value,
  placeholder,
  required,
  onChange,
}: {
  label: string
  value: string
  placeholder?: string
  required?: boolean
  onChange: (value: string) => void
}) {
  return (
    <label className="block">
      <span className="mb-1 block text-xs font-medium text-text-secondary">{label}</span>
      <input
        className="input-base h-10 text-sm"
        value={value}
        required={required}
        placeholder={placeholder}
        onChange={(event) => onChange(event.target.value)}
      />
    </label>
  )
}

function TaskList({
  tasks,
  selectedTaskId,
  onSelectTask,
}: {
  tasks: SweTask[]
  selectedTaskId: number | null
  onSelectTask: (taskId: number) => void
}) {
  return (
    <div className="rounded-lg border border-terminal bg-white p-3 shadow-sm">
      <div className="mb-3 flex items-center justify-between">
        <h3 className="text-sm font-bold text-text-primary">任务列表</h3>
        <span className="text-xs text-text-muted">{tasks.length} 个任务</span>
      </div>
      <div className="max-h-[360px] space-y-2 overflow-y-auto pr-1 custom-scrollbar">
        {tasks.length === 0 ? (
          <EmptyState icon="mdi:folder-outline" text="暂无任务" />
        ) : (
          tasks.map((task) => (
            <button
              key={task.id}
              type="button"
              onClick={() => onSelectTask(task.id)}
              className={cn(
                'w-full rounded-lg border p-3 text-left transition-colors',
                selectedTaskId === task.id
                  ? 'border-cyan bg-primary-50'
                  : 'border-terminal bg-white hover:bg-tertiary/40'
              )}
            >
              <div className="flex items-center justify-between gap-2">
                <span className="truncate text-sm font-bold text-text-primary">{task.taskName}</span>
                <StatusBadge status={task.status} />
              </div>
              <div className="mt-2 truncate text-xs text-text-secondary">{task.repo || 'repo 未填写'}</div>
            </button>
          ))
        )}
      </div>
    </div>
  )
}

function TaskOverview({ task }: { task: SweTask | null }) {
  if (!task) {
    return (
      <div className="rounded-lg border border-terminal bg-white p-5 shadow-sm">
        <EmptyState icon="mdi:source-branch" text="选择任务后查看验收元数据" />
      </div>
    )
  }

  return (
    <div className="rounded-lg border border-terminal bg-white p-4 shadow-sm">
      <div className="mb-3 flex flex-col gap-2 lg:flex-row lg:items-center lg:justify-between">
        <div>
          <h3 className="text-base font-bold text-text-primary">{task.taskName}</h3>
          <p className="mt-1 text-xs text-text-secondary">{task.repo}</p>
        </div>
        <StatusBadge status={task.status} />
      </div>
      <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
        <Meta label="候选ID" value={task.candidateId ? `#${task.candidateId}` : '-'} />
        <Meta label="语言" value={task.repoLanguage || '-'} />
        <Meta label="Base Commit" value={shorten(task.baseCommit)} />
        <Meta label="Fix Commit" value={shorten(task.fixCommit)} />
      </div>
      <div className="mt-3 grid gap-3 md:grid-cols-2 xl:grid-cols-4">
        <Meta label="样本目录" value={task.samplePath || '-'} />
      </div>
      <div className="mt-3 grid gap-3 md:grid-cols-2">
        <Meta label="Issue Specificity" value={task.issueSpecificity || '-'} />
        <Meta label="Issue Categories" value={task.issueCategories || '-'} />
      </div>
    </div>
  )
}

function RunTimeline({ run, title = '阶段进度' }: { run: SwePipelineRun | null; title?: string }) {
  return (
    <div className="rounded-lg border border-terminal bg-white p-4 shadow-sm">
      <div className="mb-4 flex items-center justify-between gap-2">
        <h3 className="text-sm font-bold text-text-primary">{title}</h3>
        {run ? <StatusBadge status={run.status} /> : null}
      </div>
      {!run ? (
        <EmptyState icon="mdi:timeline-clock-outline" text="启动或选择运行后查看阶段状态" />
      ) : (
        <div className="grid gap-2 md:grid-cols-2 xl:grid-cols-4">
          {run.stages.map((stage) => (
            <StageItem key={stage.id} stage={stage} />
          ))}
        </div>
      )}
    </div>
  )
}

function StageItem({ stage }: { stage: SweStage }) {
  return (
    <motion.div
      layout
      className="min-h-[112px] rounded-lg border border-terminal bg-slate-50 p-3"
    >
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="truncate text-xs font-bold text-text-primary">{stage.stageName}</p>
          <p className="mt-1 text-[11px] text-text-muted">{stage.stageCode}</p>
        </div>
        <StatusBadge status={stage.status} compact tooltip={stage.errorMessage} />
      </div>
      <p className="mt-3 line-clamp-2 text-xs text-text-secondary">
        {stage.errorMessage || stage.resultSummary || '等待执行'}
      </p>
    </motion.div>
  )
}

function RunList({
  runs,
  selectedRunId,
  onSelectRun,
}: {
  runs: SwePipelineRun[]
  selectedRunId: number | null
  onSelectRun: (runId: number) => void
}) {
  return (
    <div className="rounded-lg border border-terminal bg-white p-4 shadow-sm">
      <div className="mb-3 flex items-center justify-between">
        <h3 className="text-sm font-bold text-text-primary">运行记录</h3>
        <span className="text-xs text-text-muted">{runs.length} 次</span>
      </div>
      <div className="max-h-[360px] space-y-2 overflow-y-auto pr-1 custom-scrollbar">
        {runs.length === 0 ? (
          <EmptyState icon="mdi:history" text="暂无运行记录" />
        ) : (
          runs.map((run) => (
            <button
              key={run.id}
              type="button"
              onClick={() => onSelectRun(run.id)}
              className={cn(
                'w-full rounded-lg border p-3 text-left transition-colors',
                selectedRunId === run.id
                  ? 'border-cyan bg-primary-50'
                  : 'border-terminal bg-white hover:bg-tertiary/40'
              )}
            >
              <div className="flex items-center justify-between gap-2">
                <span className="text-sm font-bold text-text-primary">Run #{run.id}</span>
                <StatusBadge status={run.status} />
              </div>
              <p className="mt-2 text-xs text-text-secondary">
                当前阶段：{run.currentStage || '-'}
              </p>
              {run.candidateId ? (
                <p className="mt-1 text-xs text-text-secondary">候选ID：#{run.candidateId}</p>
              ) : null}
            </button>
          ))
        )}
      </div>
    </div>
  )
}

function ModelIoConsole({
  run,
  console,
  loading,
  error,
  onRefresh,
}: {
  run: SwePipelineRun | null
  console: SweModelIoConsole | null
  loading: boolean
  error: string | null
  onRefresh: () => void
}) {
  const [selectedAttemptKey, setSelectedAttemptKey] = useState<string>('')
  const [activePane, setActivePane] = useState<'prompt' | 'steps' | 'raw'>('steps')
  const attempts = console?.attempts ?? []
  const selectedAttempt =
    attempts.find((attempt) => attemptKey(attempt) === selectedAttemptKey) ?? attempts[0] ?? null

  useEffect(() => {
    if (!selectedAttemptKey && attempts[0]) {
      setSelectedAttemptKey(attemptKey(attempts[0]))
    }
    if (selectedAttemptKey && attempts.length > 0 && !attempts.some((attempt) => attemptKey(attempt) === selectedAttemptKey)) {
      setSelectedAttemptKey(attemptKey(attempts[0]))
    }
  }, [attempts, selectedAttemptKey])

  return (
    <div className="rounded-lg border border-terminal bg-white p-4 shadow-sm">
      <div className="mb-3 flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <Icon icon="mdi:console" className="h-5 w-5 text-cyan" />
            <h3 className="text-sm font-bold text-text-primary">模型 I/O 控制台</h3>
            {run?.status === 'RUNNING' && (
              <span className="rounded-full border border-blue-200 bg-blue-50 px-2 py-0.5 text-[11px] font-bold text-blue-700">
                实时
              </span>
            )}
          </div>
          <p className="mt-1 truncate text-xs text-text-secondary">
            {console?.packagePath || '选择运行记录后加载模型输入输出'}
          </p>
        </div>
        <Button type="button" variant="outline" size="sm" disabled={!run || loading} onClick={onRefresh}>
          {loading ? '刷新中' : '刷新'}
        </Button>
      </div>

      {!run ? (
        <EmptyState icon="mdi:console-line" text="选择运行记录后查看模型 I/O" />
      ) : error ? (
        <div className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-xs text-rose-700">
          {error}
        </div>
      ) : (
        <div className="space-y-3">
          <div className="grid gap-2 md:grid-cols-4">
            <Meta label="Problem" value={shortPath(console?.problemStatementPath)} />
            <Meta label="Guard" value={shortPath(console?.guardConfigPath)} />
            <Meta label="Attempts" value={String(attempts.length)} />
            <Meta
              label="API Calls"
              value={String(attempts.reduce((total, attempt) => total + (attempt.rawResponseLines ?? 0), 0))}
            />
          </div>

          <div className="flex flex-col gap-2 lg:flex-row">
            <div className="flex gap-2 overflow-x-auto pb-1 lg:max-w-[52%]">
              {attempts.length === 0 ? (
                <span className="rounded-lg border border-dashed border-terminal px-3 py-2 text-xs text-text-muted">
                  等待模型 attempt
                </span>
              ) : (
                attempts.map((attempt) => (
                  <button
                    key={attemptKey(attempt)}
                    type="button"
                    onClick={() => setSelectedAttemptKey(attemptKey(attempt))}
                    className={cn(
                      'shrink-0 rounded-lg border px-3 py-2 text-left text-xs transition-colors',
                      selectedAttempt === attempt
                        ? 'border-cyan bg-primary-50 text-text-primary'
                        : 'border-terminal bg-slate-50 text-text-secondary hover:border-cyan'
                    )}
                  >
                    <div className="font-bold">#{attempt.attempt ?? '-'}</div>
                    <div className="mt-0.5 whitespace-nowrap">{attempt.status || 'running'}</div>
                    <div className="mt-0.5 whitespace-nowrap">{attempt.rawResponseLines ?? 0} calls</div>
                  </button>
                ))
              )}
            </div>

            <div className="flex flex-1 justify-start gap-2 lg:justify-end">
              {(['steps', 'prompt', 'raw'] as const).map((pane) => (
                <button
                  key={pane}
                  type="button"
                  onClick={() => setActivePane(pane)}
                  className={cn(
                    'inline-flex h-9 items-center gap-2 rounded-lg border px-3 text-xs font-bold',
                    activePane === pane
                      ? 'border-cyan bg-primary-50 text-cyan'
                      : 'border-terminal bg-white text-text-secondary hover:bg-tertiary/40'
                  )}
                >
                  <Icon
                    icon={pane === 'steps' ? 'mdi:swap-vertical' : pane === 'prompt' ? 'mdi:file-document-outline' : 'mdi:code-json'}
                    className="h-4 w-4"
                  />
                  {pane === 'steps' ? '逐步 I/O' : pane === 'prompt' ? '初始提示词' : '原始日志'}
                </button>
              ))}
            </div>
          </div>

          {selectedAttempt?.error && (
            <div className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800">
              {selectedAttempt.error}
            </div>
          )}

          {activePane === 'prompt' && (
            <ConsoleBlock title="Problem Statement" text={console?.problemStatement || ''} />
          )}
          {activePane === 'raw' && (
            <ConsoleBlock
              title={shortPath(selectedAttempt?.sweAgentOutputPath) || 'SWE-agent Output'}
              text={selectedAttempt?.sweAgentOutputTail || ''}
            />
          )}
          {activePane === 'steps' && (
            <StepIoView attempt={selectedAttempt} />
          )}
        </div>
      )}
    </div>
  )
}

function StepIoView({ attempt }: { attempt: SweModelIoAttempt | null }) {
  if (!attempt) {
    return <EmptyState icon="mdi:swap-vertical" text="等待模型调用记录" />
  }
  const maxSteps = Math.max(attempt.modelInputBlocks.length, attempt.responses.length)
  if (maxSteps === 0) {
    return <EmptyState icon="mdi:swap-vertical" text="当前 attempt 尚无模型 I/O" />
  }
  return (
    <div className="space-y-3">
      {Array.from({ length: maxSteps }).map((_, index) => {
        const response = attempt.responses[index]
        return (
          <div key={`${attemptKey(attempt)}-${index}`} className="rounded-lg border border-terminal bg-slate-50 p-3">
            <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
              <div className="text-xs font-bold text-text-primary">Step {index + 1}</div>
              {response && (
                <div className="flex flex-wrap gap-2 text-[11px] text-text-secondary">
                  <span className="rounded-full bg-white px-2 py-1">call #{response.apiCallIndex ?? index + 1}</span>
                  <span className="rounded-full bg-white px-2 py-1">{response.finishReason || '-'}</span>
                  <span className="rounded-full bg-white px-2 py-1">tokens {response.totalTokens ?? '-'}</span>
                </div>
              )}
            </div>
            <div className="grid gap-3 xl:grid-cols-2">
              <ConsoleBlock compact title="请求提示词" text={attempt.modelInputBlocks[index] || ''} />
              <ModelResponseBlock response={response} />
            </div>
          </div>
        )
      })}
    </div>
  )
}

function ModelResponseBlock({ response }: { response?: SweModelIoResponse }) {
  if (!response) {
    return <ConsoleBlock compact title="模型返回" text="" />
  }
  const usage = [
    `model: ${response.responseModel || response.configuredModel || '-'}`,
    `finish: ${response.finishReason || '-'}`,
    `prompt_tokens: ${response.promptTokens ?? '-'}`,
    `completion_tokens: ${response.completionTokens ?? '-'}`,
    `total_tokens: ${response.totalTokens ?? '-'}`,
  ].join('\n')
  return (
    <div className="space-y-2">
      <ConsoleBlock compact title="模型返回" text={response.assistantContent || ''} />
      <ConsoleBlock compact title="Usage / Raw JSON" text={`${usage}\n\n${response.rawJson || ''}`} />
    </div>
  )
}

function ConsoleBlock({
  title,
  text,
  compact = false,
}: {
  title: string
  text: string
  compact?: boolean
}) {
  return (
    <div className="min-w-0 rounded-lg border border-terminal bg-zinc-950">
      <div className="flex items-center justify-between gap-2 border-b border-zinc-800 px-3 py-2">
        <div className="truncate text-[11px] font-bold text-zinc-300" title={title}>
          {title}
        </div>
        <span className="text-[11px] text-zinc-500">{formatBytes(text ? new Blob([text]).size : 0)}</span>
      </div>
      <pre
        className={cn(
          'custom-scrollbar overflow-auto whitespace-pre-wrap break-words p-3 font-mono text-[11px] leading-5 text-zinc-100',
          compact ? 'max-h-[320px]' : 'max-h-[560px]'
        )}
      >
        {text || '暂无内容'}
      </pre>
    </div>
  )
}

function attemptKey(attempt: SweModelIoAttempt) {
  return `${attempt.evaluationName}:${attempt.attempt ?? attempt.runDir}`
}

function shortPath(value?: string) {
  if (!value) return '-'
  const parts = value.split('/')
  return parts.slice(-4).join('/')
}

function ArtifactList({ artifacts }: { artifacts: SweArtifact[] }) {
  return (
    <div className="rounded-lg border border-terminal bg-white p-4 shadow-sm">
      <div className="mb-3 flex items-center justify-between">
        <h3 className="text-sm font-bold text-text-primary">产物索引</h3>
        <span className="text-xs text-text-muted">{artifacts.length} 个文件</span>
      </div>
      <div className="max-h-[360px] overflow-y-auto custom-scrollbar">
        {artifacts.length === 0 ? (
          <EmptyState icon="mdi:archive-outline" text="暂无产物" />
        ) : (
          <table className="w-full table-fixed text-left text-xs">
            <thead className="sticky top-0 bg-white text-text-secondary">
              <tr>
                <th className="w-[130px] py-2 font-medium">类型</th>
                <th className="py-2 font-medium">名称</th>
                <th className="w-[90px] py-2 font-medium">大小</th>
              </tr>
            </thead>
            <tbody>
              {artifacts.map((artifact) => (
                <tr key={artifact.id} className="border-t border-terminal">
                  <td className="py-2 pr-2 text-cyan">{artifact.artifactType}</td>
                  <td className="py-2 pr-2">
                    <div className="truncate text-text-primary" title={artifact.artifactPath}>
                      {artifact.artifactName}
                    </div>
                  </td>
                  <td className="py-2 text-text-secondary">{formatBytes(artifact.fileSize)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  )
}

function StatusBadge({
  status,
  compact = false,
  tooltip,
}: {
  status: PipelineStatus
  compact?: boolean
  tooltip?: string
}) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 rounded-full border px-2 py-1 text-[11px] font-bold',
        tooltip && 'group relative',
        STATUS_CLASS[status]
      )}
    >
      <Icon icon={STATUS_ICON[status]} className={cn('h-3.5 w-3.5', status === 'RUNNING' && 'animate-spin')} />
      {!compact && status}
      {tooltip && (
        <span className="pointer-events-none absolute right-0 top-7 z-30 hidden w-80 rounded-lg border border-rose-200 bg-white p-3 text-left text-xs font-normal leading-5 text-rose-700 shadow-xl group-hover:block">
          {tooltip}
        </span>
      )}
    </span>
  )
}

function Meta({ label, value }: { label: string; value?: string }) {
  return (
    <div className="min-w-0 rounded-lg border border-terminal bg-slate-50 px-3 py-2">
      <div className="text-[11px] text-text-muted">{label}</div>
      <div className="mt-1 truncate text-xs font-medium text-text-primary" title={value}>
        {value || '-'}
      </div>
    </div>
  )
}

function EmptyState({ icon, text }: { icon: string; text: string }) {
  return (
    <div className="flex min-h-[96px] flex-col items-center justify-center rounded-lg border border-dashed border-terminal bg-slate-50 text-center">
      <Icon icon={icon} className="mb-2 h-7 w-7 text-text-muted" />
      <p className="text-xs text-text-muted">{text}</p>
    </div>
  )
}

function parseOptionalNumber(value: string) {
  const trimmed = value.trim()
  if (!trimmed) return undefined
  const parsed = Number(trimmed)
  return Number.isFinite(parsed) ? Math.max(parsed, 0) : undefined
}

function parseCandidateFilter(value: string) {
  if (value === 'in') return true
  if (value === 'not_in') return false
  return undefined
}

function downloadBlob(blob: Blob, filename: string) {
  const url = window.URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  window.URL.revokeObjectURL(url)
}

function shorten(value?: string) {
  if (!value) return '-'
  return value.length > 12 ? value.slice(0, 12) : value
}

function formatBytes(value?: number) {
  if (!value) return '-'
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / 1024 / 1024).toFixed(1)} MB`
}

function formatRatio(value?: number) {
  if (value === undefined || value === null) return '-'
  return `${Math.round(value * 100)}%`
}

function formatDate(value?: string) {
  if (!value) return '-'
  return new Date(value).toLocaleDateString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  })
}
