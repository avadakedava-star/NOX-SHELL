import { useEffect, useRef, useState } from 'react'
import { useAgentStore } from '../../stores/agentStore'
import { Icon } from '../Icon'

interface ComposerProps {
  /** 终端连接状态的可读描述 */
  contextTone: 'ok' | 'warn' | 'danger'
  contextText: string
  contextAction?: { label: string; onClick: () => void }
  disabled?: boolean
  /** 会话已就绪，可以发送 */
  ready: boolean
  /** 与上方连接提示对应的终端；纯聊天时为 null。 */
  terminalSessionId: string | null
}

export function Composer({ contextTone, contextText, contextAction, disabled, ready, terminalSessionId }: ComposerProps) {
  const {
    inputText, setInputText, isLoading, stopGeneration,
    agents, currentAgentId, setCurrentAgent, sessions, currentSessionId,
  } = useAgentStore()
  const currentSession = currentSessionId ? sessions.get(currentSessionId) : null

  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const menuRef = useRef<HTMLDivElement>(null)
  const [focused, setFocused] = useState(false)
  const [menuOpen, setMenuOpen] = useState(false)

  const currentAgent = agents.find(agent => agent.agentId === currentAgentId)
  const canSend = Boolean(inputText.trim() && currentSession && !isLoading && currentAgentId && !disabled)

  // textarea 双向自适应，高度上限 240px；不改变外层布局高度策略
  useEffect(() => {
    const el = textareaRef.current
    if (!el) return
    el.style.height = 'auto'
    el.style.height = `${Math.min(Math.max(el.scrollHeight, 58), 240)}px`
  }, [inputText])

  useEffect(() => {
    if (!menuOpen) return
    const onDown = (event: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(event.target as Node)) setMenuOpen(false)
    }
    document.addEventListener('mousedown', onDown)
    return () => document.removeEventListener('mousedown', onDown)
  }, [menuOpen])

  const send = () => {
    if (!canSend) return
    const text = inputText.trim()
    setInputText('')
    void useAgentStore.getState().sendMessage(text, terminalSessionId)
  }

  const onKeyDown = (event: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.nativeEvent.isComposing || event.keyCode === 229) return
    if (event.key === 'Enter' && !event.shiftKey && !event.metaKey && !event.ctrlKey) {
      event.preventDefault()
      send()
    }
    if (event.key === 'Escape' && isLoading) stopGeneration()
  }

  return (
    <div className="nox-composer">
      {/* 固定高度的上下文条：连接状态变化不会推动输入框 */}
      <div className="nox-composer-context" data-tone={contextTone} role="status" aria-live="polite">
        <span className="nox-dot" data-tone={contextTone === 'warn' ? 'warn' : contextTone === 'danger' ? 'danger' : 'ok'} data-pulse={contextTone !== 'ok' ? 'true' : undefined} style={{ color: `var(--${contextTone === 'ok' ? 'ok' : contextTone === 'warn' ? 'warn' : 'danger'})` }} />
        <span>{contextText}</span>
        {contextAction && <button onClick={contextAction.onClick}>{contextAction.label} →</button>}
      </div>

      <div className="nox-composer-body">
        <div className="nox-composer-field">
          <textarea
            ref={textareaRef}
            aria-label="给 NOX AI 的消息"
            placeholder="描述你要完成的事，例如：检查 nginx 为什么 502"
            value={inputText}
            onChange={e => setInputText(e.target.value)}
            onKeyDown={onKeyDown}
            onFocus={() => setFocused(true)}
            onBlur={() => setFocused(false)}
            rows={2}
            disabled={disabled}
          />

          <div className="nox-composer-toolbar">
            <div ref={menuRef} style={{ position: 'relative' }}>
              <button
                type="button"
                className="nox-agent-picker"
                onClick={() => setMenuOpen(!menuOpen)}
                title="选择执行此任务的 Agent"
                aria-expanded={menuOpen}
              >
                <span className="dot" style={{ background: currentAgentId ? 'var(--ok)' : 'var(--ink-4)' }} />
                <span className="u-truncate">{currentAgent?.agentName || '选择 Agent'}</span>
                <Icon.ChevronDown size={10} />
              </button>
              {menuOpen && (
                <div className="nox-agent-menu" role="listbox">
                  {agents.length === 0 && (
                    <div style={{ padding: '8px', fontSize: 10.5, color: 'var(--ink-3)' }}>
                      未读取到 Agent，接口恢复后会自动重试。
                    </div>
                  )}
                  {agents.map((agent) => (
                    <button
                      key={agent.agentId}
                      role="option"
                      aria-selected={agent.agentId === currentAgentId}
                      onClick={() => { setCurrentAgent(agent.agentId); setMenuOpen(false) }}
                    >
                      <strong>{agent.agentName}</strong>
                      {agent.agentDesc && <small>{agent.agentDesc}</small>}
                    </button>
                  ))}
                </div>
              )}
            </div>

            <span className="grow" />

            {isLoading ? (
              <button type="button" className="nox-send" data-mode="stop" onClick={stopGeneration} title="停止本轮生成（Esc）">
                <Icon.Stop size={11} /> 停止
              </button>
            ) : (
              <button
                type="button"
                className="nox-send"
                onClick={send}
                disabled={!canSend}
                title={ready ? '发送（Enter）' : '先连接服务器或选择 Agent'}
              >
                <Icon.Send size={12} /> 发送
              </button>
            )}
          </div>
        </div>

        <div className="nox-composer-hint">
          <span>{focused ? 'Enter 发送 · Shift+Enter 换行 · Esc 停止' : 'NOX AI 会在执行前规划步骤，修改类操作需要你确认'}</span>
          <span className="u-mono">{inputText.length > 0 ? `${inputText.length} 字` : ''}</span>
        </div>
      </div>
    </div>
  )
}
