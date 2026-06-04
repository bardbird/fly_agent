export interface Tb20ApiResult<T> {
  code: string
  message: string
  data: T
  timestamp: number
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
  harborRoot?: string
  terminalBenchRoot?: string
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
  expertTimeEstimateMin?: number
  juniorTimeEstimateMin?: number
  dockerImage?: string
  cpu?: number
  memory?: string
  storage?: string
  lineCounts?: {
    instruction?: number
    tests?: number
    solution?: number
  }
  environmentFileCount?: number
  testCount?: number
  passedTestCount?: number
  failedTestCount?: number
  reward?: string | number
  agentName?: string
  modelName?: string
  trajectorySchema?: string
  trajectorySteps?: number
  agentElapsedSec?: number
  promptTokens?: number
  completionTokens?: number
  cachedTokens?: number
  contentChecksum?: string
}

export interface Tb20Summary {
  taskCount?: number
  compliantTaskCount?: number
  rewardOneCount?: number
  totalTests?: number
  totalTrajectorySteps?: number
  totalPromptTokens?: number
  totalCompletionTokens?: number
  totalCachedTokens?: number
  difficultyDistribution?: Record<string, number>
  categoryDistribution?: Record<string, number>
}

export interface Tb20PipelineResponse {
  mode: string
  sourceRoot: string
  outputRoot?: string
  manifestPath?: string
  deliveryIndexPath?: string
  summary: Tb20Summary
  tasks: Tb20Task[]
  dependencies: Tb20DependencyStatus[]
}
