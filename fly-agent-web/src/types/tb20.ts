export interface Tb20ApiResult<T> {
  code: string
  message: string
  data: T
  timestamp: number
}

export type Tb20Domain =
  | 'software-engineering'
  | 'system-administration'
  | 'security'
  | 'data-science'
  | 'scientific-computing'
  | 'file-operations'
  | 'web-network-services'
  | 'distributed-systems'
  | 'performance-optimization'
  | 'algorithms-and-formats'

export interface Tb20ConfigRequest {
  scope: 'dataset-production' | 'batch-execution-delivery'
  values?: Record<string, unknown>
}

export interface Tb20ConfigResponse {
  scope: string
  values: Record<string, string>
}

export interface Tb20DatasetRunRequest {
  domain: Tb20Domain
  sourceChannel: string
  brief?: string
  outputRoot?: string
  workspaceRoot?: string
  channelConfig?: Record<string, unknown>
}

export interface Tb20ExecutionRunRequest {
  sourceRoot: string
  outputRoot?: string
  workspaceRoot?: string
  agent?: string
  model?: string
  concurrency?: number
  failFast?: boolean
  taskPaths?: string[]
  executionConfig?: Record<string, unknown>
}

export interface Tb20RunStage {
  code: string
  name: string
  status: string
  note?: string
}

export interface Tb20RunArtifact {
  name: string
  role: string
  path: string
  present: boolean
}

export interface Tb20Run {
  runId: string
  kind: 'DATASET_PRODUCTION' | 'BATCH_EXECUTION_DELIVERY'
  status: 'RUNNING' | 'COMPLETED' | 'FAILED' | 'BLOCKED' | string
  skillName: string
  workspace: string
  outputRoot: string
  logPath?: string
  command: string[]
  stages: Tb20RunStage[]
  artifacts: Tb20RunArtifact[]
  startedAt?: string
  finishedAt?: string
  exitCode?: number
  errorMessage?: string
}

export interface Tb20DependencyStatus {
  name: string
  role: string
  configuredPath?: string
  present: boolean
  status: string
  note?: string
}

export interface Tb20Stage {
  code: string
  name: string
  automationLevel: string
  triggerMode: string
  owner: string
  input: string
  output: string
  gate: string
}

export interface Tb20Blueprint {
  standard: string
  requiredTaskFiles: string[]
  optionalDeliveryLogs: string[]
  stages: Tb20Stage[]
  nonAutomatableBoundaries: string[]
  aiScaleOutControls: string[]
  dependencies: Tb20DependencyStatus[]
}

export interface Tb20InspectRequest {
  sourceRoot: string
  outputRoot?: string
  taskPaths?: string[]
  copyTasks?: boolean
}

export interface Tb20Task {
  taskName: string
  relativePath: string
  absolutePath: string
  standardCompliant: boolean
  missingRequiredFiles: string[]
  optionalLogFilesPresent: string[]
  optionalLogFilesMissing: string[]
  difficulty?: string
  category?: string
  tags: string[]
}

export interface Tb20PipelineResponse {
  mode: string
  sourceRoot: string
  outputRoot?: string
  manifestPath?: string
  deliveryIndexPath?: string
  summary: Record<string, unknown>
  tasks: Tb20Task[]
  dependencies: Tb20DependencyStatus[]
}
