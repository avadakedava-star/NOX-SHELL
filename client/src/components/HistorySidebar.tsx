import { useState } from 'react'
import { useThemeStore } from '../stores/themeStore'
import { useAgentStore } from '../stores/agentStore'
import type { AgentSession } from '../types'

interface HistorySidebarProps {
  width?: number
  onClose: () => void
}

function getSessionTitle(session: AgentSession): string {
  if (!/^会话 \d+$/.test(session.name)) return session.name
  const firstUserMessage = session.messages.find((message) => message.role === 'user')?.content
  const normalized = firstUserMessage?.replace(/\s+/g, ' ').trim()
  return normalized ? (normalized.length > 30 ? `${normalized.slice(0, 30)}…` : normalized) : session.name
}

export function HistorySidebar({ width = 280, onClose }: HistorySidebarProps) {
  const { colors } = useThemeStore()
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
    if (editingId && editingName.trim()) {
      renameSession(editingId, editingName.trim())
    }
    setEditingId(null)
    setEditingName('')
  }

  return (
    <div
      className="flex flex-col h-full flex-shrink-0 relative"
      style={{ width, backgroundColor: colors.bgSecondary, borderLeft: `1px solid ${colors.border}` }}
    >
      {/* 顶部标题栏 */}
      <div
        className="flex items-center justify-between px-3 h-11 flex-shrink-0"
        style={{ borderBottom: `1px solid ${colors.border}` }}
      >
        <div className="flex items-center gap-2">
          <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" style={{ color: colors.textSecondary }}>
            <circle cx="12" cy="12" r="10"></circle>
            <polyline points="12 6 12 12 16 14"></polyline>
          </svg>
          <span className="text-[13px] font-medium" style={{ color: colors.text }}>历史对话</span>
          <span className="text-[11px]" style={{ color: colors.textDim }}>{sortedSessions.length}</span>
        </div>
        <button
          onClick={onClose}
          className="w-6 h-6 flex items-center justify-center rounded transition-all duration-150"
          style={{ color: colors.textSecondary }}
          onMouseEnter={(e) => { e.currentTarget.style.backgroundColor = `${colors.accent}18`; e.currentTarget.style.color = colors.accent }}
          onMouseLeave={(e) => { e.currentTarget.style.backgroundColor = 'transparent'; e.currentTarget.style.color = colors.textSecondary }}
          title="关闭历史侧边栏"
        >
          <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
            <line x1="18" y1="6" x2="6" y2="18"></line>
            <line x1="6" y1="6" x2="18" y2="18"></line>
          </svg>
        </button>
      </div>

      {/* 新建会话按钮 */}
      <div className="px-3 py-2 flex-shrink-0" style={{ borderBottom: `1px solid ${colors.border}` }}>
        <button
          onClick={() => createSession()}
          className="w-full flex items-center justify-center gap-1.5 px-3 py-2 rounded-lg text-[12px] font-medium transition-all"
          style={{
            backgroundColor: colors.bgTertiary,
            color: colors.textSecondary,
            border: `1px solid ${colors.border}`,
          }}
          onMouseEnter={(e) => { e.currentTarget.style.backgroundColor = colors.accentSoft; e.currentTarget.style.color = colors.accent; e.currentTarget.style.borderColor = `${colors.accent}40` }}
          onMouseLeave={(e) => { e.currentTarget.style.backgroundColor = colors.bgTertiary; e.currentTarget.style.color = colors.textSecondary; e.currentTarget.style.borderColor = colors.border }}
        >
          <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <line x1="12" y1="5" x2="12" y2="19"></line>
            <line x1="5" y1="12" x2="19" y2="12"></line>
          </svg>
          <span>新建对话</span>
        </button>
      </div>

      {/* 会话列表 */}
      <div className="flex-1 overflow-y-auto py-1">
        {sortedSessions.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full gap-2 px-4">
            <svg className="w-8 h-8 opacity-30" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ color: colors.textDim }}>
              <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path>
            </svg>
            <span className="text-[11px]" style={{ color: colors.textDim }}>暂无历史对话</span>
          </div>
        ) : (
          sortedSessions.map((session) => {
            const isActive = session.id === currentSessionId
            const isEditing = editingId === session.id
            return (
              <div
                key={session.id}
                onClick={() => {
                  if (!isEditing) setCurrentSession(session.id)
                }}
                className="group mx-2 my-0.5 px-3 py-2.5 rounded-lg cursor-pointer transition-colors"
                style={{
                  color: isActive ? colors.accent : colors.text,
                  backgroundColor: isActive ? colors.accentSoft : 'transparent',
                }}
                onMouseEnter={(e) => { if (!isActive) e.currentTarget.style.backgroundColor = `${colors.accent}08` }}
                onMouseLeave={(e) => { if (!isActive) e.currentTarget.style.backgroundColor = 'transparent' }}
              >
                <div className="flex items-start gap-2">
                  <svg className="w-3.5 h-3.5 mt-0.5 flex-shrink-0 opacity-60" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" style={{ color: isActive ? colors.accent : colors.textDim }}>
                    <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path>
                  </svg>
                  <div className="flex-1 min-w-0">
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
                        className="w-full text-[12px] outline-none rounded px-1 py-0.5"
                        style={{
                          color: colors.text,
                          backgroundColor: colors.bgPrimary,
                          border: `1px solid ${colors.accent}80`,
                        }}
                      />
                    ) : (
                      <div
                        className="text-[12px] font-medium truncate"
                        onDoubleClick={(e) => { e.stopPropagation(); startEdit(session.id, getSessionTitle(session)) }}
                      >
                        {getSessionTitle(session)}
                      </div>
                    )}
                    <div className="flex items-center gap-2 mt-0.5">
                      <span className="text-[10px]" style={{ color: colors.textDim }}>
                        {new Date(session.createdAt).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })}
                      </span>
                      <span className="text-[10px]" style={{ color: colors.textDim }}>{session.messages.length} 条</span>
                    </div>
                  </div>

                  {/* 操作按钮 */}
                  <div className="flex items-center gap-0.5 flex-shrink-0 opacity-0 group-hover:opacity-100 transition-opacity">
                    <button
                      onClick={(e) => { e.stopPropagation(); startEdit(session.id, getSessionTitle(session)) }}
                      className="w-5 h-5 flex items-center justify-center rounded transition-colors"
                      style={{ color: colors.textDim }}
                      onMouseEnter={(e) => { e.currentTarget.style.backgroundColor = `${colors.accent}20`; e.currentTarget.style.color = colors.accent }}
                      onMouseLeave={(e) => { e.currentTarget.style.backgroundColor = 'transparent'; e.currentTarget.style.color = colors.textDim }}
                      title="重命名"
                    >
                      <svg className="w-3 h-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                        <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"></path>
                        <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"></path>
                      </svg>
                    </button>
                    <button
                      onClick={(e) => { e.stopPropagation(); deleteSession(session.id) }}
                      className="w-5 h-5 flex items-center justify-center rounded transition-colors"
                      style={{ color: colors.textDim }}
                      onMouseEnter={(e) => { e.currentTarget.style.backgroundColor = `${colors.red}20`; e.currentTarget.style.color = colors.red }}
                      onMouseLeave={(e) => { e.currentTarget.style.backgroundColor = 'transparent'; e.currentTarget.style.color = colors.textDim }}
                      title="删除会话"
                    >
                      <svg className="w-3 h-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                        <polyline points="3 6 5 6 21 6"></polyline>
                        <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
                      </svg>
                    </button>
                  </div>
                </div>
              </div>
            )
          })
        )}
      </div>
    </div>
  )
}
