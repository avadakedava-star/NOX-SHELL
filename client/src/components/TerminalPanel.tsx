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
import { WelcomePanel } from './WelcomePanel'
import { Icon } from './Icon'

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

export function TerminalPanel({ onOpenConnection, onOpenAssistant, onOpenServers }: { onOpenConnection: () => void; onOpenAssistant: () => void; onOpenServers: () => void }) {
  const { colors } = useThemeStore()
  const { currentConnectionId, connections, connect, disconnect, setTerminalSession, selectConnection } = useConnectionStore()
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
        blue: '#79a9ff',
        magenta: '#c084fc',
        cyan: '#22d3ee',
        white: colors.text,
        brightBlack: '#555555',
        brightRed: colors.red,
        brightGreen: colors.green,
        brightYellow: colors.yellow,
        brightBlue: '#99bcff',
        brightMagenta: '#c084fc',
        brightCyan: '#22d3ee',
        brightWhite: '#ffffff',
      },
      rows: 24,
      cols: 120,
      allowProposedApi: false,
    })
  }, [colors])

  // 更新已打开终端的配色，不重建 SSH 会话。
  useEffect(() => {
    terminalStates.current.forEach(({ terminal }) => {
      terminal.options.theme = {
        ...terminal.options.theme,
        background: colors.bgPrimary, foreground: colors.text,
        cursor: colors.accent, cursorAccent: colors.bgPrimary,
        selectionBackground: colors.accent + '50',
        red: colors.red, green: colors.green, yellow: colors.yellow,
        white: colors.text, brightRed: colors.red, brightGreen: colors.green,
        brightYellow: colors.yellow,
      }
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
    return <WelcomePanel onOpenConnection={onOpenConnection} onOpenAssistant={onOpenAssistant} />
  }

  // 始终渲染终端 wrapper，避免切换时 DOM 卸载导致其他连接的终端容器被销毁
  // 非 CONNECTED 状态用遮罩层覆盖，而不是替换整个结构
  const isConnected = currentConn.status === ConnectionStatus.CONNECTED
  const otherConnected = connections.filter((c) => c.status === ConnectionStatus.CONNECTED && c.id !== currentConn.id)

  const statusMeta = (() => {
    switch (currentConn.status) {
      case ConnectionStatus.CONNECTED:
        return { code: 'LINK UP', text: '已连接', tone: 'ok' as const, color: colors.green }
      case ConnectionStatus.CONNECTING:
        return { code: 'HANDSHAKE', text: '建立连接中', tone: 'warn' as const, color: colors.yellow }
      case ConnectionStatus.FAILED:
        return { code: 'LINK FAILED', text: '连接失败', tone: 'danger' as const, color: colors.red }
      default:
        return { code: 'NO SESSION', text: '未连接', tone: 'idle' as const, color: colors.textDim }
    }
  })()

  return (
    <div className="flex-1 flex flex-col min-w-0 min-h-0" style={{ backgroundColor: colors.bgPrimary }}>
      {/* 终端工具栏 —— 仪器条：主机身份 + 链路状态 + 会话动作 */}
      <div className="nox-term-bar">
        <div className="nox-term-id">
          <span className="nox-dot" data-tone={statusMeta.tone === 'idle' ? undefined : statusMeta.tone} data-pulse={currentConn.status === ConnectionStatus.CONNECTING ? 'true' : undefined} style={{ color: statusMeta.color }} />
          <strong className="u-truncate">{currentConn.name}</strong>
          <span className="u-mono">{currentConn.username}@{currentConn.host}:{currentConn.port}</span>
        </div>
        <span className="nox-term-status" data-status={currentConn.status}>
          {currentState?.disconnected ? 'SESSION CLOSED' : statusMeta.code}
        </span>
        <span style={{ flex: 1 }} />
        <div style={{ display: 'flex', alignItems: 'center', gap: 2 }}>
          {currentState?.disconnected ? (
            <button className="nox-button-primary" style={{ height: 26, padding: '0 10px', fontSize: 11 }} onClick={() => handleReconnect(currentConn.id)}>
              <Icon.Refresh size={12} /> 重新连接
            </button>
          ) : (
            <>
              <button
                className="nox-icon-btn"
                onClick={() => currentState?.terminal.clear()}
                disabled={!isConnected}
                title="清屏"
                aria-label="清屏"
              >
                <Icon.Eraser size={14} />
              </button>
              <button
                className="nox-icon-btn"
                data-tone="danger"
                onClick={async () => {
                  destroyTerminal(currentConn.id)
                  await disconnect(currentConn.id)
                }}
                disabled={!isConnected}
                title="断开连接"
                aria-label="断开连接"
              >
                <Icon.PlugOff size={14} />
              </button>
            </>
          )}
        </div>
      </div>

      {/* 会话标签：只列出真实存在的已连接 / 连接中主机 */}
      {(otherConnected.length > 0 || currentConn.status === ConnectionStatus.CONNECTED || currentConn.status === ConnectionStatus.CONNECTING) && (
        <div className="nox-term-tabs no-scrollbar" role="tablist" aria-label="SSH 会话">
          {[...otherConnected, currentConn]
            .filter((conn, index, list) => list.findIndex((c) => c.id === conn.id) === index)
            .map((conn) => {
              const active = conn.id === currentConn.id
              return (
                <button
                  key={conn.id}
                  role="tab"
                  aria-selected={active}
                  className="nox-term-tab"
                  data-active={active}
                  onClick={() => selectConnection(conn.id)}
                  title={`${conn.username}@${conn.host}`}
                >
                  <span
                    className="nox-dot"
                    data-tone={conn.status === ConnectionStatus.CONNECTED ? 'ok' : 'warn'}
                    data-pulse={conn.status === ConnectionStatus.CONNECTING ? 'true' : undefined}
                    style={{ color: conn.status === ConnectionStatus.CONNECTED ? colors.green : colors.yellow }}
                  />
                  <span>{conn.name}</span>
                </button>
              )
            })}
          <button className="nox-term-tab-add" onClick={onOpenServers} title="打开服务器列表">
            <Icon.Plus size={12} />
          </button>
        </div>
      )}

      {/*
        终端容器 —— position:relative 作为定位上下文
        所有连接的终端容器 position:absolute 堆叠，通过 visibility + z-index 切换
        visibility:hidden 不改变元素尺寸，避免 xterm canvas 重建导致的闪烁和多余 prompt
      */}
      <div className="nox-term-stage">
        {/* wrapper 始终存在，避免条件渲染导致 DOM 卸载 */}
        <div ref={wrapperRef} className="absolute inset-0" />

        {/* 非 CONNECTED 状态：遮罩层覆盖 */}
        {!isConnected && (
          <div className="nox-term-overlay">
            {currentConn.status === ConnectionStatus.CONNECTING ? (
              <div className="nox-term-card" role="status" aria-live="polite">
                <div className="nox-term-card-icon"><span className="spinner" style={{ width: 15, height: 15 }} /></div>
                <h2>正在建立安全通道</h2>
                <p className="nox-term-host">{currentConn.username}@{currentConn.host}:{currentConn.port}</p>
                <dl className="nox-term-meta">
                  <dt>STEP</dt><dd>SSH handshake</dd>
                  <dt>AUTH</dt><dd>{currentConn.authType === 2 ? 'private key' : 'password'}</dd>
                  <dt>HOST KEY</dt><dd>verifying</dd>
                </dl>
              </div>
            ) : currentConn.status === ConnectionStatus.FAILED ? (
              <div className="nox-term-card" role="alert">
                <div className="nox-term-card-icon" data-tone="danger"><Icon.Alert size={17} /></div>
                <h2>连接未能建立</h2>
                <p className="nox-term-host">{currentConn.username}@{currentConn.host}:{currentConn.port}</p>
                <p>无法与 {currentConn.name} 建立 SSH 会话。请确认主机可达、端口开放，并检查账号或密钥是否正确。</p>
                <div className="nox-term-card-actions">
                  <button className="nox-button-primary" onClick={() => connect(currentConn.id)}>
                    <Icon.Refresh size={13} /> 重试连接
                  </button>
                  <button className="nox-button-secondary" onClick={onOpenAssistant}>让 AI 协助排查</button>
                </div>
              </div>
            ) : (
              <div className="nox-term-card">
                <div className="nox-term-card-icon"><Icon.Terminal size={17} /></div>
                <h2>{currentConn.name}</h2>
                <p className="nox-term-host">{currentConn.username}@{currentConn.host}:{currentConn.port}</p>
                <dl className="nox-term-meta">
                  <dt>PORT</dt><dd>{currentConn.port}</dd>
                  <dt>AUTH</dt><dd>{currentConn.authType === 2 ? 'private key' : 'password'}</dd>
                  <dt>STATE</dt><dd>offline</dd>
                </dl>
                <div className="nox-term-card-actions">
                  <button className="nox-button-primary" onClick={() => connect(currentConn.id)}>
                    <Icon.Plug size={13} /> 建立连接
                  </button>
                  <button className="nox-button-secondary" onClick={onOpenAssistant}>先问 AI</button>
                </div>
              </div>
            )}
          </div>
        )}
      </div>

      {/* 会话中断提示 */}
      {currentState?.disconnected && isConnected && (
        <div
          className="flex items-center justify-between flex-shrink-0"
          style={{ padding: '7px 12px', borderTop: '1px solid var(--line)', background: 'color-mix(in srgb, var(--danger) 7%, var(--s1))', color: colors.red, fontSize: 11 }}
        >
          <span className="flex items-center gap-2"><Icon.Alert size={12} /> 终端会话已结束，日志仍保留在当前标签内</span>
          <button className="nox-inline-btn" style={{ color: colors.red, fontSize: 11 }} onClick={() => handleReconnect(currentConn.id)}>重新连接</button>
        </div>
      )}
    </div>
  )
}
