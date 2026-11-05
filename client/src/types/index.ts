// SSH 连接信息（前端本地模型）
export interface SSHConnection {
  id: string
  name: string
  host: string
  port: number
  username: string
  password?: string
  privateKey?: string
  authType: number       // 1-密码, 2-私钥
  status: number         // 0-未连接, 1-已连接, 2-连接中, 3-连接失败
  createdAt: number
  updatedAt: number
}

// AI Agent 配置（与后端 AiAgentConfigResponseDTO 对齐）
export interface AgentConfig {
  agentId: string
  agentName: string
  agentDesc: string
}

export type AgentRunStage =
  | 'queued'
  | 'thinking'
  | 'tool_running'
  | 'responding'
  | 'completed'
  | 'error'
  | 'stopped'
  | 'waiting_approval'

export type AgentToolStatus = 'pending' | 'running' | 'success' | 'error' | 'blocked' | 'unknown'
export type AgentToolKind = 'dispatch' | 'tool'

export type AgentTimelineEventType = 'status' | 'text' | 'tool_call' | 'tool_result' | 'round_end' | 'done' | 'error' | 'run_finished'

export interface AgentStepInfo {
  currentStep: number
  maxSteps?: number
  shouldContinue?: boolean
  totalToolCalls?: number
  summary?: string
  timestamp: number
}

export interface AgentToolTrace {
  id: string
  name: string
  status: AgentToolStatus
  argumentsText?: string
  resultText?: string
  sourceAgent?: string
  failureType?: string
  ruleId?: string
  rewriteAllowed?: boolean
  approvalId?: string
  kind?: AgentToolKind
  startedAt: number
  updatedAt: number
}

export interface AgentTimelineEntry {
  id: string
  type: AgentTimelineEventType
  stage: AgentRunStage
  label: string
  detail?: string
  status?: AgentToolStatus
  toolCallId?: string
  toolName?: string
  kind?: AgentToolKind
  timestamp: number
}

export interface AgentRunTrace {
  stage: AgentRunStage
  statusText: string
  startedAt: number
  updatedAt: number
  completedAt?: number
  totalSteps: number
  totalToolCalls: number
  stopReason?: string
  error?: string
  finalResponse?: string
  steps: AgentStepInfo[]
  tools: AgentToolTrace[]
  timeline: AgentTimelineEntry[]
}

// Agent 会话消息
export interface AgentMessage {
  id: string
  role: 'user' | 'assistant' | 'system'
  content: string
  timestamp: number
  trace?: AgentRunTrace
}

// Agent 会话
export interface AgentSession {
  id: string
  name: string
  connectionId?: string
  agentId?: string
  userId?: string
  backendSessionId?: string
  messages: AgentMessage[]
  createdAt: number
}

// 流式对话请求参数
export interface AgentChatPayload {
  agentId: string
  userId: string
  sessionId?: string
  message: string
  terminalSessionId?: string
}

// 服务器状态
export interface ServerStatus {
  connected: boolean
  url: string
  version?: string
}

// ===== SSH 连接状态枚举 =====
export const ConnectionStatus = {
  DISCONNECTED: 0,
  CONNECTED: 1,
  CONNECTING: 2,
  FAILED: 3,
} as const

export const AuthType = {
  PASSWORD: 1,
  PRIVATE_KEY: 2,
} as const
