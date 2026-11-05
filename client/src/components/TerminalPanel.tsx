import { useEffect, useRef, useCallback } from 'react'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import { WebglAddon } from '@xterm/addon-webgl'
import '@xterm/xterm/css/xterm.css'
import { useThemeStore } from '../stores/themeStore'
import { useConnectionStore } from '../stores/connectionStore'
import { useAgentStore } from '../stores/agentStore'
import { openTerminal, writeInput, readOutput, resizeTerminal, closeTerminal, terminalReadState } from '../api/terminal'
import { ConnectionStatus } from '../types'

/** 终端会话状态 */
interface TerminalSession {
  sessionId: string
  connectionId: string
}

/** 每个连接持有的终端状态 */
interface ConnectionTerminalState {
  terminal: Terminal
  fitAddon: FitAddon
  container: HTMLDivElement          // 每个连接独立的 DOM 容器
  session: TerminalSession | null
  pollTimer: ReturnType<typeof setInterval> | null
  onDataDisposable: { dispose(): void } | null
  disconnected: boolean
  connecting: boolean                 // 防止并发 openTerminal
  resizeObserver: ResizeObserver | null
  /** 上次发送给后端的 cols/rows，用于去重避免重复 resize 导致 shell 重绘 prompt */
  lastSentSize: { cols: number; rows: number }
  /** 输入缓冲：积攒字符后批量发送，减少网络往返 */
  inputBuffer: string[] | null
  /** 输入 flush 定时器 */
  inputFlushTimer: ReturnType<typeof setTimeout> | null
}

/** 轮询间隔（ms） */
const POLL_INTERVAL = 50

const TERMINAL_INSET = 8
const TERMINAL_BOTTOM_GAP = 24

export function TerminalPanel() {
  const { colors } = useThemeStore()
  const { currentConnectionId, connections, connect, disconnect, setTerminalSession } = useConnectionStore()
  const { createSession: createAgentSession } = useAgentStore()

  /** connectionId → 终端状态（每个连接独立 Terminal 实例，切换时保留日志） */
  const terminalStates = useRef<Map<string, ConnectionTerminalState>>(new Map())
  /** 外层容器，承载所有终端容器 */
  const wrapperRef = useRef<HTMLDivElement>(null)
  /** 当前活跃连接 ID，用于 ResizeObserver 判断是否发送 resize */
  const activeConnectionIdRef = useRef<string | null>(null)

  const currentConn = connections.find((c) => c.id === currentConnectionId)

  /** 创建 xterm Terminal 实例（共享配置） */
  const createTerminalInstance = useCallback(() => {
    return new Terminal({
      cursorBlink: true,
      cursorStyle: 'block',
      fontSize: 13,
      fontFamily: "'JetBrains Mono', 'Fira Code', Consolas, monospace",
      scrollback: 100000,  // 不限制存储，保留完整操作日志
      theme: {
        background: colors.bgPrimary,
        foreground: colors.text,
        cursor: colors.accent,
        cursorAccent: colors.bgPrimary,
        selectionBackground: colors.accent + '50',
        selectionForeground: '#ffffff',
        black: '#000000',
        red: colors.red,
        green: colors.green,
        yellow: colors.yellow,
        blue: colors.accent,
        magenta: '#c084fc',
        cyan: '#22d3ee',
        white: colors.text,
        brightBlack: '#555555',
        brightRed: colors.red,
        brightGreen: colors.green,
        brightYellow: colors.yellow,
        brightBlue: colors.accent,
        brightMagenta: '#c084fc',
        brightCyan: '#22d3ee',
        brightWhite: '#ffffff',
      },
      rows: 24,
      cols: 120,
      allowProposedApi: false,
    })
  }, [colors])

  /** 停止指定连接的轮询 */
  const stopPolling = useCallback((state: ConnectionTerminalState) => {
    if (state.pollTimer) {
      clearInterval(state.pollTimer)
      state.pollTimer = null
    }
  }, [])

  /** 标记终端断开并清理 */
  const markDisconnected = useCallback((state: ConnectionTerminalState, reason: string) => {
    if (state.disconnected) return
    state.disconnected = true
    stopPolling(state)

    // 后端已确认终端结束，只清理本地状态，不反向请求断开整条 SSH 连接。
    if(state.session)useConnectionStore.setState(current=>({
      connections:current.connections.map(conn=>conn.id===state.session!.connectionId ? {...conn,status:ConnectionStatus.DISCONNECTED}:conn),
    }))

    state.terminal.writeln(`\x1b[33m\r\n*** ${reason} ***\x1b[0m`)
    if (state.session) {
      setTerminalSession(state.session.connectionId, null)
    }
  }, [stopPolling, setTerminalSession])

  /** 启动轮询 */
  const startPolling = useCallback((state: ConnectionTerminalState) => {
    stopPolling(state)
    const sessionId = state.session!.sessionId
    let pending = false
    let nextAttempt = 0
    let unavailable = false
    const setReachable=(connected:boolean)=>{
      const store=useConnectionStore.getState()
      if(store.serverStatus.connected!==connected)store.setServerStatus({connected})
    }
    const pause=()=>{
      nextAttempt=Date.now()+1000
      setReachable(false)
      if(!unavailable){
        unavailable=true
        state.terminal.writeln('\r\n\x1b[33m接口暂时不可达，SSH 状态尚未确认；接口恢复后继续接收输出。\x1b[0m')
      }
    }
    state.pollTimer = setInterval(async () => {
      if(pending || state.disconnected || Date.now()<nextAttempt)return
      pending=true
      try {
        const response=await readOutput(sessionId)
        if(state.disconnected)return
        const status=terminalReadState(response)
        if(status==='closed'){markDisconnected(state,'后端确认终端会话已结束');return}
        if(status==='unavailable'){pause();return}
        setReachable(true)
        if(unavailable){state.terminal.writeln('\r\n\x1b[32m接口已恢复，继续接收终端输出。\x1b[0m');unavailable=false}
        if(response.data?.output)state.terminal.write(response.data.output)
      } catch { pause() }
      finally {pending=false}
    }, POLL_INTERVAL)
  }, [stopPolling, markDisconnected])

  /** 销毁指定连接的终端（断开/重连时调用，清空日志） */
  const destroyTerminal = useCallback((connectionId: string) => {
    const state = terminalStates.current.get(connectionId)
    if (!state) return

    // 停止轮询
    stopPolling(state)

    // 清理输入缓冲 flush 定时器
    if (state.inputFlushTimer) {
      clearTimeout(state.inputFlushTimer)
    }

    // 关闭后端会话
    if (state.session) {
      closeTerminal(state.session.sessionId).catch(() => {})
      setTerminalSession(state.session.connectionId, null)
    }

    // 释放输入监听
    state.onDataDisposable?.dispose()

    // 释放 ResizeObserver
    state.resizeObserver?.disconnect()

    // 销毁 xterm 实例
    state.terminal.dispose()

    // 移除 DOM 容器
    state.container.remove()

    terminalStates.current.delete(connectionId)
  }, [stopPolling])

  /** 创建并打开终端会话 */
  const openTerminalSession = useCallback(async (connectionId: string) => {
    // 如果已有终端状态，检查是否正在连接中
    const existingState = terminalStates.current.get(connectionId)
    if (existingState && existingState.connecting) return

    // 如果已有活跃会话（切换回来的场景），不需要重新打开
    if (existingState && existingState.session && !existingState.disconnected) return

    // 如果已断开（重连场景），先销毁旧终端清空日志
    if (existingState && existingState.disconnected) {
      destroyTerminal(connectionId)
    }

    // 确保外层容器存在
    if (!wrapperRef.current) return

    // 创建新的终端状态
    const term = createTerminalInstance()
    const fitAddon = new FitAddon()
    term.loadAddon(fitAddon)

    try {
      term.loadAddon(new WebglAddon())
    } catch {
      /* WebGL 不可用时降级 */
    }

    // 创建独立的 DOM 容器 —— position:absolute 堆叠，visibility 切换不会改变尺寸
    // 不在 xterm 容器本身使用 padding，避免 fitAddon 把 padding 也算进可用高度，导致最后一行被裁切
    const container = document.createElement('div')
    container.style.cssText = `position:absolute;top:${TERMINAL_INSET}px;left:${TERMINAL_INSET}px;right:${TERMINAL_INSET}px;bottom:${TERMINAL_BOTTOM_GAP}px;overflow:hidden;`
    wrapperRef.current.appendChild(container)

    term.open(container)
    fitAddon.fit()

    const state: ConnectionTerminalState = {
      terminal: term,
      fitAddon,
      container,
      session: null,
      pollTimer: null,
      onDataDisposable: null,
      disconnected: false,
      connecting: true,
      resizeObserver: null,
      lastSentSize: { cols: term.cols, rows: term.rows },
      inputBuffer: null,
      inputFlushTimer: null,
    }
    terminalStates.current.set(connectionId, state)

    // ResizeObserver：监听容器尺寸变化，仅在尺寸实际变化时发送 resize
    // position:absolute + inset:0 → 所有容器始终跟随 wrapper 尺寸
    // visibility 切换不会触发 resize（尺寸不变），避免切换时多余 prompt
    let resizeTimer: ReturnType<typeof setTimeout> | null = null
    const ro = new ResizeObserver(() => {
      if (resizeTimer) return
      resizeTimer = setTimeout(() => {
        resizeTimer = null
        if (!state.session) return
        fitAddon.fit()
        if (term.cols > 0 && term.rows > 0) {
          const { cols, rows } = term
          // 尺寸未变时跳过，避免重复 resize 导致 shell 重绘 prompt
          if (cols === state.lastSentSize.cols && rows === state.lastSentSize.rows) return
          state.lastSentSize = { cols, rows }
          resizeTerminal({ sessionId: state.session.sessionId, cols, rows }).catch(() => {})
        }
      }, 300)
    })
    ro.observe(container)
    state.resizeObserver = ro

    try {
      const res = await openTerminal({
        connectionId,
        cols: term.cols,
        rows: term.rows,
      })

      if (res.code !== '0000' || !res.data) {
        term.writeln(`\x1b[31m打开终端失败: ${res.info}\x1b[0m`)
        state.connecting = false
        return
      }

      const { sessionId, initialOutput } = res.data!
      state.session = { sessionId, connectionId }

      // 同步 terminalSessionId 到 store，供 AI 对话使用
      setTerminalSession(connectionId, sessionId)

      // 显示初始输出
      if (initialOutput) {
        term.write(initialOutput)
      }

      // 监听用户输入
      state.onDataDisposable = term.onData((data) => {
        if (state.disconnected || !state.session) return

        // 输入缓冲：积攒字符后批量发送，减少网络往返
        if (!state.inputBuffer) {
          state.inputBuffer = []
          state.inputFlushTimer = setTimeout(() => {
            if (state.inputBuffer && state.session && !state.disconnected) {
              const input = state.inputBuffer.join('')
              writeInput({ sessionId: state.session.sessionId, input })
                .catch((err) => {
                  term.writeln('\r\n\x1b[31m输入发送失败\x1b[0m')
                  console.error('writeInput error:', err)
                })
            }
            state.inputBuffer = null
            state.inputFlushTimer = null
          }, 10) // 10ms 内积攒字符后批量发送
        }
        state.inputBuffer.push(data)
      })

      // 启动输出轮询
      startPolling(state)
    } catch (err: any) {
      term.writeln(`\x1b[31m连接错误: ${err.message || '未知错误'}\x1b[0m`)
    } finally {
      state.connecting = false
    }
  }, [createTerminalInstance, destroyTerminal, startPolling])

  /**
   * 切换终端显示：visibility + z-index
   * 
   * 关键：用 visibility:hidden 代替 display:none
   * - visibility:hidden 不改变元素尺寸 → xterm canvas 尺寸不变 → 不触发 ResizeObserver
   * - display:none 让元素尺寸归零 → 恢复时 canvas 重建 → 闪烁 + ResizeObserver 触发 + shell 重绘 prompt
   */
  useEffect(() => {
    activeConnectionIdRef.current = currentConnectionId
    terminalStates.current.forEach((state, connId) => {
      const isActive = connId === currentConnectionId
      state.container.style.visibility = isActive ? 'visible' : 'hidden'
      state.container.style.zIndex = isActive ? '1' : '0'
      // 活跃终端：聚焦，确保键盘输入立即生效
      if (isActive && state.session && !state.disconnected) {
        requestAnimationFrame(() => {
          state.terminal.focus()
        })
      }
    })
  }, [currentConnectionId])

  /** 监听连接状态变化 */
  useEffect(() => {
    if (!currentConn) return

    const isConnected = currentConn.status === ConnectionStatus.CONNECTED
    const state = terminalStates.current.get(currentConn.id)

    if (isConnected) {
      // 已连接：创建或恢复终端，同时确保 AI 会话存在
      createAgentSession()
      if (!state) {
        openTerminalSession(currentConn.id)
      } else if (state.disconnected) {
        // 断开后再连接 → 销毁旧终端，重新创建（清空日志）
        destroyTerminal(currentConn.id)
        openTerminalSession(currentConn.id)
      }
      // 如果已有活跃终端（切换回来），什么都不做，日志保留
    } else {
      // 未连接/断开/失败：销毁终端，清空日志
      if (state) {
        destroyTerminal(currentConn.id)
      }
    }
  }, [currentConn, openTerminalSession, destroyTerminal, createAgentSession])

  /** 组件卸载：清理所有终端 */
  useEffect(() => {
    return () => {
      terminalStates.current.forEach((_, connId) => {
        destroyTerminal(connId)
      })
    }
  }, [destroyTerminal])

  /** 重新连接（销毁旧终端，清空日志，重新打开） */
  const handleReconnect = useCallback((connectionId: string) => {
    destroyTerminal(connectionId)
    openTerminalSession(connectionId)
  }, [destroyTerminal, openTerminalSession])

  // ===== 渲染 =====

  const currentState = currentConn ? terminalStates.current.get(currentConn.id) : undefined

  // 未选择连接：只显示空状态，不渲染 wrapper（没有终端需要保留）
  if (!currentConn) {
    return (
      <div className="flex-1 flex flex-col items-center justify-center min-w-0" style={{ backgroundColor: colors.bgPrimary }}>
        <div className="text-center">
          <div className="text-6xl mb-4">🖥️</div>
          <h2 className="text-lg font-medium mb-2" style={{ color: colors.text }}>
            未连接 SSH 服务器
          </h2>
          <p className="text-sm mb-6" style={{ color: colors.textDim }}>
            请在左侧选择或添加 SSH 连接
          </p>
        </div>
      </div>
    )
  }

  // 始终渲染终端 wrapper，避免切换时 DOM 卸载导致其他连接的终端容器被销毁
  // 非 CONNECTED 状态用遮罩层覆盖，而不是替换整个结构
  const isConnected = currentConn.status === ConnectionStatus.CONNECTED

  return (
    <div className="flex-1 flex flex-col min-w-0 min-h-0" style={{ backgroundColor: colors.bgPrimary }}>
      {/* 终端工具栏 —— 始终显示 */}
      <div className="h-9 flex items-center justify-between px-3 border-b flex-shrink-0" style={{ backgroundColor: colors.bgSecondary, borderColor: colors.border }}>
        <div className="flex items-center gap-2">
          <div className="w-2 h-2 rounded-full" style={{ backgroundColor: isConnected ? colors.green : colors.yellow }} />
          <span className="text-xs font-medium" style={{ color: colors.text }}>
            {currentConn.name}
          </span>
          <span className="text-[10px] font-mono" style={{ color: colors.textDim }}>
            {currentConn.username}@{currentConn.host}:{currentConn.port}
          </span>
          {currentState?.disconnected && (
            <span className="text-[10px] px-1.5 py-0.5 rounded" style={{ backgroundColor: colors.red + '20', color: colors.red }}>
              已断开
            </span>
          )}
        </div>
        <div className="flex items-center gap-1">
          {currentState?.disconnected ? (
            <button
              onClick={() => handleReconnect(currentConn.id)}
              className="px-2 py-1 rounded text-[11px] font-medium transition-colors"
              style={{ backgroundColor: colors.green, color: '#ffffff' }}
            >
              重新连接
            </button>
          ) : (
            <>
              <button
                onClick={() => currentState?.terminal.clear()}
                className="p-1.5 rounded hover:bg-white/10 transition-colors"
                style={{ color: colors.textDim }}
                title="清屏"
              >
                <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M3 6h18M19 6v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6m3 0V4a2 2 0 012-2h4a2 2 0 012 2v2" />
                </svg>
              </button>
              <button
                onClick={async () => {
                  destroyTerminal(currentConn.id)
                  await disconnect(currentConn.id)
                }}
                className="p-1.5 rounded hover:bg-white/10 transition-colors"
                style={{ color: colors.textDim }}
                title="断开连接"
              >
                <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M9 21H5a2 2 0 01-2-2V5a2 2 0 012-2h4" />
                  <polyline points="16 17 21 12 16 7" />
                  <line x1="21" y1="12" x2="9" y2="12" />
                </svg>
              </button>
            </>
          )}
        </div>
      </div>

      {/* 
        终端容器 —— position:relative 作为定位上下文
        所有连接的终端容器 position:absolute 堆叠，通过 visibility + z-index 切换
        visibility:hidden 不改变元素尺寸，避免 xterm canvas 重建导致的闪烁和多余 prompt
      */}
      <div className="flex-1 min-h-0 overflow-hidden" style={{ position: 'relative' }}>
        {/* wrapper 始终存在，避免条件渲染导致 DOM 卸载 */}
        <div ref={wrapperRef} className="absolute inset-0" />

        {/* 非 CONNECTED 状态：遮罩层覆盖 */}
        {!isConnected && (
          <div className="absolute inset-0 z-10 flex flex-col items-center justify-center" style={{ backgroundColor: colors.bgPrimary }}>
            {currentConn.status === ConnectionStatus.CONNECTING && (
              <div className="text-center">
                <div className="animate-spin text-4xl mb-4" style={{ color: colors.accent }}>⚙️</div>
                <p style={{ color: colors.text }}>正在连接 {currentConn.name}...</p>
              </div>
            )}
            {currentConn.status === ConnectionStatus.FAILED && (
              <div className="text-center">
                <div className="text-6xl mb-4">❌</div>
                <h2 className="text-lg font-medium mb-2" style={{ color: colors.red }}>连接失败</h2>
                <p className="text-sm mb-6" style={{ color: colors.textDim }}>
                  {currentConn.name} 无法连接
                </p>
                <button
                  onClick={() => connect(currentConn.id)}
                  className="px-4 py-2 rounded-lg text-sm font-medium transition-colors"
                  style={{ backgroundColor: colors.accent, color: '#ffffff' }}
                >
                  重试连接
                </button>
              </div>
            )}
            {currentConn.status === ConnectionStatus.DISCONNECTED && (
              <div className="text-center">
                <div className="text-6xl mb-4">🔌</div>
                <h2 className="text-lg font-medium mb-2" style={{ color: colors.text }}>
                  {currentConn.name}
                </h2>
                <p className="text-sm mb-2" style={{ color: colors.textDim }}>
                  {currentConn.username}@{currentConn.host}:{currentConn.port}
                </p>
                <p className="text-sm mb-6" style={{ color: colors.textDim }}>
                  点击下方按钮建立 SSH 连接
                </p>
                <button
                  onClick={() => connect(currentConn.id)}
                  className="px-4 py-2 rounded-lg text-sm font-medium transition-colors"
                  style={{ backgroundColor: colors.accent, color: '#ffffff' }}
                >
                  连接服务器
                </button>
              </div>
            )}
          </div>
        )}
      </div>

      {/* 断连提示 */}
      {currentState?.disconnected && (
        <div className="p-3 border-t flex items-center justify-between" style={{ backgroundColor: colors.bgSecondary, borderColor: colors.border }}>
          <span className="text-xs" style={{ color: colors.red }}>
            ⚠️ 终端连接已断开
          </span>
          <button
            onClick={() => handleReconnect(currentConn.id)}
            className="px-3 py-1 rounded text-xs font-medium transition-colors"
            style={{ backgroundColor: colors.accent, color: '#ffffff' }}
          >
            重新连接
          </button>
        </div>
      )}
    </div>
  )
}
