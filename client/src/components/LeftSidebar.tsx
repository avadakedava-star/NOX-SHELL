import { useState, useEffect, useMemo, useRef } from 'react'
import { useConnectionStore } from '../stores/connectionStore'
import { ConnectionStatus } from '../types'
import { SSHConnectionModal } from './SSHConnectionModal'
import { Icon } from './Icon'

type TabId = 'servers' | 'files' | 'sftp' | 'extensions'

interface LeftSidebarProps {
  activeTab: TabId
  onRequestOpenModal: () => void
}

const STATUS_META: Record<number, { text: string; tone: 'ok' | 'warn' | 'danger' | 'idle' }> = {
  [ConnectionStatus.CONNECTED]: { text: 'CONNECTED', tone: 'ok' },
  [ConnectionStatus.CONNECTING]: { text: 'CONNECTING', tone: 'warn' },
  [ConnectionStatus.FAILED]: { text: 'FAILED', tone: 'danger' },
  [ConnectionStatus.DISCONNECTED]: { text: 'OFFLINE', tone: 'idle' },
}

type Filter = 'all' | 'online' | 'offline'

export function LeftSidebar({ activeTab, onRequestOpenModal }: LeftSidebarProps) {
  const { connections, currentConnectionId, selectConnection, fetchConnections, removeConnection, connect, disconnect, loading, error, clearError } = useConnectionStore()
  const [search, setSearch] = useState('')
  const [filter, setFilter] = useState<Filter>('all')
  const [deletingId, setDeletingId] = useState<string | null>(null)
  const [connectingId, setConnectingId] = useState<string | null>(null)
  const searchRef = useRef<HTMLInputElement>(null)

  const [modalOpen, setModalOpen] = useState(false)
  const [editingConn, setEditingConn] = useState<{ id: string; name: string; host: string; port: number; username: string; authType: number } | null>(null)

  useEffect(() => { fetchConnections() }, [fetchConnections])

  // Ctrl/Cmd+K 聚焦搜索
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
        if (activeTab !== 'servers') return
        event.preventDefault()
        searchRef.current?.focus()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [activeTab])

  const counts = useMemo(() => {
    const online = connections.filter(c => c.status === ConnectionStatus.CONNECTED).length
    return { all: connections.length, online, offline: connections.length - online }
  }, [connections])

  const filteredConnections = useMemo(() => {
    const keyword = search.trim().toLowerCase()
    return connections.filter((c) => {
      if (filter === 'online' && c.status !== ConnectionStatus.CONNECTED) return false
      if (filter === 'offline' && c.status === ConnectionStatus.CONNECTED) return false
      if (!keyword) return true
      return `${c.name} ${c.host} ${c.username}`.toLowerCase().includes(keyword)
    })
  }, [connections, filter, search])

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
      selectConnection(conn.id)
      await connect(conn.id)
    }
    setConnectingId(null)
  }

  const tabLabels: Record<TabId, string> = {
    servers: 'SSH 服务器',
    files: '文件目录',
    sftp: 'SFTP 传输',
    extensions: '扩展',
  }

  const openCreate = () => onRequestOpenModal()

  return (
    <div className="nox-sidebar">
      <div className="nox-sidebar-head">
        <span className="u-caps">{tabLabels[activeTab]}</span>
        {activeTab === 'servers' ? (
          <div style={{ display: 'flex', alignItems: 'center', gap: 2 }}>
            <button className="nox-icon-btn" onClick={() => fetchConnections()} title="刷新服务器列表" aria-label="刷新服务器列表">
              <Icon.Refresh size={13} className={loading ? 'animate-spin' : undefined} />
            </button>
            <button className="nox-icon-btn" onClick={openCreate} title="添加服务器" aria-label="添加服务器">
              <Icon.Plus size={14} />
            </button>
          </div>
        ) : (
          <span className="u-mono" style={{ fontSize: 9, color: 'var(--ink-4)' }}>—</span>
        )}
      </div>

      {activeTab === 'servers' ? (
        <>
          <div className="nox-sidebar-tools">
            <label className="nox-search">
              <Icon.Search size={13} />
              <input
                ref={searchRef}
                aria-label="搜索服务器"
                placeholder="搜索名称、IP 或用户"
                value={search}
                onChange={e => setSearch(e.target.value)}
              />
              <kbd>Ctrl K</kbd>
            </label>
            <div className="nox-filters" role="group" aria-label="按连接状态筛选">
              {([
                ['all', '全部', counts.all],
                ['online', '在线', counts.online],
                ['offline', '离线', counts.offline],
              ] as const).map(([key, label, count]) => (
                <button key={key} data-active={filter === key} onClick={() => setFilter(key)} aria-pressed={filter === key}>
                  {label}<b>{count}</b>
                </button>
              ))}
            </div>
          </div>

          <div className="nox-list-head">
            <span className="u-caps">主机</span>
            <span className="u-mono" style={{ fontSize: 9, color: 'var(--ink-4)' }}>{counts.online} UP</span>
          </div>

          <div className="flex-1 overflow-y-auto" style={{ paddingBottom: 8 }}>
            {error && (
              <div className="inline-error" role="alert">
                <Icon.Alert size={13} style={{ flex: 'none', marginTop: 1 }} />
                <span style={{ flex: 1 }}>{/Internal Server Error|Failed to fetch|NetworkError/.test(error) ? '无法读取服务器列表，请检查后端服务与连接设置。' : error}</span>
                <button onClick={clearError} aria-label="关闭错误提示"><Icon.X size={12} /></button>
              </div>
            )}

            {loading && connections.length === 0 ? (
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '20px 16px', color: 'var(--ink-3)', fontSize: 11 }}>
                <span className="spinner" style={{ color: 'var(--signal)' }} />
                正在读取连接列表…
              </div>
            ) : connections.length === 0 ? (
              <div className="nox-empty" style={{ marginTop: 16 }}>
                <Icon.Server size={18} />
                <h3>{error ? '服务器列表暂不可用' : '还没有服务器'}</h3>
                <p>{error ? '服务恢复后，点击右上角刷新重新读取。' : '添加第一台机器，开始一次远程会话。'}</p>
                <button className="nox-button-primary" style={{ height: 30, padding: '0 12px', fontSize: 11, marginTop: 4 }} onClick={openCreate}>
                  <Icon.Plus size={12} /> 添加服务器
                </button>
              </div>
            ) : filteredConnections.length === 0 ? (
              <div className="nox-empty">
                <Icon.Search size={18} />
                <h3>没有匹配的服务器</h3>
                <p>换一个关键词，或把筛选项切回“全部”。</p>
                <button className="nox-inline-btn" onClick={() => { setSearch(''); setFilter('all') }}>清除筛选</button>
              </div>
            ) : (
              <div className="nox-host-list">
                {filteredConnections.map((conn) => {
                  const active = currentConnectionId === conn.id
                  const isDeleting = deletingId === conn.id
                  const isToggling = connectingId === conn.id
                  const meta = STATUS_META[conn.status] ?? STATUS_META[ConnectionStatus.DISCONNECTED]
                  const connected = conn.status === ConnectionStatus.CONNECTED
                  return (
                    <div
                      key={conn.id}
                      className="nox-host"
                      data-active={active}
                      role="button"
                      tabIndex={0}
                      onClick={() => {
                        selectConnection(conn.id)
                        if (conn.status === ConnectionStatus.DISCONNECTED) {
                          setConnectingId(conn.id)
                          void connect(conn.id).finally(() => setConnectingId(null))
                        }
                      }}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); selectConnection(conn.id) }
                      }}
                    >
                      <span
                        className="nox-dot"
                        data-tone={meta.tone === 'idle' ? undefined : meta.tone}
                        data-pulse={conn.status === ConnectionStatus.CONNECTING ? 'true' : undefined}
                        style={{ alignSelf: 'start', marginTop: 6, color: meta.tone === 'warn' ? 'var(--warn)' : undefined }}
                      />

                      <div style={{ minWidth: 0 }}>
                        <div className="nox-host-name">
                          <span className="u-truncate">{conn.name}</span>
                          {conn.authType === 2 && <Icon.Shield size={10} style={{ color: 'var(--ink-4)', flex: 'none' }} />}
                        </div>
                        <div className="nox-host-addr u-truncate">{conn.username}@{conn.host}:{conn.port}</div>
                      </div>

                      <div style={{ display: 'flex', alignItems: 'center', gap: 2 }}>
                        <span className="nox-host-actions">
                          <button
                            className="nox-icon-btn"
                            onClick={(e) => handleEdit(e, { id: conn.id, name: conn.name, host: conn.host, port: conn.port, username: conn.username, authType: conn.authType })}
                            title="编辑连接"
                            aria-label={`编辑 ${conn.name}`}
                          >
                            <Icon.Edit size={12} />
                          </button>
                          <button
                            className="nox-icon-btn"
                            data-tone="danger"
                            onClick={(e) => handleDelete(e, conn.id)}
                            disabled={isDeleting}
                            title="删除连接"
                            aria-label={`删除 ${conn.name}`}
                          >
                            {isDeleting ? <span className="spinner" /> : <Icon.Trash size={12} />}
                          </button>
                        </span>
                        <button
                          className="nox-icon-btn"
                          onClick={(e) => handleToggleConnection(e, conn)}
                          disabled={isToggling}
                          data-tone={connected ? 'danger' : undefined}
                          title={connected ? '断开连接' : '建立连接'}
                          aria-label={connected ? `断开 ${conn.name}` : `连接 ${conn.name}`}
                        >
                          {isToggling ? <span className="spinner" style={{ color: 'var(--warn)' }} /> : connected ? <Icon.PlugOff size={13} /> : <Icon.Plug size={13} />}
                        </button>
                      </div>

                      <span className="nox-host-state" data-status={conn.status} style={{ gridColumn: '2 / -1', marginTop: 2 }}>
                        {meta.text}
                      </span>
                    </div>
                  )
                })}
              </div>
            )}
          </div>

          <div style={{ padding: '0 12px 10px' }}>
            <button className="nox-add-host" onClick={openCreate}>
              <Icon.Plus size={12} /> 添加服务器
            </button>
          </div>
        </>
      ) : activeTab === 'extensions' ? (
        <div className="nox-empty" style={{ marginTop: 16 }}>
          <Icon.Puzzle size={18} />
          <h3>扩展尚未开放</h3>
          <p>这里会用于安装和配置额外能力。当前版本只提供 SSH 与 AI 助手。</p>
        </div>
      ) : (
        <div className="nox-empty" style={{ marginTop: 16 }}>
          <Icon.Folder size={18} />
          <h3>{activeTab === 'sftp' ? 'SFTP 尚未接入' : '文件浏览尚未接入'}</h3>
          <p>
            {activeTab === 'sftp'
              ? '文件传输通道还在开发中。现在可以用终端完成上传与下载。'
              : '远端文件树还没有可用接口。请先通过终端查看目录结构。'}
          </p>
        </div>
      )}

      <div className="nox-sidebar-foot">
        <span className="u-caps">{activeTab === 'servers' ? 'SSH / WORKSPACE' : 'NOX-SHELL'}</span>
        <p>{activeTab === 'servers' ? '每一条命令，都在你的掌控中。' : '工具只展示真实可用状态。'}</p>
      </div>

      <SSHConnectionModal open={modalOpen} onClose={handleCloseModal} editingConnection={editingConn} />
    </div>
  )
}
