import { useState } from 'react'
import { useAgentStore } from '../stores/agentStore'
import type { AgentSession } from '../types'
import { Icon } from './Icon'
import { formatClock } from '../utils/format'

interface HistorySidebarProps {
  width?: number
  onClose: () => void
}

function getSessionTitle(session: AgentSession): string {
  if (!/^会话 \d+$/.test(session.name)) return session.name
  const firstUserMessage = session.messages.find((message) => message.role === 'user')?.content
  const normalized = firstUserMessage?.replace(/\s+/g, ' ').trim()
  return normalized ? (normalized.length > 34 ? `${normalized.slice(0, 34)}…` : normalized) : session.name
}

export function HistorySidebar({ width = 284, onClose }: HistorySidebarProps) {
  const {
    sessions,
    currentSessionId,
    createSession,
    deleteSession,
    renameSession,
    setCurrentSession,
  } = useAgentStore()

  const [editingId, setEditingId] = useState<string | null>(null)
  const [editingName, setEditingName] = useState('')

  const sortedSessions = Array.from(sessions.values()).sort((a, b) => b.createdAt - a.createdAt)

  const startEdit = (id: string, name: string) => {
    setEditingId(id)
    setEditingName(name)
  }

  const commitEdit = () => {
    if (editingId && editingName.trim()) renameSession(editingId, editingName.trim())
    setEditingId(null)
    setEditingName('')
  }

  return (
    <div className="nox-history flex flex-col" style={{ width, background: 'var(--s1)', borderLeft: '1px solid var(--line)' }}>
      <div className="nox-sidebar-head">
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 7 }}>
          <Icon.History size={13} style={{ color: 'var(--ink-3)' }} />
          <span className="u-caps">历史对话</span>
          <span className="u-num" style={{ fontSize: 9, color: 'var(--ink-4)' }}>{sortedSessions.length}</span>
        </span>
        <button className="nox-icon-btn" onClick={onClose} title="关闭历史面板" aria-label="关闭历史面板">
          <Icon.X size={13} />
        </button>
      </div>

      <div style={{ padding: '10px 12px', borderBottom: '1px solid var(--line)' }}>
        <button
          className="nox-add-host"
          onClick={() => { createSession(); onClose() }}
        >
          <Icon.Plus size={12} /> 新建对话
        </button>
      </div>

      <div className="flex-1 overflow-y-auto" style={{ padding: '6px 8px' }}>
        {sortedSessions.length === 0 ? (
          <div className="nox-empty">
            <Icon.History size={18} />
            <h3>还没有历史对话</h3>
            <p>每次对话都会保存在本机，方便你回看当时的执行过程。</p>
          </div>
        ) : (
          sortedSessions.map((session) => {
            const isActive = session.id === currentSessionId
            const isEditing = editingId === session.id
            return (
              <div
                key={session.id}
                onClick={() => { if (!isEditing) { setCurrentSession(session.id); onClose() } }}
                className="nox-host"
                data-active={isActive}
                style={{ gridTemplateColumns: 'minmax(0, 1fr) auto', cursor: 'pointer' }}
                role="button"
                tabIndex={0}
                onKeyDown={(e) => { if (e.key === 'Enter') { setCurrentSession(session.id); onClose() } }}
              >
                <div style={{ minWidth: 0 }}>
                  {isEditing ? (
                    <input
                      autoFocus
                      value={editingName}
                      onChange={(e) => setEditingName(e.target.value)}
                      onBlur={commitEdit}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') commitEdit()
                        if (e.key === 'Escape') { setEditingId(null); setEditingName('') }
                      }}
                      onClick={(e) => e.stopPropagation()}
                      className="u-truncate"
                      style={{
                        width: '100%',
                        fontSize: 12,
                        padding: '2px 5px',
                        color: 'var(--ink)',
                        background: 'var(--sunken)',
                        border: '1px solid color-mix(in srgb, var(--signal) 55%, var(--line))',
                        borderRadius: 2,
                        outline: 'none',
                      }}
                    />
                  ) : (
                    <div
                      className="u-truncate"
                      style={{ fontSize: 12, fontWeight: 500, color: isActive ? 'var(--ink)' : 'var(--ink-2)' }}
                      onDoubleClick={(e) => { e.stopPropagation(); startEdit(session.id, getSessionTitle(session)) }}
                      title={getSessionTitle(session)}
                    >
                      {getSessionTitle(session)}
                    </div>
                  )}
                  <div style={{ display: 'flex', gap: 8, marginTop: 3 }} className="u-mono">
                    <span style={{ fontSize: 9, color: 'var(--ink-4)' }}>{formatClock(session.createdAt)}</span>
                    <span style={{ fontSize: 9, color: 'var(--ink-4)' }}>{session.messages.length} 条</span>
                    {isActive && <span style={{ fontSize: 9, color: 'var(--signal)' }}>当前</span>}
                  </div>
                </div>

                <div className="nox-host-actions">
                  <button
                    className="nox-icon-btn"
                    onClick={(e) => { e.stopPropagation(); startEdit(session.id, getSessionTitle(session)) }}
                    title="重命名"
                    aria-label="重命名对话"
                  >
                    <Icon.Edit size={11} />
                  </button>
                  <button
                    className="nox-icon-btn"
                    data-tone="danger"
                    onClick={(e) => { e.stopPropagation(); deleteSession(session.id) }}
                    title="删除对话"
                    aria-label="删除对话"
                  >
                    <Icon.Trash size={11} />
                  </button>
                </div>
              </div>
            )
          })
        )}
      </div>

      <div className="nox-sidebar-foot">
        <span className="u-caps">LOCAL HISTORY</span>
        <p>对话记录保存在本机浏览器中，最多保留 20 条。</p>
      </div>
    </div>
  )
}
