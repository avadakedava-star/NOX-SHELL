import { buildSync } from 'esbuild'
import { createRequire } from 'node:module'
import { runInNewContext } from 'node:vm'
import assert from 'node:assert/strict'

const bundle = buildSync({
  entryPoints: ['src/api/agent.ts'], bundle: true, platform: 'node', format: 'cjs', write: false,
  define: { 'import.meta.env': JSON.stringify({ DEV: true }) }, logLevel: 'silent',
}).outputFiles[0].text
const module = { exports: {} }
const storage = new Map()
const sent = []
runInNewContext(bundle, {
  module, exports: module.exports, require: createRequire(import.meta.url), console,
  setTimeout, clearTimeout, AbortController, TextDecoder, URLSearchParams,
  localStorage: { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value) },
  fetch: async (url, options) => {
    sent.push(JSON.parse(options.body))
    return { ok: true, body: { getReader: () => ({ read: async () => ({ done: true }), releaseLock() {}, cancel: async () => {} }) } }
  },
})
const { getChatExecutionMode, setChatExecutionMode, chatStream } = module.exports
const payload = { agentId: 'a', userId: 'u', message: '检查' }
assert.equal(getChatExecutionMode(), 'standard')
await chatStream(payload, () => {})
assert.equal(sent.at(-1).executionMode, 'standard')
setChatExecutionMode('enhanced')
assert.equal(getChatExecutionMode(), 'enhanced')
await chatStream(payload, () => {})
assert.equal(sent.at(-1).executionMode, 'enhanced')
await chatStream({ ...payload, executionMode: 'standard' }, () => {})
assert.equal(sent.at(-1).executionMode, 'standard')
storage.set('nox_shell_chat_execution_mode', 'unknown')
assert.equal(getChatExecutionMode(), 'standard')
console.log('聊天模式测试通过：默认模式、保存选择、请求分流字段、显式覆盖与无效值回退。')
