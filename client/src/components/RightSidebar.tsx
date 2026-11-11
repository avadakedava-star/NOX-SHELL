import { useEffect, useMemo, useRef, useState } from 'react'
import { NoxMark } from './NoxBrand'
import { useAgentStore } from '../stores/agentStore'
import { useConnectionStore } from '../stores/connectionStore'
import { ConnectionStatus } from '../types'
import { TaskProgressPanel } from './TaskProgressPanel'
import { Composer } from './agent/Composer'
import { MessageBubble } from './agent/MessageBubble'
import { RunCapsule, TERMINAL_STAGES } from './agent/AgentRun'
import { CommandApprovalCard } from './CommandApprovalCard'
import { Icon } from './Icon'

interface RightSidebarProps {
  width?: number
  historyVisible?: boolean
  onToggleHistory?: () => void
}

const SUGGESTIONS = [
  { label: '检查服务器状态', detail: 'CPU、内存与磁盘使用情况', icon: '⌁', prompt: '检查服务器状态，包括 CPU、内存和磁盘使用情况。' },
  { label: '查看 Docker 容器', detail: '镜像、端口与运行状态', icon: '▣', prompt: '请查看 Docker 里部署了哪些应用，列出容器名、镜像、端口和运行状态。' },
  { label: '排查服务问题', detail: '从错误日志寻找原因', icon: '⌕', prompt: '我想排查服务问题，请先询问我要检查哪个服务，再帮我分析日志。' },
]

export function RightSidebar({ width = 408, historyVisible = false, onToggleHistory }: RightSidebarProps) {
  const {
    sessions,
    currentSessionId,
    isLoading,
    createSession,
    clearSessionMessages,
    loadAgents,
    loadPersistedSessions,
  } = useAgentStore()

  const { currentConnectionId, connections, getCurrentTerminalSession, serverStatus } = useConnectionStore()

  const currentSession = currentSessionId ? sessions.get(currentSessionId) : null
  const textareaFocusRef = useRef<HTMLDivElement>(null)
  const scrollContainerRef = useRef<HTMLDivElement>(null)
  const [showScrollToBottom, setShowScrollToBottom] = useState(false)
  const autoScrollRef = useRef(true)

  useEffect(() => { loadPersistedSessions() }, [loadPersistedSessions])
  useEffect(() => { loadAgents() }, [loadAgents])

  useEffect(() => {
    const loaded = useAgentStore.getState()
    if (!loaded.currentSessionId && loaded.sessions.size === 0) loaded.createSession()
  }, [currentSessionId, sessions.size, createSession])

  const currentConnection = connections.find(connection => connection.id === currentConnectionId)
  const terminalSessionId = getCurrentTerminalSession()
  const isConnected = currentConnection?.status === ConnectionStatus.CONNECTED && !!terminalSessionId

  const context = useMemo(() => {
    if (!serverStatus.connected) {
      return { tone: 'danger' as const, text: '本机接口不可达，SSH 状态待确认；恢复前 AI 无法执行命令', action: undefined }
    }
    if (!currentConnectionId) {
      return { tone: 'warn' as const, text: '尚未选择服务器：可以对话，但无法执行 SSH 操作', action: undefined }
    }
    if (!isConnected) {
      return { tone: 'warn' as const, text: `未连接 ${currentConnection?.name ?? '服务器'}：可以对话，执行命令前需先建立连接`, action: undefined }
    }
    return { tone: 'ok' as const, text: `已连接 ${currentConnection?.name}${currentConnection ? ` · ${currentConnection.username}@${currentConnection.host}` : ''}`, action: undefined }
  }, [serverStatus.connected, currentConnectionId, isConnected, currentConnection])

  const currentTrace = useMemo(() => {
    if (!currentSession) return null
    for (let index = currentSession.messages.length - 1; index >= 0; index -= 1) {
      const message = currentSession.messages[index]
      if (message.role === 'assistant' && message.trace) return message.trace
    }
    return null
  }, [currentSession])

  const isTraceActive = Boolean(currentTrace && !TERMINAL_STAGES.includes(currentTrace.stage))

  // 最后一条助手消息中等待确认的变更
  const pendingApprovals = useMemo(() => {
    if (!currentTrace || currentTrace.stage !== 'waiting_approval') return [] as string[]
    return currentTrace.tools
      .filter(tool => tool.approvalId && tool.status !== 'success' && tool.status !== 'error' && tool.status !== 'blocked')
      .map(tool => tool.approvalId as string)
  }, [currentTrace])

  // 用户上滚后停止自动跟随，回到底部时恢复
  const messageCount = currentSession?.messages.length ?? 0
  const lastMessageLen = currentSession?.messages[messageCount - 1]?.content.length ?? 0
  const traceRevision = currentTrace ? currentTrace.timeline.length + currentTrace.tools.length : 0
  useEffect(() => {
    if (!autoScrollRef.current) return
    const el = scrollContainerRef.current
    if (!el) return
    if (messageCount === 0) { el.scrollTop = 0; return }
    requestAnimationFrame(() => {
      requestAnimationFrame(() => { if (autoScrollRef.current) el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' }) })
    })
  }, [messageCount, lastMessageLen, isLoading, traceRevision])

  const handleScroll = () => {
    const el = scrollContainerRef.current
    if (!el) return
    const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 72
    autoScrollRef.current = nearBottom
    setShowScrollToBottom(!nearBottom && messageCount > 0)
  }

  const scrollToBottom = () => {
    const el = scrollContainerRef.current
    if (!el) return
    el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' })
    autoScrollRef.current = true
    setShowScrollToBottom(false)
  }

  const setPrompt = (text: string) => {
    useAgentStore.getState().setInputText(text)
    textareaFocusRef.current?.querySelector('textarea')?.focus()
  }

  // 任务目标：每条 assistant 消息对应它之前最近的一条用户消息
  const goalFor = (index: number): string | undefined => {
    if (!currentSession) return undefined
    for (let i = index - 1; i >= 0; i -= 1) {
      const message = currentSession.messages[i]
      if (message.role === 'user' && message.content.trim()) {
        const text = message.content.replace(/\s+/g, ' ').trim()
        return text.length > 72 ? `${text.slice(0, 72)}…` : text
      }
    }
    return undefined
  }

  return (
    <div className="nox-assistant flex flex-col h-full flex-shrink-0 relative" style={{ width }}>
      <div className="nox-panel-heading">
        <span className="nox-assistant-title">
          <NoxMark /> NOX AI <span className="nox-ai-tag">assistant</span>
        </span>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <span className="nox-ai-state" data-busy={isLoading}>
            <span className="nox-dot" data-tone={isLoading ? 'signal' : 'ok'} data-pulse={isLoading ? 'true' : undefined} style={{ color: isLoading ? 'var(--signal)' : undefined }} />
            {isLoading ? 'RUNNING' : 'READY'}
          </span>
          <span style={{ width: 1, height: 14, background: 'var(--line)' }} />
          <button className="nox-icon-btn" onClick={() => createSession()} title="新建对话" aria-label="新建对话">
            <Icon.Plus size={14} />
          </button>
          {currentSession && currentSession.messages.length > 0 && (
            <button
              className="nox-icon-btn"
              data-tone="danger"
              onClick={() => { if (currentSessionId) clearSessionMessages(currentSessionId) }}
              title="清空当前对话"
              aria-label="清空当前对话"
            >
              <Icon.Trash size={13} />
            </button>
          )}
          <button
            className="nox-icon-btn"
            data-active={historyVisible}
            onClick={() => onToggleHistory?.()}
            title="历史对话"
            aria-label="历史对话"
          >
            <Icon.History size={14} />
          </button>
        </div>
      </div>

      {isLoading && <div className="ai-progress-bar" aria-hidden="true" />}

      <div className="relative flex-1 min-h-0">
        <div ref={scrollContainerRef} className="h-full overflow-y-auto" onScroll={handleScroll}>
          {!currentSession ? (
            <div className="flex flex-col items-center justify-center h-full gap-3 px-6 text-center">
              <span className="nox-ai-symbol" style={{ marginBottom: 4 }}><NoxMark /></span>
              <h3 style={{ fontSize: 13, fontWeight: 600, color: 'var(--ink)' }}>开始一段对话</h3>
              <p style={{ fontSize: 11.5, lineHeight: 1.8, color: 'var(--ink-3)', maxWidth: 260 }}>
                连接 SSH 后，AI 可以读取终端、执行命令，并把每一步进展展示给你。
              </p>
              <button className="nox-button-primary" style={{ height: 30, fontSize: 11, padding: '0 12px' }} onClick={() => createSession()}>
                <Icon.Plus size={12} /> 新建对话
              </button>
            </div>
          ) : currentSession.messages.length === 0 ? (
            <div className="nox-ai-welcome">
              <span className="nox-ai-symbol"><NoxMark /></span>
              <div className="nox-eyebrow">YOUR TERMINAL COPILOT</div>
              <h2>把下一步，交给灵感。</h2>
              <p>查状态、看日志、分析问题。<br />说出你的想法，和 NOX 一起处理。</p>
              <div className="nox-suggestions">
                <span className="nox-section-caption">从这里开始</span>
                {SUGGESTIONS.map(item => (
                  <button key={item.label} onClick={() => setPrompt(item.prompt)}>
                    <span className="nox-suggestion-icon">{item.icon}</span>
                    <span style={{ minWidth: 0 }}>
                      <strong>{item.label}</strong>
                      <small>{item.detail}</small>
                    </span>
                    <span className="nox-suggestion-arrow">→</span>
                  </button>
                ))}
              </div>
              <div className="nox-ai-note">
                <span />
                {isConnected ? '已关联当前终端，可以直接操作' : '可以直接聊天；操作服务器前请先连接 SSH'}
              </div>
            </div>
          ) : (
            <div style={{ paddingBottom: isTraceActive ? 44 : 4 }}>
              {currentSession.messages.map((message, index) => (
                <MessageBubble key={message.id} message={message} goal={goalFor(index)} />
              ))}
              <TaskProgressPanel sessionId={currentSession.backendSessionId} chatRunning={isLoading} />
            </div>
          )}

        </div>

        {isTraceActive && currentTrace && <RunCapsule trace={currentTrace} />}
        {showScrollToBottom && (
          <button className="nox-scroll-bottom" onClick={scrollToBottom}>
            <Icon.ArrowDown size={11} /> 回到底部
          </button>
        )}
      </div>

      {/* 待确认变更固定在输入区上方，不随消息滚动丢失 */}
      {pendingApprovals.length > 0 && (
        <div style={{ borderTop: '1px solid var(--line)', background: 'var(--s0)', padding: '8px 14px 0', flexShrink: 0 }}>
          <div className="u-caps" style={{ marginBottom: 4 }}>待确认操作 · {pendingApprovals.length}</div>
          {pendingApprovals.map(id => <CommandApprovalCard key={id} approvalId={id} />)}
        </div>
      )}

      <div ref={textareaFocusRef}>
        <Composer
          contextTone={context.tone}
          contextText={context.text}
          disabled={!currentSession}
          ready={isConnected}
          terminalSessionId={isConnected ? terminalSessionId : null}
        />
      </div>
    </div>
  )
}
