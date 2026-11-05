import { useState, useEffect } from 'react'
import { useThemeStore } from '../stores/themeStore'
import { useConnectionStore } from '../stores/connectionStore'
import { ConnectionStatus } from '../types'
import { SSHConnectionModal } from './SSHConnectionModal'

type TabId = 'servers' | 'files' | 'sftp' | 'extensions'

interface LeftSidebarProps {
  activeTab: TabId
}

// 模拟文件树数据
const mockFileTree = [
  {
    name: '/',
    type: 'dir',
    expanded: true,
    children: [
      { name: 'home', type: 'dir', expanded: false, children: [] },
      { name: 'etc', type: 'dir', expanded: false, children: [] },
      { name: 'var', type: 'dir', expanded: false, children: [] },
      { name: 'usr', type: 'dir', expanded: true, children: [
        { name: 'bin', type: 'dir', children: [] },
        { name: 'local', type: 'dir', children: [] },
      ]},
      { name: 'tmp', type: 'dir', expanded: false, children: [] },
      { name: 'boot', type: 'dir', expanded: false, children: [] },
      { name: 'root', type: 'dir', expanded: false, children: [] },
      { name: 'opt', type: 'dir', expanded: false, children: [] },
    ]
  }
]

/** 连接状态对应颜色 */
function statusColor(status: number, colors: any): string {
  switch (status) {
    case ConnectionStatus.CONNECTED: return colors.green
    case ConnectionStatus.CONNECTING: return colors.yellow
    case ConnectionStatus.FAILED: return colors.red
    default: return colors.textDim
  }
}

/** 连接状态对应文字 */
function statusText(status: number): string {
  switch (status) {
    case ConnectionStatus.CONNECTED: return '已连接'
    case ConnectionStatus.CONNECTING: return '连接中'
    case ConnectionStatus.FAILED: return '连接失败'
    default: return '未连接'
  }
}

export function LeftSidebar({ activeTab }: LeftSidebarProps) {
  const { colors } = useThemeStore()
  const { connections, currentConnectionId, selectConnection, fetchConnections, removeConnection, connect, disconnect, loading, error, clearError } = useConnectionStore()
  const [expandedDirs, setExpandedDirs] = useState<Set<string>>(new Set(['/']))
  const [deletingId, setDeletingId] = useState<string | null>(null)
  const [connectingId, setConnectingId] = useState<string | null>(null)

  const currentConn = connections.find((c) => c.id === currentConnectionId)

  // 新增：管理弹窗状态
  const [modalOpen, setModalOpen] = useState(false)
  const [editingConn, setEditingConn] = useState<{ id: string; name: string; host: string; port: number; username: string; authType: number } | null>(null)

  // 首次挂载加载连接列表
  useEffect(() => {
    fetchConnections()
  }, [fetchConnections])

  const toggleDir = (path: string) => {
    const next = new Set(expandedDirs)
    if (next.has(path)) next.delete(path)
    else next.add(path)
    setExpandedDirs(next)
  }

  const handleDelete = async (e: React.MouseEvent, id: string) => {
    e.stopPropagation()
    setDeletingId(id)
    await removeConnection(id)
    setDeletingId(null)
  }

  const handleEdit = (e: React.MouseEvent, conn: { id: string; name: string; host: string; port: number; username: string; authType: number }) => {
    e.stopPropagation()
    setEditingConn(conn)
    setModalOpen(true)
  }

  const handleCloseModal = () => {
    setModalOpen(false)
    setEditingConn(null)
  }

  const handleToggleConnection = async (e: React.MouseEvent, conn: { id: string; status: number }) => {
    e.stopPropagation()
    setConnectingId(conn.id)
    if (conn.status === ConnectionStatus.CONNECTED) {
      await disconnect(conn.id)
    } else {
      // 建立连接时同步选中该连接，使右侧面板立即展示连接信息
      selectConnection(conn.id)
      await connect(conn.id)
    }
    setConnectingId(null)
  }

  const handleRefresh = () => {
    fetchConnections()
  }

  const renderTree = (nodes: any[], depth = 0, parentPath = '') => {
    return nodes.map((node) => {
      const path = parentPath ? `${parentPath}/${node.name}` : node.name
      const isExpanded = expandedDirs.has(path)
      const hasChildren = node.children && node.children.length > 0

      return (
        <div key={path}>
          <button
            onClick={() => hasChildren && toggleDir(path)}
            className="w-full flex items-center gap-1.5 px-2 py-1 text-xs hover:bg-white/5 transition-colors"
            style={{ paddingLeft: `${8 + depth * 16}px`, color: colors.textSecondary }}
          >
            {hasChildren ? (
              <span className="text-[10px] w-3 flex items-center justify-center" style={{ color: colors.textDim }}>
                <svg className="w-3 h-3 transition-transform" style={{ transform: isExpanded ? '' : 'rotate(-90deg)' }} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <polyline points="6 9 12 15 18 9"></polyline>
                </svg>
              </span>
            ) : (
              <span className="w-3" />
            )}
            <svg className="w-4 h-4 shrink-0" style={{ color: node.type === 'dir' ? colors.accent : colors.textDim }} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              {node.type === 'dir' ? (
                <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"></path>
              ) : (
                <><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path>
                <polyline points="14 2 14 8 20 8"></polyline></>
              )}
            </svg>
            <span className="truncate">{node.name}</span>
          </button>
          {isExpanded && node.children && renderTree(node.children, depth + 1, path)}
        </div>
      )
    })
  }

  // 标题映射
  const tabLabels: Record<TabId, string> = {
    servers: 'SSH 服务器',
    files: '文件目录',
    sftp: 'SFTP 文件传输',
    extensions: '扩展',
  }

  return (
    <div
      className="flex flex-col h-full min-w-0"
      style={{ backgroundColor: colors.bgSecondary }}
    >
      {/* Header */}
      <div
        className="h-9 flex items-center justify-between px-4 border-b flex-shrink-0"
        style={{ borderColor: colors.border }}
      >
        <span className="text-xs font-medium uppercase tracking-wider" style={{ color: colors.textSecondary }}>
          {tabLabels[activeTab]}
        </span>
        {activeTab === 'servers' && (
          <button
            onClick={handleRefresh}
            className="w-5 h-5 flex items-center justify-center rounded transition-colors"
            style={{ color: colors.textDim }}
            onMouseEnter={(e) => { e.currentTarget.style.color = colors.accent }}
            onMouseLeave={(e) => { e.currentTarget.style.color = colors.textDim }}
            title="刷新连接列表"
          >
            <svg className={`w-3.5 h-3.5 ${loading ? 'animate-spin' : ''}`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M23 4v6h-6M1 20v-6h6" />
              <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15" />
            </svg>
          </button>
        )}
        {currentConn && activeTab === 'files' && (
          <span className="ml-2 text-xs font-mono truncate" style={{ color: colors.textDim }}>
            — {currentConn.name}
          </span>
        )}
      </div>

      {/* Content */}
      <div className="flex-1 overflow-y-auto">
        {activeTab === 'servers' ? (
          <div className="p-2">
            {/* 错误提示 */}
            {error && (
              <div
                className="flex items-center justify-between gap-2 px-3 py-2 mb-2 rounded-md text-[11px]"
                style={{ backgroundColor: `${colors.red}15`, color: colors.red, border: `1px solid ${colors.red}30` }}
              >
                <span className="truncate">{error}</span>
                <button onClick={clearError} className="flex-shrink-0 opacity-60 hover:opacity-100">✕</button>
              </div>
            )}

            {/* 加载状态 */}
            {loading && connections.length === 0 ? (
              <div className="flex items-center justify-center py-10 gap-2">
                <svg className="w-4 h-4 animate-spin" style={{ color: colors.accent }} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M23 4v6h-6M1 20v-6h6" />
                  <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15" />
                </svg>
                <span className="text-xs" style={{ color: colors.textSecondary }}>加载连接列表...</span>
              </div>
            ) : connections.length === 0 ? (
              <div className="text-center py-10 px-4">
                <svg className="w-12 h-12 mx-auto mb-3 opacity-30" viewBox="0 0 24 24" fill="none" stroke={colors.textDim} strokeWidth="1.5">
                  <rect x="2" y="3" width="20" height="14" rx="2" ry="2"></rect>
                  <line x1="8" y1="21" x2="16" y2="21"></line>
                  <line x1="12" y1="17" x2="12" y2="21"></line>
                </svg>
                <p className="text-sm mb-1" style={{ color: colors.textSecondary }}>暂无 SSH 连接</p>
                <p className="text-xs" style={{ color: colors.textDim }}>点击上方按钮添加服务器</p>
              </div>
            ) : (
              <div className="space-y-0.5">
                {connections.map((conn) => {
                  const active = currentConnectionId === conn.id
                  const isDeleting = deletingId === conn.id
                  return (
                    <div
                      key={conn.id}
                      onClick={async () => {
                        selectConnection(conn.id)
                        // 未连接状态下点击，自动发起 SSH 连接
                        if (conn.status === ConnectionStatus.DISCONNECTED) {
                          setConnectingId(conn.id)
                          await connect(conn.id)
                          setConnectingId(null)
                        }
                      }}
                      className="w-full text-left px-3 py-2.5 rounded-md transition-all group cursor-pointer relative"
                      style={{
                        backgroundColor: active ? `${colors.accent}15` : 'transparent',
                        borderLeft: active ? `3px solid ${colors.accent}` : '3px solid transparent',
                      }}
                      onMouseEnter={(e) => { if (!active) e.currentTarget.style.backgroundColor = `${colors.textDim}08` }}
                      onMouseLeave={(e) => { if (!active) e.currentTarget.style.backgroundColor = 'transparent' }}
                    >
                      <div className="flex items-center gap-2 mb-0.5">
                        <span className="w-2 h-2 rounded-full flex-shrink-0" style={{ backgroundColor: statusColor(conn.status, colors) }} />
                        <span className="text-xs font-medium truncate" style={{ color: active ? colors.text : colors.textSecondary }}>
                          {conn.name}
                        </span>
                        <span className="text-[10px] px-1 py-0 rounded" style={{ color: statusColor(conn.status, colors), backgroundColor: `${statusColor(conn.status, colors)}15` }}>
                          {statusText(conn.status)}
                        </span>
                        {/* 连接/断开开关 */}
                        <button
                          onClick={(e) => handleToggleConnection(e, conn)}
                          disabled={connectingId === conn.id}
                          className={`w-8 h-4 rounded-full relative transition-colors flex-shrink-0 ${connectingId === conn.id ? 'opacity-50' : ''}`}
                          style={{
                            backgroundColor: conn.status === ConnectionStatus.CONNECTED ? colors.green : colors.textDim + '40',
                          }}
                          title={conn.status === ConnectionStatus.CONNECTED ? '断开连接' : '建立连接'}
                        >
                          <span
                            className="absolute top-0.5 w-3 h-3 rounded-full bg-white transition-all"
                            style={{
                              left: conn.status === ConnectionStatus.CONNECTED ? '18px' : '2px',
                            }}
                          />
                        </button>
                        {/* 编辑按钮 */}
                        <button
                          onClick={(e) => handleEdit(e, {
                            id: conn.id,
                            name: conn.name,
                            host: conn.host,
                            port: conn.port,
                            username: conn.username,
                            authType: conn.authType,
                          })}
                          className="opacity-0 group-hover:opacity-60 hover:!opacity-100 w-5 h-5 flex items-center justify-center rounded transition-opacity flex-shrink-0"
                          style={{ color: colors.textDim }}
                          title="编辑连接"
                        >
                          <svg className="w-3 h-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                            <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" />
                            <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z" />
                          </svg>
                        </button>
                        {/* 删除按钮 */}
                        <button
                          onClick={(e) => handleDelete(e, conn.id)}
                          className="ml-auto opacity-0 group-hover:opacity-60 hover:!opacity-100 w-5 h-5 flex items-center justify-center rounded transition-opacity flex-shrink-0"
                          style={{ color: colors.textDim }}
                          title="删除连接"
                        >
                          {isDeleting ? (
                            <svg className="w-3 h-3 animate-spin" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                              <path d="M23 4v6h-6M1 20v-6h6" />
                              <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15" />
                            </svg>
                          ) : (
                            <svg className="w-3 h-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                              <polyline points="3 6 5 6 21 6" />
                              <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
                            </svg>
                          )}
                        </button>
                      </div>
                      <div className="text-[11px] font-mono truncate pl-4" style={{ color: colors.textDim }}>
                        {conn.username}@{conn.host}:{conn.port}
                      </div>
                    </div>
                  )
                })}
              </div>
            )}
          </div>
        ) : activeTab === 'files' || activeTab === 'sftp' ? (
          <div>
            {currentConn ? (
              <div className="py-1">{renderTree(mockFileTree)}</div>
            ) : (
              <div className="text-center py-10 px-4">
                <div className="w-12 h-12 mx-auto mb-3 rounded-lg flex items-center justify-center text-sm font-bold" style={{ backgroundColor: `${colors.accent}20`, color: colors.accent }}>
                  SFTP
                </div>
                <p className="text-sm mb-1" style={{ color: colors.textSecondary }}>请先选择 SSH 连接</p>
                <p className="text-xs" style={{ color: colors.textDim }}>在「SSH 服务器」面板中添加并连接</p>
              </div>
            )}
          </div>
        ) : (
          <div className="text-center py-10 px-4">
            <svg className="w-12 h-12 mx-auto mb-3 opacity-30" viewBox="0 0 24 24" fill="none" stroke={colors.textDim} strokeWidth="1.5">
              <rect x="3" y="3" width="7" height="7"></rect>
              <rect x="14" y="3" width="7" height="7"></rect>
              <rect x="14" y="14" width="7" height="7"></rect>
              <rect x="3" y="14" width="7" height="7"></rect>
            </svg>
            <p className="text-sm" style={{ color: colors.textSecondary }}>扩展</p>
            <p className="text-xs mt-1" style={{ color: colors.textDim }}>敬请期待</p>
          </div>
        )}
      </div>

      {/* SSH 连接弹窗：新建 / 编辑 */}
      <SSHConnectionModal
        open={modalOpen}
        onClose={handleCloseModal}
        editingConnection={editingConn}
      />
    </div>
  )
}
