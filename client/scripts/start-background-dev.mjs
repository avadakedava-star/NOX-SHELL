// 后台启动：不依赖交互 stdin，日志由进程自行写入，适用于独立 Windows 启动。
import { createWriteStream, appendFileSync } from 'node:fs'
const logFile = new URL('../../client-run.log', import.meta.url)
const errorFile = new URL('../../client-run.err.log', import.meta.url)
const output = createWriteStream(logFile, { flags: 'a' })
const errors = createWriteStream(errorFile, { flags: 'a' })
process.stdout.write = output.write.bind(output)
process.stderr.write = errors.write.bind(errors)
const stamp = () => new Date().toISOString()
appendFileSync(logFile, `\n[${stamp()}] frontend starting pid=${process.pid} parent=${process.ppid}\n`)
process.on('exit', code => appendFileSync(logFile, `[${stamp()}] frontend exited code=${code}\n`))
process.on('uncaughtExceptionMonitor', error => appendFileSync(errorFile, `[${stamp()}] ${error.stack}\n`))
process.env.CI = 'true'
const { createServer } = await import('vite')
const server = await createServer({ server: { host: '127.0.0.1', port: 5173, strictPort: true } })
await server.listen()
server.printUrls()
