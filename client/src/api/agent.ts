/**
 * AI Agent 对话 API
 * 提供智能体列表查询、会话创建、流式对话能力
 */

import { getRequestBaseUrl } from './request'
import type { AgentConfig } from '../types'
import type { AgentRunStage } from '../types'

const BASE = '/api/v1'

export type ChatExecutionMode = 'standard' | 'enhanced'
const CHAT_MODE_KEY = 'nox_shell_chat_execution_mode'

export function getChatExecutionMode(): ChatExecutionMode {
  try { return localStorage.getItem(CHAT_MODE_KEY) === 'enhanced' ? 'enhanced' : 'standard' }
  catch { return 'standard' }
}

export function setChatExecutionMode(mode: ChatExecutionMode): void {
  localStorage.setItem(CHAT_MODE_KEY, mode)
}

/** 创建会话响应 */
export interface CreateSessionResponse {
  sessionId: string
}

/** 流式对话请求 */
export interface ChatStreamPayload {
  agentId: string
  userId: string
  sessionId?: string
  message: string
  terminalSessionId?: string
  executionMode?: ChatExecutionMode
}

/** 查询智能体配置列表 */
export async function queryAgentList(): Promise<AgentConfig[]> {
  const res = await fetch(`${getRequestBaseUrl()}${BASE}/query_ai_agent_config_list`)
  if (!res.ok) {
    throw new Error(`查询智能体列表失败: ${res.statusText}`)
  }
  const json = await res.json()
  if (json.code !== '0000' || !json.data) {
    throw new Error(json.info || '查询智能体列表失败')
  }
  return json.data as AgentConfig[]
}

/** 创建对话会话 */
export async function createSession(agentId: string, userId: string): Promise<string> {
  const res = await fetch(`${getRequestBaseUrl()}${BASE}/create_session`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ agentId, userId }),
  })
  if (!res.ok) {
    throw new Error(`创建会话失败: ${res.statusText}`)
  }
  const json = await res.json()
  if (json.code !== '0000' || !json.data) {
    throw new Error(json.info || '创建会话失败')
  }
  return (json.data as CreateSessionResponse).sessionId
}

/** SSE 事件类型 */
export interface SSEStepInfo {
  currentStep: number
  maxSteps?: number
  shouldContinue?: boolean
  totalToolCalls?: number
  summary?: string
}

export interface SSEEvent {
  event: 'status' | 'text' | 'tool_call' | 'tool_result' | 'done' | 'error' | 'round_end' | 'run_finished'
  stage?: AgentRunStage
  content?: string
  fullText?: string
  toolCallId?: string
  executionId?: string
  requestId?: string
  parentExecutionId?: string | null
  executionStatus?: 'RUNNING' | 'RETURNED' | 'REPORTED_FAILURE' | 'FAILED' | 'UNKNOWN' | 'BLOCKED'
    | 'WAITING_APPROVAL'
  approvalId?: string
  failureType?: string
  ruleId?: string
  policyDecision?: string
  rewriteAllowed?: boolean
  rewritesRemaining?: number
  commandDispatched?: boolean
  attemptCount?: number
  exitCode?: number | null
  auditPersisted?: boolean
  startedAt?: number
  finishedAt?: number | null
  toolName?: string
  toolArgs?: string
  status?: string
  stepInfo?: SSEStepInfo
  totalSteps?: number
  totalToolCalls?: number
  stopReason?: string
  timestamp?: number
  author?: string
  sourceAgent?: string
  nested?: boolean
  branch?: string
  modelVersion?: string
  partial?: boolean
}

export interface CommandApproval {
  id: string
  userId: string
  sessionId: string
  terminalSessionId: string
  connectionId: string
  host: string
  username: string
  kind: 'COMMAND' | 'CONFIG'
  command: string
  executionCommand: string
  path?: string
  expectedContent?: string
  content?: string
  expiresAt: number
  status: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'UNKNOWN' | 'REJECTED' | 'EXPIRED'
  result?: string
}
export async function listApprovals(userId: string, sessionId: string): Promise<CommandApproval[]> {
  const response=await fetch(`${getRequestBaseUrl()}${BASE}/command_approval/list?${new URLSearchParams({userId,sessionId})}`)
  const body=await response.json()
  if(!response.ok || body.code !== '0000') throw new Error(body.info || '读取确认方案失败')
  return body.data
}
export async function decideApproval(approvalId: string,userId: string,sessionId: string,approve: boolean): Promise<CommandApproval> {
  const response=await fetch(`${getRequestBaseUrl()}${BASE}/command_approval/decide`, {
    method:'POST', headers:{'Content-Type':'application/json'},body:JSON.stringify({approvalId,userId,sessionId,approve}),
  })
  const body=await response.json()
  if(!response.ok || body.code !== '0000') throw new Error(body.info || '处理确认方案失败；请核查执行状态，不要重复提交')
  return body.data
}

function emitParsedEvent(raw: string, onEvent: (event: SSEEvent) => void): void {
  const trimmed = raw.trim()
  if (!trimmed) return

  let event: SSEEvent
  try { event = JSON.parse(trimmed) as SSEEvent }
  catch { return }
  onEvent(event)
}

function consumeSSEBlock(block: string, onEvent: (event: SSEEvent) => void): void {
  const trimmed = block.trim()
  if (!trimmed || trimmed.startsWith(':')) return

  if (trimmed.startsWith('{')) {
    emitParsedEvent(trimmed, onEvent)
    return
  }

  const dataPayload = trimmed
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
    .filter((line) => !line.startsWith(':'))
    .filter((line) => line.startsWith('data:'))
    .map((line) => line.slice(5).trimStart())
    .join('\n')

  if (dataPayload) {
    emitParsedEvent(dataPayload, onEvent)
  }
}

/** 流式对话：通过 ReadableStream 逐段读取并回调 */
const DEFAULT_CHAT_TIMEOUT_MS = 10 * 60 * 1000
const CHAT_TIMEOUT_KEY = 'walissh_chat_timeout_minutes'

/** 获取用户配置的流式对话超时（毫秒），默认 10 分钟 */
export function getChatTimeoutMs(): number {
  try {
    const minutes = Number(localStorage.getItem(CHAT_TIMEOUT_KEY))
    if (minutes > 0 && minutes <= 60) return minutes * 60 * 1000
  } catch {
    // localStorage 不可用时静默回退默认值
  }
  return DEFAULT_CHAT_TIMEOUT_MS
}

/** 设置流式对话超时（分钟），持久化到 localStorage */
export function setChatTimeoutMinutes(minutes: number): void {
  try {
    if (minutes > 0 && minutes <= 60) {
      localStorage.setItem(CHAT_TIMEOUT_KEY, String(minutes))
    } else {
      localStorage.removeItem(CHAT_TIMEOUT_KEY)
    }
  } catch {
    // 静默忽略
  }
}

/** 获取当前超时分钟数（用于 UI 显示） */
export function getChatTimeoutMinutes(): number {
  return Math.round(getChatTimeoutMs() / 60 / 1000)
}

export async function chatStream(
  payload: ChatStreamPayload,
  onEvent: (event: SSEEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  const TIMEOUT_MS = getChatTimeoutMs()
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS)

  // 如果外部传入了 signal，监听它的 abort 事件并转发
  if (signal) {
    if (signal.aborted) {
      clearTimeout(timer)
      throw new DOMException('Aborted', 'AbortError')
    }
    signal.addEventListener('abort', () => {
      clearTimeout(timer)
      controller.abort()
    })
  }

  let res: Response
  try {
    res = await fetch(`${getRequestBaseUrl()}${BASE}/chat_stream`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream, application/json',
        'Cache-Control': 'no-cache',
      },
      body: JSON.stringify({ ...payload, executionMode: payload.executionMode ?? getChatExecutionMode() }),
      signal: controller.signal,
    })
  } catch (err: any) {
    clearTimeout(timer)
    if (err?.name === 'AbortError') {
      throw new Error('请求超时或被取消')
    }
    throw err
  }

  if (!res.ok) {
    clearTimeout(timer)
    throw new Error(`流式对话请求失败: ${res.statusText}`)
  }

  if (!res.body) {
    clearTimeout(timer)
    throw new Error('响应没有 body')
  }

  const reader = res.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let receivedDone = false
  let receivedError = false

  const originalOnEvent = onEvent
  const wrappedOnEvent = (event: SSEEvent) => {
    if (receivedDone || receivedError) return
    if (event.event === 'done' || event.event === 'run_finished') receivedDone = true
    if (event.event === 'error') receivedError = true
    if (receivedDone || receivedError) clearTimeout(timer)
    originalOnEvent(event)
  }

  try {
    while (!receivedDone && !receivedError) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      buffer = buffer.replace(/\r\n/g, '\n')

      let blockBoundary = buffer.indexOf('\n\n')
      while (blockBoundary !== -1) {
        const block = buffer.slice(0, blockBoundary)
        buffer = buffer.slice(blockBoundary + 2)
        consumeSSEBlock(block, wrappedOnEvent)
        if (receivedDone || receivedError) break
        blockBoundary = buffer.indexOf('\n\n')
      }

      // 保留未收齐的 SSE block，不能提前丢弃 data: 行。
      // 兼容旧版单行 JSON：仅在确实以 JSON 开头时按行处理。
      while (buffer.trimStart().startsWith('{') && buffer.includes('\n')) {
        const newline = buffer.indexOf('\n')
        emitParsedEvent(buffer.slice(0, newline), wrappedOnEvent)
        buffer = buffer.slice(newline + 1)
      }
    }

    if (!receivedDone && !receivedError) consumeSSEBlock(buffer, wrappedOnEvent)

    // 流结束但未收到 done/error → 连接异常中断
    if (!receivedDone && !receivedError) {
      wrappedOnEvent({
        event: 'error',
        content: '连接已中断，响应未完成。请重试。',
        stopReason: 'disconnected',
      })
    }
  } finally {
    clearTimeout(timer)
    try { await reader.cancel() } finally { reader.releaseLock() }
  }
}
