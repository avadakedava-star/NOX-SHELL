/**
 * SSH 终端操作 API
 */
import { get, post } from './request'

const BASE = '/api/v1/ssh/terminal'

// ===== 类型定义 =====

/** 打开终端请求 */
export interface TerminalOpenPayload {
  connectionId: string
  cols?: number
  rows?: number
}

/** 打开终端响应 */
export interface TerminalOpenResponse {
  sessionId: string
  connectionId: string
  initialOutput: string
}

/** 执行命令请求（整行模式，兼容旧逻辑） */
export interface TerminalExecPayload {
  sessionId: string
  command: string
}

/** 执行命令响应 */
export interface TerminalExecResponse {
  output: string
}

/** 终端写入请求（原始输入，逐字节发送到 Shell） */
export interface TerminalWritePayload {
  sessionId: string
  input: string
}

/** 终端读取响应（Shell 缓冲输出） */
export interface TerminalReadResponse {
  output: string
}

/** 调整终端大小请求 */
export interface TerminalResizePayload {
  sessionId: string
  cols: number
  rows: number
}

// ===== API 方法 =====

/** 打开终端会话 */
export function openTerminal(payload: TerminalOpenPayload) {
  return post<TerminalOpenResponse>(`${BASE}/open`, payload)
}

/** 执行命令（整行模式） */
export function execCommand(payload: TerminalExecPayload) {
  return post<TerminalExecResponse>(`${BASE}/exec`, payload)
}

/** 写入原始输入到终端（逐字节模式，Shell 自身处理 echo） */
export function writeInput(payload: TerminalWritePayload) {
  return post<void>(`${BASE}/write`, payload)
}

/** 读取终端缓冲输出（轮询模式） */
export function readOutput(sessionId: string) {
  return get<TerminalReadResponse>(`${BASE}/read`, { sessionId })
}

/** 调整终端大小 */
export function resizeTerminal(payload: TerminalResizePayload) {
  return post<void>(`${BASE}/resize`, payload)
}

/** 关闭终端会话 */
export function closeTerminal(sessionId: string) {
  return post<void>(`${BASE}/close`, undefined, { sessionId })
}
/** 请求不可达不等于 SSH 断开；只有后端明确报告会话结束才清理终端。 */
export function terminalReadState(response: {code:string;info?:string;data:TerminalReadResponse|null}): 'ready'|'closed'|'unavailable' {
  if(response.code==='0000') return response.data?.output.includes('[连接已断开]') ? 'closed' : 'ready'
  if((response.code==='0002' || response.code==='ILLEGAL_PARAMETER') && /不存在|已关闭/.test(response.info || ''))return 'closed'
  return 'unavailable'
}
