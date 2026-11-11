import { buildSync } from 'esbuild'
import { createRequire } from 'node:module'
import { runInNewContext } from 'node:vm'
import assert from 'node:assert/strict'

// 运行真实 store 和 chatStream，截获最终 HTTP 请求；不连接模型或远程服务器。
const bundle = buildSync({
  stdin: { contents: `
    export { useAgentStore } from './src/stores/agentStore';
    export { useConnectionStore } from './src/stores/connectionStore';
    export { setChatExecutionMode } from './src/api/agent';
  `, resolveDir: process.cwd(), loader: 'ts' },
  bundle: true, platform: 'node', format: 'cjs', write: false,
  define: { 'import.meta.env': JSON.stringify({ DEV: true }) }, logLevel: 'silent',
}).outputFiles[0].text
const module = { exports: {} }
const storage = new Map()
const requests = []
runInNewContext(bundle, {
  module, exports: module.exports, require: createRequire(import.meta.url), console, process,
  setTimeout, clearTimeout, setInterval, clearInterval, AbortController, TextDecoder, URLSearchParams,
  localStorage: {
    getItem: key => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key),
  },
  fetch: async (url, options) => {
    assert.match(url, /\/chat_stream$/)
    requests.push(JSON.parse(options.body))
    let returned = false
    return { ok: true, body: { getReader: () => ({
      read: async () => {
        if (returned) return { done: true }
        returned = true
        return { done: false, value: new TextEncoder().encode('data: {"event":"run_finished","status":"completed"}\n\n') }
      }, releaseLock() {}, cancel: async () => {},
    }) } }
  },
})
const { useAgentStore: agent, useConnectionStore: connection, setChatExecutionMode } = module.exports
function setup({ status = 1, selected = 'server-a', terminals = { 'server-a': 'terminal-a' }, beforeRequest } = {}) {
  connection.setState({ currentConnectionId: selected, connections: [{ id: 'server-a', status }, { id: 'server-b', status: 1 }], terminalSessions: terminals })
  agent.setState({
    currentAgentId: 'agent', currentSessionId: 'chat', isLoading: false,
    sessions: new Map([['chat', { id: 'chat', name: '测试', createdAt: 1, messages: [] }]]),
    ensureBackendSession: async () => { await beforeRequest?.(); return 'backend-chat' },
  })
}
async function sent(argument) {
  const count = requests.length
  await agent.getState().sendMessage('检查项目状态', argument)
  assert.equal(requests.length, count + 1)
  assert.equal(agent.getState().isLoading, false)
  return requests.at(-1)
}
for (const mode of ['standard', 'enhanced']) {
  setChatExecutionMode(mode)
  setup()
  assert.equal((await sent()).terminalSessionId, 'terminal-a', '调用方漏传时绑定当前已连接终端')
  assert.equal(requests.at(-1).executionMode, mode)
  setup()
  assert.equal((await sent('explicit-terminal')).terminalSessionId, 'explicit-terminal', '明确指定的操作目标不被当前选择覆盖')
  setup()
  assert.equal((await sent(null)).terminalSessionId, undefined, '明确纯聊天时不自动关联终端')
  setup({ status: 0 })
  assert.equal((await sent()).terminalSessionId, undefined, '已断开的连接不能使用残留编号')
  setup({ selected: null })
  assert.equal((await sent()).terminalSessionId, undefined, '未选服务器时不猜测其他终端')
  setup({ terminals: {} })
  assert.equal((await sent()).terminalSessionId, undefined, 'SSH 连上但终端尚未建立时保持无终端')
  setup({ terminals: { 'server-a': 'reconnected-terminal' } })
  assert.equal((await sent()).terminalSessionId, 'reconnected-terminal', '重连后取最新编号')
  setup({ beforeRequest: () => connection.setState({ currentConnectionId: 'server-b', terminalSessions: { 'server-b': 'terminal-b' } }) })
  assert.equal((await sent()).terminalSessionId, 'terminal-a', '等待期间切换服务器不会改变已经发起的任务目标')
}
console.log('终端绑定回归通过：普通/增强两种模式共16个请求，覆盖漏传、显式目标、纯聊天、断开、未选择、未建终端、重连和异步切换。')
