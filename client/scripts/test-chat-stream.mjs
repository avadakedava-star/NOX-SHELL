import { buildSync } from 'esbuild'
import { createRequire } from 'node:module'
import { runInNewContext } from 'node:vm'
import assert from 'node:assert/strict'
const bundle=buildSync({entryPoints:['src/api/agent.ts'],bundle:true,platform:'node',format:'cjs',write:false,define:{'import.meta.env':JSON.stringify({DEV:true})},logLevel:'silent'}).outputFiles[0].text
let response
const module={exports:{}}
runInNewContext(bundle,{module,exports:module.exports,require:createRequire(import.meta.url),console,setTimeout,clearTimeout,AbortController,TextDecoder,DOMException,localStorage:{getItem:()=>null},fetch:async()=>response})
const {chatStream}=module.exports
const encoder=new TextEncoder()
let cancelled=false
// SSE 的 data: 行和分隔符逐字分包；结束事件到达后，服务器故意不关闭连接。
const wire='event: text\ndata: {"event":"text","content":"你好"}\n\nevent: run_finished\ndata: {"event":"run_finished","status":"failed","stopReason":"MODEL_ERROR"}\n\nevent: text\ndata: {"event":"text","content":"late"}\n\n'
response=new Response(new ReadableStream({start(controller){for(const char of wire) controller.enqueue(encoder.encode(char))},cancel(){cancelled=true}}))
const received=[]
let timer
try {await Promise.race([chatStream({agentId:'a',userId:'u',message:'test'},e=>received.push(e)),new Promise((_,reject)=>{timer=setTimeout(()=>reject(new Error('收到结束事件后仍在等待连接关闭')),1500)})])}finally{clearTimeout(timer)}
assert.equal(received.length,2);assert.equal(received[0].content,'你好');assert.equal(received[1].event,'run_finished');assert.ok(cancelled)
response=new Response('data: {"event":"text","content":"partial"}\n\n')
const interrupted=[];await chatStream({agentId:'a',userId:'u',message:'test'},e=>interrupted.push(e))
assert.equal(interrupted.at(-1).stopReason,'disconnected')
console.log('SSE 定向测试通过：逐字分包、收到终态立即退出、忽略晚到事件、无终态断线。')
