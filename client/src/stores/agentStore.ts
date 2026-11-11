import { create } from 'zustand'
import * as agentApi from '../api/agent'
import { playCompleteSound, playErrorSound } from '../utils/sound'
import { useConnectionStore } from './connectionStore'
import { ConnectionStatus } from '../types'
import type { SSEEvent } from '../api/agent'
import type {
  AgentConfig,
  AgentMessage,
  AgentRunTrace,
  AgentSession,
  AgentStepInfo,
  AgentTimelineEntry,
  AgentToolStatus,
  AgentToolTrace,
} from '../types'

interface AgentStore {
  agents: AgentConfig[]
  currentAgentId: string | null
  currentSessionId: string | null
  sessions: Map<string, AgentSession>
  inputText: string
  isLoading: boolean
  loadingText: string
  abortController: AbortController | null
   setAgents: (agents: AgentConfig[]) => void
   loadAgents: () => Promise<void>
   startAgentPolling: () => void
   stopAgentPolling: () => void
  setCurrentAgent: (agentId: string | null) => void
  setCurrentSession: (id: string | null) => void
  addMessage: (sessionId: string, message: AgentMessage) => void
  appendToLastAssistant: (sessionId: string, delta: string) => void
  replaceLastAssistant: (sessionId: string, fullText: string) => void
  setInputText: (text: string) => void
  setLoading: (loading: boolean, text?: string) => void
  setAbortController: (controller: AbortController | null) => void
  stopGeneration: () => void
  createSession: (name?: string) => string
  deleteSession: (id: string) => void
  clearSessionMessages: (id: string) => void
  renameSession: (id: string, name: string) => void
  ensureBackendSession: () => Promise<string | null>
  sendMessage: (content: string, terminalSessionId?: string | null) => Promise<void>
  setApprovalResult: (approvalId: string, status: string) => void
  loadPersistedSessions: () => void
}

const DEFAULT_USER_ID = 'walissh-user'
const DEFAULT_LOADING_TEXT = 'NOX-SHELL 正在思考...'
const DEFAULT_TRACE_STATUS = '请求已发送，正在启动流式响应...'
const MAX_TIMELINE_ENTRIES = 24
const AGENT_POLL_INITIAL_MS = 2000
const AGENT_POLL_MAX_MS = 30000
const PERSIST_KEY = 'walissh_agent_sessions'
const PERSIST_MAX_SESSIONS = 20
const DEFAULT_SESSION_NAME_PATTERN = /^会话 \d+$/

let agentPollTimer: ReturnType<typeof setTimeout> | null = null
let agentPollDelay = AGENT_POLL_INITIAL_MS

function clearAgentPollTimer(): void {
  if (agentPollTimer) {
    clearTimeout(agentPollTimer)
    agentPollTimer = null
  }
  agentPollDelay = AGENT_POLL_INITIAL_MS
}

function persistSessions(sessions: Map<string, AgentSession>, currentSessionId: string | null): void {
  try {
    const list = Array.from(sessions.values())
      .sort((a, b) => b.createdAt - a.createdAt)
      .slice(0, PERSIST_MAX_SESSIONS)
    const data = { sessions: list, currentSessionId }
    localStorage.setItem(PERSIST_KEY, JSON.stringify(data))
  } catch {
    // localStorage 可能在隐私模式不可用，静默忽略
  }
}

function loadPersistedSessionsData(): { sessions: AgentSession[]; currentSessionId: string | null } | null {
  try {
    const raw = localStorage.getItem(PERSIST_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw)
    if (!Array.isArray(parsed.sessions)) return null
    return { sessions: parsed.sessions, currentSessionId: parsed.currentSessionId ?? null }
  } catch {
    return null
  }
}

export function createRunTrace(statusText = DEFAULT_TRACE_STATUS): AgentRunTrace {
  const now = Date.now()
  return {
    stage: 'queued',
    statusText,
    startedAt: now,
    updatedAt: now,
    totalSteps: 0,
    totalToolCalls: 0,
    steps: [],
    tools: [],
    timeline: [],
  }
}

export function applyApprovalResult(trace: AgentRunTrace, approvalId: string, status: string): AgentRunTrace {
  if(status==='PENDING' || !trace.tools.some(tool=>tool.approvalId===approvalId)) return trace
  const stage: AgentRunTrace['stage']=status==='SUCCEEDED' ? 'completed' : status==='RUNNING' ? 'tool_running' : status==='FAILED' || status==='UNKNOWN' ? 'error' : 'stopped'
  const messages: Record<string,string>={SUCCEEDED:'已批准并执行成功',RUNNING:'操作执行中或结果待核查，请勿重复执行',FAILED:'已批准，但执行失败；请查看确认卡结果',UNKNOWN:'执行结果未知，请先核查服务器',REJECTED:'已拒绝，未执行变更',EXPIRED:'确认方案已过期，未执行变更'}
  const text=messages[status] || status
  return {...trace,stage,statusText:text,stopReason:`approval_${status.toLowerCase()}`,updatedAt:Date.now(),completedAt:Date.now(),error:stage==='error' ? text:undefined}
}

function updateSessionMessages(
  sessions: Map<string, AgentSession>,
  sessionId: string,
  updater: (messages: AgentMessage[]) => AgentMessage[],
): Map<string, AgentSession> {
  const nextSessions = new Map(sessions)
  const session = nextSessions.get(sessionId)
  if (!session) return nextSessions

  nextSessions.set(sessionId, {
    ...session,
    messages: updater(session.messages),
  })

  return nextSessions
}

function updateLastAssistantMessage(
  sessions: Map<string, AgentSession>,
  sessionId: string,
  updater: (message: AgentMessage) => AgentMessage,
): Map<string, AgentSession> {
  return updateSessionMessages(sessions, sessionId, (messages) => {
    if (messages.length === 0) return messages

    const nextMessages = [...messages]
    for (let index = nextMessages.length - 1; index >= 0; index -= 1) {
      if (nextMessages[index].role === 'assistant') {
        nextMessages[index] = updater(nextMessages[index])
        break
      }
    }

    return nextMessages
  })
}

function summarizeSessionTitle(content: string): string {
  const normalized = content.replace(/\s+/g, ' ').trim()
  return normalized.length > 30 ? `${normalized.slice(0, 30)}…` : normalized
}

function withSessionTitleFromMessage(
  sessions: Map<string, AgentSession>,
  sessionId: string,
  content: string,
): Map<string, AgentSession> {
  const session = sessions.get(sessionId)
  const title = summarizeSessionTitle(content)
  if (!session || !title || !DEFAULT_SESSION_NAME_PATTERN.test(session.name)) {
    return sessions
  }

  const nextSessions = new Map(sessions)
  nextSessions.set(sessionId, { ...session, name: title })
  return nextSessions
}

function normalizeToolStatus(status?: string): AgentToolStatus {
  if (status === 'blocked') return 'blocked'
  if (status === 'unknown') return 'unknown'
  if (status === 'pending') return 'pending'
  if (status === 'success') return 'success'
  if (status === 'error') return 'error'
  return 'running'
}

function upsertToolTrace(tools: AgentToolTrace[], tool: AgentToolTrace): AgentToolTrace[] {
  const index = tools.findIndex((item) => item.id === tool.id)
  if (index === -1) {
    return [...tools, tool]
  }

  const nextTools = [...tools]
  nextTools[index] = {
    ...nextTools[index],
    ...tool,
    startedAt: nextTools[index].startedAt,
    updatedAt: tool.updatedAt,
  }
  return nextTools
}

function upsertStepInfo(steps: AgentStepInfo[], step: AgentStepInfo): AgentStepInfo[] {
  const index = steps.findIndex((item) => item.currentStep === step.currentStep)
  if (index === -1) {
    return [...steps, step]
  }

  const nextSteps = [...steps]
  nextSteps[index] = { ...nextSteps[index], ...step }
  return nextSteps
}

function isDispatchToolEvent(event: SSEEvent): boolean {
  const { toolName, sourceAgent } = event
  return toolName === 'dispatchSubAgents'
    || toolName === 'planAndDispatchSubAgents'
    || Boolean(toolName && sourceAgent && toolName === sourceAgent)
}

function getEventStatusText(event: SSEEvent): string {
  if (event.executionStatus === 'WAITING_APPROVAL' || event.stopReason === 'WAITING_APPROVAL') return '等待你确认变更方案'
  if (event.executionStatus === 'BLOCKED' && event.rewriteAllowed) return '查询写法需要调整，正在尝试改写…'
  if (event.executionStatus === 'BLOCKED') return `操作已拦截${event.ruleId ? `（${event.ruleId}）` : ''}`
  if (event.executionStatus === 'UNKNOWN') return '操作结果未知，需要核查服务器状态'
  if (isDispatchToolEvent(event)) {
    return event.event === 'tool_call' ? '正在派发子Agent...' : '子Agent派发完成'
  }

  switch (event.event) {
    case 'status':
      return event.content?.trim() || DEFAULT_LOADING_TEXT
    case 'text':
      return '正在生成回答...'
    case 'tool_call':
      return event.toolName ? `正在执行 ${event.toolName}...` : '正在调用工具...'
    case 'tool_result':
      return event.toolName ? `${event.toolName} 已返回结果` : '工具执行完成'
    case 'round_end':
      return event.stepInfo?.currentStep ? `第 ${event.stepInfo.currentStep} 步已完成` : '执行步骤已完成'
    case 'done':
      return '回答已完成'
    case 'error':
      return event.content?.trim() || '执行失败'
    default:
      return DEFAULT_LOADING_TEXT
  }
}

function getTimelineLabel(event: SSEEvent): string {
  switch (event.event) {
    case 'status':
      return '开始分析'
    case 'text':
      return '生成回复'
    case 'tool_call':
      if (isDispatchToolEvent(event)) return '派发子Agent'
      return event.toolName ? `调用 ${event.toolName}` : '调用工具'
    case 'tool_result':
      if (isDispatchToolEvent(event)) return '子Agent派发返回'
      return event.toolName ? `${event.toolName} 返回结果` : '工具返回结果'
    case 'round_end':
      return event.stepInfo?.currentStep ? `步骤 ${event.stepInfo.currentStep}` : '步骤完成'
    case 'done':
      return '最终完成'
    case 'error':
      return '执行失败'
    default:
      return '状态更新'
  }
}

function getTimelineDetail(event: SSEEvent): string | undefined {
  if (event.event === 'text') {
    return event.fullText?.slice(-160) || event.content?.slice(-160)
  }
  if (event.event === 'tool_call') {
    return event.toolArgs || event.content
  }
  return event.content
}

function appendTimeline(trace: AgentRunTrace, event: SSEEvent): AgentTimelineEntry[] {
  const timestamp = event.timestamp ?? Date.now()
  const detail = getTimelineDetail(event)
  const label = getTimelineLabel(event)

  if (event.event === 'text') {
    const latest = trace.timeline[trace.timeline.length - 1]
    if (latest && latest.type === 'text') {
      const nextTimeline = [...trace.timeline]
      nextTimeline[nextTimeline.length - 1] = {
        ...latest,
        stage: event.stage ?? 'responding',
        label,
        detail,
        timestamp,
      }
      return nextTimeline
    }
  }

  const entry: AgentTimelineEntry = {
    id: `${event.event}_${event.toolCallId || event.stepInfo?.currentStep || timestamp}_${trace.timeline.length}`,
    type: event.event,
    stage: event.stage ?? (event.event === 'error' ? 'error' : 'thinking'),
    label,
    detail,
    status: event.status ? normalizeToolStatus(event.status) : undefined,
    toolCallId: event.toolCallId,
    toolName: event.toolName,
    kind: event.event === 'tool_call' || event.event === 'tool_result'
      ? (isDispatchToolEvent(event) ? 'dispatch' : 'tool')
      : undefined,
    timestamp,
  }

  return [...trace.timeline, entry].slice(-MAX_TIMELINE_ENTRIES)
}

export function applyTraceEvent(trace: AgentRunTrace, event: SSEEvent): AgentRunTrace {
  if (event.event === 'run_finished') {
    if(event.status==='paused') {
      const paused=applyTraceEvent(trace,{...event,event:'done'})
      return {...paused,stage:'paused',statusText:'已暂停，等待核查',error:undefined}
    }
    const failed = event.status === 'failed'
    return applyTraceEvent(trace, { ...event, event: failed ? 'error' : 'done' })
  }
  if (trace.completedAt) return trace

  const timestamp = event.timestamp ?? Date.now()
  const baseTrace: AgentRunTrace = {
    ...trace,
    updatedAt: timestamp,
    timeline: appendTimeline(trace, event),
  }

  switch (event.event) {
    case 'status':
      return {
        ...baseTrace,
        stage: event.stage ?? 'thinking',
        statusText: getEventStatusText(event),
      }
    case 'text':
      return {
        ...baseTrace,
        stage: event.stage ?? 'responding',
        statusText: getEventStatusText(event),
      }
    case 'tool_call': {
      const toolId = event.toolCallId || `tool_${baseTrace.tools.length + 1}`
      const current = baseTrace.tools.find((item) => item.id === toolId)
      const tools = upsertToolTrace(baseTrace.tools, {
        id: toolId,
        name: event.toolName || '工具调用',
        status: normalizeToolStatus(event.status),
        argumentsText: event.toolArgs || current?.argumentsText,
        resultText: current?.resultText,
        sourceAgent: event.sourceAgent || current?.sourceAgent,
        kind: isDispatchToolEvent(event) ? 'dispatch' : current?.kind ?? 'tool',
        startedAt: current?.startedAt ?? timestamp,
        updatedAt: timestamp,
      })

      return {
        ...baseTrace,
        stage: 'tool_running',
        statusText: getEventStatusText(event),
        tools,
        totalToolCalls: Math.max(baseTrace.totalToolCalls, event.totalToolCalls ?? 0, tools.filter(tool => tool.status !== 'blocked' && !tool.approvalId).length),
      }
    }
    case 'tool_result': {
      const toolId = event.toolCallId || `tool_${baseTrace.tools.length + 1}`
      const current = baseTrace.tools.find((item) => item.id === toolId)
      const status = normalizeToolStatus(event.status)
      const tools = upsertToolTrace(baseTrace.tools, {
        id: toolId,
        name: event.toolName || current?.name || '工具调用',
        status,
        argumentsText: event.toolArgs || current?.argumentsText,
        resultText: event.content || current?.resultText,
        failureType: event.failureType,
        ruleId: event.ruleId,
        rewriteAllowed: event.rewriteAllowed,
        approvalId: event.approvalId,
        sourceAgent: event.sourceAgent || current?.sourceAgent,
        kind: isDispatchToolEvent(event) ? 'dispatch' : current?.kind ?? 'tool',
        startedAt: current?.startedAt ?? timestamp,
        updatedAt: timestamp,
      })

      return {
        ...baseTrace,
        stage: event.executionStatus === 'WAITING_APPROVAL' ? 'waiting_approval' : 'thinking',
        statusText: getEventStatusText(event),
        tools,
        totalToolCalls: Math.max(baseTrace.totalToolCalls, event.totalToolCalls ?? 0, tools.filter(tool => tool.status !== 'blocked' && !tool.approvalId).length),
        error: baseTrace.error,
      }
    }
    case 'round_end': {
      const currentStep = event.stepInfo?.currentStep ?? (baseTrace.steps.length + 1)
      const stepInfo: AgentStepInfo = {
        currentStep,
        maxSteps: event.stepInfo?.maxSteps || undefined,
        shouldContinue: event.stepInfo?.shouldContinue,
        totalToolCalls: event.stepInfo?.totalToolCalls,
        summary: getEventStatusText(event),
        timestamp,
      }
      const steps = upsertStepInfo(baseTrace.steps, stepInfo)

      return {
        ...baseTrace,
        stage: stepInfo.shouldContinue === false ? 'responding' : 'thinking',
        statusText: stepInfo.summary || baseTrace.statusText,
        steps,
        totalSteps: Math.max(baseTrace.totalSteps, currentStep),
        totalToolCalls: Math.max(baseTrace.totalToolCalls, stepInfo.totalToolCalls ?? 0),
      }
    }
    case 'done':
      {
        const tools = baseTrace.tools.map((tool) => (
          tool.status === 'running' ? { ...tool, status: 'unknown' as const, updatedAt: timestamp } : tool
        ))
        return {
        ...baseTrace,
        stage: event.stopReason === 'WAITING_APPROVAL' ? 'waiting_approval' : 'completed',
        statusText: event.stopReason === 'WAITING_APPROVAL' ? '等待你确认变更方案' : '回答已完成',
        completedAt: timestamp,
        finalResponse: event.fullText ?? event.content ?? baseTrace.finalResponse,
        totalSteps: event.totalSteps ?? baseTrace.totalSteps,
        totalToolCalls: event.totalToolCalls ?? baseTrace.totalToolCalls,
        stopReason: event.stopReason ?? 'completed',
          tools,
      }
      }
    case 'error':
      {
        const tools = baseTrace.tools.map((tool) => (
          tool.status === 'running' ? { ...tool, status: 'unknown' as const, updatedAt: timestamp } : tool
        ))
        return {
        ...baseTrace,
        stage: 'error',
        statusText: getEventStatusText(event),
        completedAt: timestamp,
        error: event.content || baseTrace.error || '执行失败',
        stopReason: event.stopReason ?? 'error',
          tools,
      }
      }
    default:
      return baseTrace
  }
}

function stopTrace(trace: AgentRunTrace, statusText: string): AgentRunTrace {
  const now = Date.now()
  const stoppedEntry: AgentTimelineEntry = {
    id: `stopped_${now}`,
    type: 'error',
    stage: 'stopped',
    label: '已停止生成',
    detail: statusText,
    timestamp: now,
  }

  return {
    ...trace,
    stage: 'stopped',
    statusText,
    updatedAt: now,
    completedAt: now,
    stopReason: 'user_stop',
    timeline: [...trace.timeline, stoppedEntry].slice(-MAX_TIMELINE_ENTRIES),
  }
}

function isAbortError(err: unknown): boolean {
  const message = String((err as { message?: string } | null)?.message || '')
  const name = String((err as { name?: string } | null)?.name || '')
  return name === 'AbortError' || message.includes('取消') || message.includes('Aborted')
}

export const useAgentStore = create<AgentStore>((set, get) => ({
  agents: [],
  currentAgentId: null,
  currentSessionId: null,
  sessions: new Map(),
  inputText: '',
  isLoading: false,
  loadingText: DEFAULT_LOADING_TEXT,
  abortController: null,

  setAgents: (agents) => set({ agents }),

  loadAgents: async () => {
    try {
      const agents = await agentApi.queryAgentList()
      set({ agents })
      if (agents.length > 0 && !get().currentAgentId) {
        set({ currentAgentId: agents[0].agentId })
      }
      // 加载成功且列表非空 → 停止轮询
      if (agents.length > 0) {
        clearAgentPollTimer()
      }
    } catch (err) {
      console.error('加载智能体列表失败', err)
      // 加载失败 → 启动轮询，等服务端就绪后自动重试
      get().startAgentPolling()
    }
  },

  startAgentPolling: () => {
    if (agentPollTimer) return
    const tick = () => {
      if (get().agents.length === 0) {
        void get().loadAgents()
        // 指数退避：每次失败后翻倍，上限 30s
        agentPollDelay = Math.min(agentPollDelay * 2, AGENT_POLL_MAX_MS)
        agentPollTimer = setTimeout(tick, agentPollDelay)
      } else {
        clearAgentPollTimer()
      }
    }
    agentPollTimer = setTimeout(tick, agentPollDelay)
  },

  stopAgentPolling: () => {
    clearAgentPollTimer()
  },

  setCurrentAgent: (agentId) => set({ currentAgentId: agentId }),

  setCurrentSession: (id) => {
    set({ currentSessionId: id })
    persistSessions(get().sessions, id)
  },

  addMessage: (sessionId, message) =>
    set((state) => ({
      sessions: updateSessionMessages(state.sessions, sessionId, (messages) => [...messages, message]),
    })),

  appendToLastAssistant: (sessionId, delta) =>
    set((state) => ({
      sessions: updateLastAssistantMessage(state.sessions, sessionId, (message) => ({
        ...message,
        content: message.content + delta,
      })),
    })),

  replaceLastAssistant: (sessionId, fullText) =>
    set((state) => ({
      sessions: updateLastAssistantMessage(state.sessions, sessionId, (message) => ({
        ...message,
        content: fullText,
      })),
    })),

  setInputText: (text) => set({ inputText: text }),

  setLoading: (loading, text) =>
    set({
      isLoading: loading,
      loadingText: text || (loading ? DEFAULT_LOADING_TEXT : DEFAULT_LOADING_TEXT),
    }),

  setAbortController: (controller) => set({ abortController: controller }),

  stopGeneration: () => {
    const controller = get().abortController
    const sessionId = get().currentSessionId

    if (controller) {
      controller.abort()
    }

    set((state) => ({
      sessions: sessionId
        ? updateLastAssistantMessage(state.sessions, sessionId, (message) => ({
            ...message,
            trace: stopTrace(message.trace ?? createRunTrace(), '已停止生成'),
          }))
        : state.sessions,
      abortController: null,
      isLoading: false,
      loadingText: DEFAULT_LOADING_TEXT,
    }))
  },

  createSession: (name) => {
    const id = `session_${Date.now()}`
    const session: AgentSession = {
      id,
      name: name || `会话 ${get().sessions.size + 1}`,
      messages: [],
      createdAt: Date.now(),
    }

    set((state) => {
      const sessions = new Map(state.sessions)
      sessions.set(id, session)
      const next = { sessions, currentSessionId: id }
      persistSessions(sessions, id)
      return next
    })

    return id
  },

  deleteSession: (id) => {
    set((state) => {
      const sessions = new Map(state.sessions)
      sessions.delete(id)
      const nextCurrent = state.currentSessionId === id
        ? (sessions.size > 0 ? Array.from(sessions.keys())[0] : null)
        : state.currentSessionId
      persistSessions(sessions, nextCurrent)
      return { sessions, currentSessionId: nextCurrent }
    })
  },

  clearSessionMessages: (id) => {
    set((state) => {
      const sessions = new Map(state.sessions)
      const session = sessions.get(id)
      if (session) {
        sessions.set(id, { ...session, messages: [], backendSessionId: undefined })
      }
      persistSessions(sessions, state.currentSessionId)
      return { sessions }
    })
  },

  renameSession: (id, name) => {
    set((state) => {
      const sessions = new Map(state.sessions)
      const session = sessions.get(id)
      if (session) {
        sessions.set(id, { ...session, name })
      }
      persistSessions(sessions, state.currentSessionId)
      return { sessions }
    })
  },

  loadPersistedSessions: () => {
    const data = loadPersistedSessionsData()
    if (!data || data.sessions.length === 0) return

    set((state) => {
      const sessions = new Map(state.sessions)
      for (const session of data.sessions) {
        // 跳过流式输出未完成的消息标记
        const messages = session.messages.map((msg) => {
          if (msg.role === 'assistant' && msg.trace && !['completed', 'error', 'stopped', 'paused', 'waiting_approval'].includes(msg.trace.stage)) {
            return { ...msg, trace: { ...msg.trace, stage: 'stopped' as const, statusText: msg.trace.statusText || '已中断' } }
          }
          return msg
        })
        const legacyTitle = DEFAULT_SESSION_NAME_PATTERN.test(session.name)
          ? summarizeSessionTitle(messages.find((message) => message.role === 'user')?.content || '')
          : ''
        sessions.set(session.id, {
          ...session,
          name: legacyTitle || session.name,
          messages,
        })
      }
      const currentSessionId = data.currentSessionId && sessions.has(data.currentSessionId)
        ? data.currentSessionId
        : (sessions.size > 0 ? Array.from(sessions.keys())[0] : null)
      return { sessions, currentSessionId: currentSessionId ?? state.currentSessionId }
    })
  },

  ensureBackendSession: async () => {
    const state = get()
    const sessionId = state.currentSessionId
    if (!sessionId) return null

    const session = state.sessions.get(sessionId)
    if (!session) return null

    if (session.backendSessionId) {
      return session.backendSessionId
    }

    const agentId = state.currentAgentId
    if (!agentId) {
      throw new Error('未选择智能体')
    }

    const backendSessionId = await agentApi.createSession(agentId, DEFAULT_USER_ID)
    set((currentState) => {
      const sessions = new Map(currentState.sessions)
      const current = sessions.get(sessionId)
      if (current) {
        sessions.set(sessionId, {
          ...current,
          agentId,
          userId: DEFAULT_USER_ID,
          backendSessionId,
        })
      }
      return { sessions }
    })

    return backendSessionId
  },

  setApprovalResult: (approvalId,status) => {
    set(state=>{
      const sessions=new Map(state.sessions)
      for(const [id,session] of sessions) sessions.set(id,{...session,messages:session.messages.map(message=>
        message.trace ? {...message,trace:applyApprovalResult(message.trace,approvalId,status)}:message)})
      return {sessions}
    })
    persistSessions(get().sessions,get().currentSessionId)
  },
  sendMessage: async (content, terminalSessionId) => {
    const state = get()
    const sessionId = state.currentSessionId
    const agentId = state.currentAgentId
    // 在本轮开始时绑定目标，避免等待创建聊天会话期间切换服务器后串到另一台机器。
    // undefined 表示调用方省略参数，统一从已连接的当前终端兜底；null 明确表示纯聊天。
    const connectionState = useConnectionStore.getState()
    const currentConnection = connectionState.connections.find(connection => connection.id === connectionState.currentConnectionId)
    const targetTerminalSessionId = terminalSessionId === undefined
      ? (currentConnection?.status === ConnectionStatus.CONNECTED ? connectionState.getCurrentTerminalSession() : null)
      : terminalSessionId

    if (!sessionId) {
      throw new Error('当前没有活跃会话')
    }
    if (!agentId) {
      throw new Error('未选择智能体')
    }

    set((currentState) => {
      const namedSessions = withSessionTitleFromMessage(currentState.sessions, sessionId, content)
      return {
        sessions: updateSessionMessages(namedSessions, sessionId, (messages) => [...messages, {
          id: `msg_${Date.now()}`,
          role: 'user' as const,
          content,
          timestamp: Date.now(),
        }]),
      }
    })

    get().setLoading(true, '已发送，等待 NOX-SHELL 响应...')

    let controller: AbortController | null = null
    let hadError = false

    try {
      const backendSessionId = await get().ensureBackendSession()
      if (!backendSessionId) {
        throw new Error('创建后端会话失败')
      }

      get().addMessage(sessionId, {
        id: `msg_${Date.now()}_ai`,
        role: 'assistant',
        content: '',
        timestamp: Date.now(),
        trace: createRunTrace(),
      })

      controller = new AbortController()
      get().setAbortController(controller)

      await agentApi.chatStream(
        {
          agentId,
          userId: DEFAULT_USER_ID,
          sessionId: backendSessionId,
          message: content,
          terminalSessionId: targetTerminalSessionId || undefined,
        },
        (event) => {
          let nextLoadingText = DEFAULT_LOADING_TEXT
          if (event.event === 'error' || (event.event === 'run_finished' && event.status === 'failed')) {
            hadError = true
          }

          set((currentState) => {
            let traceSnapshot: AgentRunTrace | undefined
            const sessions = updateLastAssistantMessage(currentState.sessions, sessionId, (message) => {
              const trace = applyTraceEvent(message.trace ?? createRunTrace(), event)
              traceSnapshot = trace

              let nextContent = message.content
              if (event.event === 'text') {
                nextContent = event.fullText ?? (event.content ? message.content + event.content : message.content)
              } else if (event.event === 'done') {
                nextContent = event.fullText ?? event.content ?? message.content
              } else if ((event.event === 'error' || (event.event === 'run_finished' && event.status === 'failed')) && !message.content.trim()) {
                nextContent = `⚠️ ${event.content || '执行失败'}`
              }

              return {
                ...message,
                content: nextContent,
                trace,
              }
            })

            nextLoadingText = traceSnapshot?.statusText || currentState.loadingText
            return {
              sessions,
              loadingText: nextLoadingText,
              isLoading: ['run_finished', 'done', 'error'].includes(event.event) ? false : currentState.isLoading,
            }
          })
        },
        controller.signal,
      )
    } catch (err) {
      console.error('发送消息失败', err)

      if ((controller?.signal.aborted ?? false) || isAbortError(err)) {
        set((currentState) => ({
          sessions: updateLastAssistantMessage(currentState.sessions, sessionId, (message) => ({
            ...message,
            trace: stopTrace(message.trace ?? createRunTrace(), '已停止生成'),
          })),
        }))
        return
      }

      hadError = true

      const errorText = (err as { message?: string } | null)?.message || '未知错误'
      set((currentState) => ({
        sessions: updateLastAssistantMessage(currentState.sessions, sessionId, (message) => ({
          ...message,
          trace: applyTraceEvent(message.trace ?? createRunTrace(), {
            event: 'error',
            content: errorText,
            stopReason: 'error',
          }),
          content: message.content || `⚠️ ${errorText}`,
        })),
      }))

      get().addMessage(sessionId, {
        id: `msg_${Date.now()}_err`,
        role: 'system',
        content: `请求失败: ${errorText}`,
        timestamp: Date.now(),
      })
    } finally {
      get().setLoading(false)
      get().setAbortController(null)
      // 持久化会话到 localStorage
      persistSessions(get().sessions, get().currentSessionId)
      // 提示音：完成或错误
      if (hadError) {
        playErrorSound()
      } else {
        playCompleteSound()
      }
    }
  },
}))
