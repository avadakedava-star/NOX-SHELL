/**
 * HTTP 请求客户端
 * 封装 fetch，统一处理响应格式和错误
 *
 * 默认行为（dev 模式）：baseUrl = ''，走 Vite proxy
 * 用户在设置中修改服务端地址后：直接使用用户指定的地址，绕过 proxy
 * 生产模式（Tauri）：直连用户配置的地址
 */

/** 后端统一响应结构 */
export interface ApiResponse<T = unknown> {
  code: string
  info: string
  data: T | null
}

/** 默认服务端地址 */
const DEFAULT_SERVER_URL = 'http://localhost:8091'

/**
 * 服务端基础地址
 * - dev 模式默认空字符串（走 Vite proxy）
 * - 用户显式设置后覆盖为实际地址（直连）
 * - 生产模式从 localStorage 读取
 */
let baseUrl: string = import.meta.env.DEV
  ? ''
  : (localStorage.getItem('walissh_server_url') || DEFAULT_SERVER_URL)

/** 获取当前服务端地址（显示用，空字符串时返回默认值） */
export function getBaseUrl(): string {
  return baseUrl || DEFAULT_SERVER_URL
}

/** 获取实际请求基地址（dev 默认空字符串，走 Vite proxy） */
export function getRequestBaseUrl(): string {
  return baseUrl
}

/**
 * 设置服务端地址（持久化到 localStorage）
 *
 * dev 模式下：
 * - 传入空或默认地址 → baseUrl = ''（走 Vite proxy）
 * - 传入其他地址 → baseUrl = 该地址（直连，绕过 proxy）
 *
 * 这样用户在设置页修改的地址才能真正生效
 */
export function setBaseUrl(url: string): void {
  const trimmed = url.trim().replace(/\/+$/, '')
  if (import.meta.env.DEV) {
    // dev 模式：只有用户明确设置了非默认地址才直连，否则走 proxy
    baseUrl = (!trimmed || trimmed === DEFAULT_SERVER_URL) ? '' : trimmed
  } else {
    baseUrl = trimmed || DEFAULT_SERVER_URL
  }
  if (trimmed && trimmed !== DEFAULT_SERVER_URL) {
    localStorage.setItem('walissh_server_url', trimmed)
  } else {
    localStorage.removeItem('walissh_server_url')
  }
}

/** 请求超时（毫秒） */
const TIMEOUT_MS = 15000

/**
 * 通用请求方法
 */
async function request<T>(
  method: string,
  path: string,
  body?: unknown,
  params?: Record<string, string>,
): Promise<ApiResponse<T>> {
  // 拼接 query string
  let url = `${baseUrl}${path}`
  if (params) {
    const qs = Object.entries(params)
      .filter(([, v]) => v !== undefined && v !== null && v !== '')
      .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`)
      .join('&')
    if (qs) url += `?${qs}`
  }

  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS)

  try {
    const res = await fetch(url, {
      method,
      headers: body ? { 'Content-Type': 'application/json' } : undefined,
      body: body ? JSON.stringify(body) : undefined,
      signal: controller.signal,
    })

    if (!res.ok) {
      return { code: String(res.status), info: res.statusText, data: null }
    }

    return (await res.json()) as ApiResponse<T>
  } catch (err: any) {
    if (err?.name === 'AbortError') {
      return { code: 'TIMEOUT', info: '请求超时', data: null }
    }
    return { code: 'NETWORK_ERROR', info: err?.message || '网络错误', data: null }
  } finally {
    clearTimeout(timer)
  }
}

/** GET 请求 */
export function get<T>(path: string, params?: Record<string, string>) {
  return request<T>('GET', path, undefined, params)
}

/** POST 请求（JSON body + 可选 query params） */
export function post<T>(path: string, body?: unknown, params?: Record<string, string>) {
  return request<T>('POST', path, body, params)
}

/** PUT 请求 */
export function put<T>(path: string, body?: unknown, params?: Record<string, string>) {
  return request<T>('PUT', path, body, params)
}

/** DELETE 请求 */
export function del<T>(path: string, params?: Record<string, string>) {
  return request<T>('DELETE', path, undefined, params)
}
