import { memo, useEffect, useMemo, useRef, useState } from 'react'
import type { AgentMessage } from '../../types'
import { formatMarkdown } from '../../utils/markdown'
import { formatClock } from '../../utils/format'
import { AgentRun } from './AgentRun'
import { Icon } from '../Icon'

const ROLE_LABEL = { user: 'YOU', assistant: 'NOX', system: 'SYSTEM' } as const

export const MessageBubble = memo(function MessageBubble({ message, goal }: { message: AgentMessage; goal?: string }) {
  const [copied, setCopied] = useState(false)
  const contentRef = useRef<HTMLDivElement>(null)
  const renderedContent = useMemo(() => (message.content ? formatMarkdown(message.content) : ''), [message.content])

  const handleCopy = () => {
    if (!message.content) return
    void navigator.clipboard.writeText(message.content).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 1600)
    })
  }

  // 代码块复制：事件委托 + data-code 定位
  useEffect(() => {
    const el = contentRef.current
    if (!el) return
    const onClick = (event: MouseEvent) => {
      const target = (event.target as HTMLElement).closest('.md-copy') as HTMLElement | null
      if (!target || !el.contains(target)) return
      const wrapper = target.closest('.md-code-wrap')
      const codeEl = wrapper?.querySelector('code')
      if (!codeEl) return
      void navigator.clipboard.writeText(codeEl.textContent || '').then(() => {
        target.textContent = '已复制'
        setTimeout(() => { target.textContent = '复制' }, 1600)
      })
    }
    el.addEventListener('click', onClick)
    return () => el.removeEventListener('click', onClick)
  }, [message.content])

  const streaming = message.role === 'assistant' && message.trace && !['completed', 'error', 'stopped', 'paused', 'waiting_approval'].includes(message.trace.stage)

  return (
    <article className="nox-msg msg-enter" data-role={message.role}>
      <header className="nox-msg-head">
        <span className="nox-msg-role-mark" />
        <span className="nox-msg-role">{ROLE_LABEL[message.role]}</span>
        <span className="nox-msg-time">{formatClock(message.timestamp)}</span>
        {message.content && (
          <span className="nox-msg-actions">
            <button className="nox-icon-btn" onClick={handleCopy} title={copied ? '已复制' : '复制回答'} aria-label="复制回答">
              {copied ? <Icon.Check size={12} /> : <Icon.Copy size={12} />}
            </button>
          </span>
        )}
      </header>

      {message.role === 'assistant' && message.trace && (
        <AgentRun trace={message.trace} goal={goal} />
      )}

      {message.content ? (
        <div className="nox-msg-body md" ref={contentRef} dangerouslySetInnerHTML={{ __html: renderedContent }} />
      ) : message.role === 'assistant' && !message.trace ? (
        <div className="nox-msg-body" style={{ display: 'flex', alignItems: 'center', gap: 6, color: 'var(--ink-3)' }}>
          <span>思考中</span>
          <span style={{ display: 'inline-flex', gap: 3 }}>
            <span className="nox-dot loading-dot" style={{ background: 'var(--signal)' }} />
            <span className="nox-dot loading-dot" style={{ background: 'var(--signal)' }} />
            <span className="nox-dot loading-dot" style={{ background: 'var(--signal)' }} />
          </span>
        </div>
      ) : null}

      {streaming && !message.content && (
        <div className="nox-msg-body" style={{ color: 'var(--ink-4)', fontSize: 11 }}>
          正在等待模型输出正文…
        </div>
      )}
    </article>
  )
})
