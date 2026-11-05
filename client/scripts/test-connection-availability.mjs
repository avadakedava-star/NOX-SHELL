import { buildSync } from 'esbuild'
import { createRequire } from 'node:module'
import { runInNewContext } from 'node:vm'
import assert from 'node:assert/strict'
function bundle(entry,extra={}){
 const source=buildSync({entryPoints:[entry],bundle:true,platform:'node',format:'cjs',write:false,define:{'import.meta.env':JSON.stringify({DEV:true})},logLevel:'silent'}).outputFiles[0].text
 const module={exports:{}}
 runInNewContext(source,{module,exports:module.exports,require:createRequire(import.meta.url),console,process,AbortController,localStorage:{getItem:()=>null},setTimeout,clearTimeout,...extra})
 return module.exports
}
const {terminalReadState}=bundle('src/api/terminal.ts')
for(let n=0;n<5;n++)assert.equal(terminalReadState({code:'NETWORK_ERROR',info:'Failed to fetch',data:null}),'unavailable')
assert.equal(terminalReadState({code:'TIMEOUT',data:null}),'unavailable')
assert.equal(terminalReadState({code:'503',data:null}),'unavailable')
assert.equal(terminalReadState({code:'0000',data:{output:'prompt'}}),'ready')
assert.equal(terminalReadState({code:'0000',data:{output:'[连接已断开]'}}),'closed')
assert.equal(terminalReadState({code:'0002',info:'会话不存在',data:null}),'closed')
let mode='offline',serial=0
const timers=new Map(),calls=[]
const {useConnectionStore:store}=bundle('src/stores/connectionStore.ts',{
 setTimeout:(callback,delay)=>{const id=++serial;timers.set(id,{callback,delay});return id},
 clearTimeout:id=>timers.delete(id),
 fetch:async url=>{calls.push(url);if(mode==='offline')throw new TypeError('Failed to fetch');return new Response(JSON.stringify({code:'0000',data:mode==='closed'?0:1}),{headers:{'Content-Type':'application/json'}})}
})
store.setState({connections:[{id:'server',status:1}],currentConnectionId:'server',terminalSessions:{server:'terminal'},serverStatus:{connected:true,url:'local'}})
async function nextHeartbeat(){const [id,timer]=timers.entries().next().value;timers.delete(id);await timer.callback()}
store.getState().startHeartbeat()
await nextHeartbeat()
assert.equal(store.getState().connections[0].status,1)
assert.equal(store.getState().terminalSessions.server,'terminal')
assert.equal(store.getState().serverStatus.connected,false)
mode='online';await nextHeartbeat()
assert.equal(store.getState().serverStatus.connected,true)
assert.equal(store.getState().connections[0].status,1)
assert.equal(store.getState().terminalSessions.server,'terminal')
mode='closed';await nextHeartbeat()
assert.equal(store.getState().connections[0].status,0)
assert.ok(calls.every(url=>url.includes('check_connection')))
store.getState().stopHeartbeat()
console.log('连接状态定向测试通过：网络失败保留会话、恢复后继续探测、明确断连才更新状态、不自动请求断开SSH。')

