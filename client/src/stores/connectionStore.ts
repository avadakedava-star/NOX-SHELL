import { create } from 'zustand'
import type { SSHConnection, ServerStatus } from '../types'
import { ConnectionStatus } from '../types'
import * as sshApi from '../api/sshConnection'
import { getBaseUrl, setBaseUrl as setRequestBaseUrl } from '../api/request'
import type { SshConnectionDTO, SshConnectionPayload } from '../api/sshConnection'

interface ConnectionStore {
  // 连接列表
  connections: SSHConnection[]
  // 当前选中的连接
  currentConnectionId: string | null
  // 连接ID -> 终端会话ID 映射
  terminalSessions: Record<string, string>
  // 服务端状态
  serverStatus: ServerStatus
  // 列表加载中
  loading: boolean
  // 服务端地址
  serverUrl: string
  // 操作错误信息
  error: string | null

  // 设置服务端地址
  setServerUrl: (url: string) => void

  // 获取连接列表
  fetchConnections: (userId?: string) => Promise<void>
  // 创建连接
  createConnection: (payload: SshConnectionPayload) => Promise<boolean>
  // 更新连接
  updateConnection: (payload: SshConnectionPayload) => Promise<boolean>
  // 删除连接
  removeConnection: (id: string) => Promise<void>
  // 选中连接
  selectConnection: (id: string | null) => void
  // 获取单个连接详情
  getConnectionDetail: (id: string) => SSHConnection | undefined
  // 更新服务端状态
  setServerStatus: (status: Partial<ServerStatus>) => void
  // 清除错误
  clearError: () => void
  // 建立 SSH 连接
  connect: (id: string) => Promise<boolean>
  // 断开 SSH 连接
  disconnect: (id: string) => Promise<boolean>

  // ===== 终端会话 =====
  /** 设置连接对应的终端会话ID */
  setTerminalSession: (connectionId: string, sessionId: string | null) => void
  /** 获取当前选中连接的终端会话ID */
  getCurrentTerminalSession: () => string | null

  // ===== 心跳检测 =====
  /** 启动心跳：定期检查已连接的 SSH 状态 */
  startHeartbeat: () => void
  /** 停止心跳 */
  stopHeartbeat: () => void
}

/** 后端 DTO → 前端模型 */
function dtoToConnection(dto: SshConnectionDTO): SSHConnection {
  return {
    id: dto.connectionId,
    name: dto.connectionName,
    host: dto.host,
    port: dto.port,
    username: dto.username,
    authType: dto.authType,
    status: dto.status,
    createdAt: new Date(dto.createdAt).getTime(),
    updatedAt: dto.updatedAt ? new Date(dto.updatedAt).getTime() : Date.now(),
  }
}

let heartbeatTimer: ReturnType<typeof setTimeout> | null = null
const HEARTBEAT_BASE_MS = 15000
const HEARTBEAT_MAX_MS = 120000
let heartbeatDelay = HEARTBEAT_BASE_MS

export const useConnectionStore = create<ConnectionStore>((set, get) => ({
  connections: [],
  currentConnectionId: null,
  terminalSessions: {},
  serverStatus: {
    connected: false,
    url: 'http://localhost:8091',
  },
  serverUrl: getBaseUrl(),
  loading: false,
  error: null,

  fetchConnections: async (userId = 'default') => {
    set({ loading: true, error: null })
    try {
      const res = await sshApi.getConnectionList(userId)
      if (res.code === '0000' && res.data) {
        const connections = res.data.map(dtoToConnection)
        set({ connections, serverStatus: { ...get().serverStatus, connected: true } })
      } else {
        set({ error: res.info || '获取连接列表失败' })
      }
    } catch {
      set({ error: '网络错误，无法连接服务器' })
    } finally {
      set({ loading: false })
    }
  },

  createConnection: async (payload) => {
    set({ error: null })
    try {
      const res = await sshApi.createConnection(payload)
      if (res.code === '0000' && res.data) {
        // 创建成功后刷新列表
        await get().fetchConnections()
        // 自动选中新创建的连接
        const newConn = dtoToConnection(res.data)
        set({ currentConnectionId: newConn.id })
        return true
      }
      set({ error: res.info || '创建连接失败' })
      return false
    } catch {
      set({ error: '网络错误，无法连接服务器' })
      return false
    }
  },

  updateConnection: async (payload) => {
    set({ error: null })
    try {
      const res = await sshApi.updateConnection(payload)
      if (res.code === '0000' && res.data) {
        // 更新成功后刷新列表
        await get().fetchConnections()
        return true
      }
      set({ error: res.info || '更新连接失败' })
      return false
    } catch {
      set({ error: '网络错误，无法连接服务器' })
      return false
    }
  },

  removeConnection: async (id) => {
    set({ error: null })
    try {
      const res = await sshApi.deleteConnection(id)
      if (res.code === '0000') {
        set((state) => {
          const terminalSessions = { ...state.terminalSessions }
          delete terminalSessions[id]
          return {
            connections: state.connections.filter((c) => c.id !== id),
            currentConnectionId: state.currentConnectionId === id ? null : state.currentConnectionId,
            terminalSessions,
          }
        })
      } else {
        set({ error: res.info || '删除连接失败' })
      }
    } catch {
      set({ error: '网络错误，无法连接服务器' })
    }
  },

  selectConnection: (id) => set({ currentConnectionId: id }),

  getConnectionDetail: (id) => {
    return get().connections.find((c) => c.id === id)
  },

  setServerStatus: (status) =>
    set((state) => ({
      serverStatus: { ...state.serverStatus, ...status },
    })),

  setServerUrl: (url: string) => {
    setRequestBaseUrl(url)
    set({ serverUrl: url, serverStatus: { ...get().serverStatus, url } })
  },

  clearError: () => set({ error: null }),

  connect: async (id) => {
    set({ error: null })
    try {
      const res = await sshApi.connect(id)
      if (res.code === '0000') {
        // 更新本地状态为已连接
        set((state) => ({
          connections: state.connections.map((c) =>
            c.id === id ? { ...c, status: 1 } : c
          ),
        }))
        return true
      }
      set({ error: res.info || '连接失败' })
      return false
    } catch {
      set({ error: '网络错误，无法连接服务器' })
      return false
    }
  },

  disconnect: async (id) => {
    set({ error: null })
    try {
      const res = await sshApi.disconnect(id)
      if (res.code === '0000') {
        // 更新本地状态为未连接，并清理终端会话ID
        set((state) => {
          const terminalSessions = { ...state.terminalSessions }
          delete terminalSessions[id]
          return {
            connections: state.connections.map((c) =>
              c.id === id ? { ...c, status: 0 } : c
            ),
            terminalSessions,
          }
        })
        return true
      }
      set({ error: res.info || '断开连接失败' })
      return false
    } catch {
      set({ error: '网络错误，无法连接服务器' })
      return false
    }
  },

  // ===== 终端会话 =====
  setTerminalSession: (connectionId, sessionId) =>
    set((state) => {
      const terminalSessions = { ...state.terminalSessions }
      if (sessionId) {
        terminalSessions[connectionId] = sessionId
      } else {
        delete terminalSessions[connectionId]
      }
      return { terminalSessions }
    }),

  getCurrentTerminalSession: () => {
    const state = get()
    if (!state.currentConnectionId) return null
    return state.terminalSessions[state.currentConnectionId] || null
  },

  // ===== 心跳检测 =====
  // 通过 check_connection 接口对已连接的 SSH 进行真实探活，
  // 后端会同步纠正 DB 状态并清理断开的 Session。
  // 正常时 15s 间隔；后端不可达时指数退避（上限 120s），恢复后回到 15s。
  startHeartbeat: () => {
    if (heartbeatTimer) return // 已启动

    const tick = async () => {
      const state = get()
      const activeConns = state.connections.filter((c) => c.status === ConnectionStatus.CONNECTED)
      if (activeConns.length === 0) {
        heartbeatDelay = HEARTBEAT_BASE_MS
        heartbeatTimer = setTimeout(tick, heartbeatDelay)
        return
      }

      let hadError = false
      for (const conn of activeConns) {
        try {
          const res = await sshApi.checkConnection(conn.id)
          if (res.code === '0000' && res.data != null) {
            get().setServerStatus({connected:true})
            const latestStatus = res.data
            if (latestStatus !== conn.status) {
              set((s) => ({
                connections: s.connections.map((c) =>
                  c.id === conn.id ? { ...c, status: latestStatus } : c
                ),
              }))
            }
          }
          else {
            hadError=true
            get().setServerStatus({connected:false})
          }
        } catch {
          hadError = true
          // 只能确认接口不可达，保留 SSH 和终端状态，下一轮继续探测。
          get().setServerStatus({connected:false})
        }
      }

      // 成功 → 回到基础间隔；失败 → 指数退避
      heartbeatDelay = hadError
        ? Math.min(heartbeatDelay * 2, HEARTBEAT_MAX_MS)
        : HEARTBEAT_BASE_MS
      heartbeatTimer = setTimeout(tick, heartbeatDelay)
    }

    heartbeatTimer = setTimeout(tick, heartbeatDelay)
  },

  stopHeartbeat: () => {
    if (heartbeatTimer) {
      clearTimeout(heartbeatTimer)
      heartbeatTimer = null
    }
    heartbeatDelay = HEARTBEAT_BASE_MS
  },
}))
