'use strict'

// Reusable local fixture. Sources stay on the network disk; runtime data stays local.
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { spawn } = require('node:child_process')
const mineflayer = require('mineflayer')

const root = path.resolve(process.argv[2] || '')
const java = process.argv[3]
const python = process.argv[4]
const platform = process.argv[5] || 'Paper'
assert(['Paper', 'Folia'].includes(platform), 'Test platform must be Paper or Folia')
assert(root.startsWith('C:\\Users\\artyo\\Documents\\Codex\\nordauth-test-'))
assert(java && python, 'Pass Java and Python executable paths')
const serverDir = path.join(root, 'server')
const db = path.join(serverDir, 'plugins', 'NordAuth', 'authme.db')
const configPath = path.join(serverDir, 'plugins', 'NordAuth', 'config.yml')
const baseConfig = fs.readFileSync(path.join(__dirname, '..', 'src', 'main', 'resources', 'config.yml'), 'utf8')
const results = []
const runToken = Date.now().toString(36).slice(-5)
const clients = new Set()
let server
let databaseLock
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

async function until(predicate, description, timeout = 15000) {
  const start = Date.now()
  while (!predicate()) {
    if (Date.now() - start > timeout) throw new Error('Timeout: ' + description)
    await sleep(50)
  }
}

function pass(description) {
  results.push(description)
  console.log('PASS: ' + description)
}

async function startServer({ queueWait = 10000, badDatabase = false, loginTimeout = 60 } = {}) {
  fs.mkdirSync(path.dirname(configPath), { recursive: true })
  let config = baseConfig.replace('maximum-queued-requests: 128', 'maximum-queued-requests: 1')
    .replace('maximum-queue-wait-millis: 10000', 'maximum-queue-wait-millis: ' + queueWait)
    .replace('login-timeout-seconds: 60', 'login-timeout-seconds: ' + loginTimeout)
    .replace('reminder-interval-seconds: 5', 'reminder-interval-seconds: 1')
  if (badDatabase) {
    fs.mkdirSync(path.join(serverDir, 'not-a-database'), { recursive: true })
    config = config.replace('file: plugins/NordAuth/authme.db', 'file: not-a-database')
  }
  fs.writeFileSync(configPath, config)
  const child = spawn(java, ['-Dterminal.jline=false', '-Dterminal.ansi=false', '-Xms256M', '-Xmx1200M', '-jar', 'server.jar', 'nogui'], {
    cwd: serverDir, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe']
  })
  server = { child, output: '', exited: false, code: null }
  const instance = server
  child.stdout.on('data', b => { instance.output += b.toString() })
  child.stderr.on('data', b => { instance.output += b.toString() })
  child.on('exit', code => { instance.exited = true; instance.code = code })
  child.on('error', e => { instance.output += String(e); instance.exited = true })
  if (badDatabase) {
    await until(() => instance.exited, 'failed initialization shuts down Paper', 90000)
    assert.match(instance.output, /NordAuth initialization failed; stopping server/)
    assert.match(instance.output, /Stopping server/)
    pass('Database initialization failure stops the server instead of disabling authentication')
    return
  }
  await until(() => /Done \(/.test(instance.output) || instance.exited, 'Paper startup', 120000)
  assert(!instance.exited, instance.output.slice(-4000))
  assert.match(instance.output, /NordAuth enabled with AuthMe-compatible SQLite storage/)
  assert.match(instance.output, new RegExp('Loading ' + platform + ' 26\\.2'))
  assert.match(instance.output, /Starting Minecraft server on 127\.0\.0\.1:25585/)
  console.log('LOCAL ' + platform + ' ready, PID ' + child.pid)
}

async function stopServer() {
  for (const client of clients) if (!client.ended) client.bot.quit()
  if (!server || server.exited) return
  server.child.stdin.write('stop\n')
  await until(() => server.exited, 'normal Paper shutdown', 45000)
}

function connect(username) {
  username = username.replace('NASecurity', 'NA' + runToken)
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25585, username,
    auth: 'offline', version: '26.2', hideErrors: true, checkTimeoutInterval: 30000 })
  const client = { bot, username, messages: [], ended: false, kicked: null, errors: [] }
  clients.add(client)
  bot.on('messagestr', message => client.messages.push(message))
  bot.on('kicked', reason => { client.kicked = typeof reason === 'string' ? reason : JSON.stringify(reason) })
  bot.on('error', e => client.errors.push(String(e)))
  bot.on('end', () => { client.ended = true })
  return client
}

async function message(client, regex, from = 0) {
  await until(() => client.messages.slice(from).some(m => regex.test(m)) || client.ended,
    client.username + ' receives ' + regex)
  assert(client.messages.slice(from).some(m => regex.test(m)),
    client.username + ': ' + JSON.stringify({ messages: client.messages, kicked: client.kicked, errors: client.errors }))
}

async function command(client, text, expected) {
  const mark = client.messages.length
  client.bot.chat(text)
  await message(client, expected, mark)
}

async function disconnect(client) {
  client.bot.quit()
  await until(() => client.ended, 'client disconnect')
  await sleep(250)
}

async function account(username, password) {
  const c = connect(username)
  await message(c, /Please register/)
  await command(c, '/register ' + password + ' ' + password, /Account registered successfully/)
  return c
}

async function acquireLock() {
  const child = spawn(python, [path.join(__dirname, 'sqlite-lock.py'), db, root],
    { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] })
  let output = ''
  let exited = false
  child.stdout.on('data', b => { output += b.toString() })
  child.stderr.on('data', b => { output += b.toString() })
  child.on('exit', () => { exited = true })
  databaseLock = { child, isExited: () => exited }
  await until(() => output.includes('LOCK_READY') || exited, 'SQLite lock')
  assert(output.includes('LOCK_READY'), output)
}

async function releaseLock() {
  if (!databaseLock) return
  databaseLock.child.stdin.end('\n')
  await until(databaseLock.isExited, 'SQLite unlock')
  databaseLock = null
}

async function main() {
  if (process.argv[6] === 'admin-only') {
    await startServer()
    let admin = await account('NASecurityAdmin', 'TestAdminInitial29')
    const mark = server.output.length
    server.child.stdin.write('resetpassword ' + admin.username + ' TestAdminConsole29\n')
    await until(() => server.output.slice(mark).includes('was reset by'), 'console reset callback')
    await disconnect(admin)
    admin = connect('NASecurityAdmin')
    await message(admin, /Please log in/)
    await command(admin, '/login TestAdminInitial29', /Incorrect password/)
    await command(admin, '/login TestAdminConsole29', /Successfully logged in/)
    pass('Console password reset returns through the global scheduler and preserves verification')
    const opMark = server.output.length
    server.child.stdin.write('natestgrant ' + admin.username + '\n')
    await until(() => server.output.slice(opMark).includes('NORD_AUTH_TEST_PERMISSION_GRANTED ' + admin.username),
      'synthetic admin permission')
    await command(admin, '/resetpassword ' + admin.username + ' TestAdminPlayer29', /has been reset/)
    await disconnect(admin)
    admin = connect('NASecurityAdmin')
    await message(admin, /Please log in/)
    await command(admin, '/login TestAdminPlayer29', /Successfully logged in/)
    pass('Player admin password reset returns to its entity scheduler')
    await stopServer()
    const log = fs.readFileSync(path.join(serverDir, 'logs', 'latest.log'), 'utf8')
    assert(!/TestAdminInitial29|TestAdminConsole29|TestAdminPlayer29/.test(log), 'Admin test passwords leaked')
    pass('Administrative test passwords are absent from the server log')
    return
  }
  await startServer()
  let basic = connect('NASecurityBasic')
  await message(basic, /Please register/)
  await command(basic, '/help', /You must log in first/)
  await command(basic, 'Synthetic unauthenticated chat', /You must log in first/)
  await command(basic, '/register TestInitial29 TestInitial29', /Account registered successfully/)
  await command(basic, '/changepassword TestInitial29 TestChanged29', /password has been changed/)
  await disconnect(basic)
  basic = connect('NASecurityBasic')
  await message(basic, /Please log in/)
  await command(basic, '/login TestInitial29', /Incorrect password/)
  await command(basic, '/login TestChanged29', /Successfully logged in/)
  pass('Registration, pre-auth command restriction, password change, reconnect and password verification')

  const b = await account('NASecurityQueueB', 'TestQueue29')
  await disconnect(b)
  const pending = connect('NASecurityQueueB')
  await message(pending, /Please log in/)
  const c = await account('NASecurityQueueC', 'TestQueue29')
  await disconnect(c)
  const rejected = connect('NASecurityQueueC')
  await message(rejected, /Please log in/)
  await acquireLock()
  const basicMark = basic.messages.length
  basic.bot.chat('/changepassword TestChanged29 TestAfterQueue29')
  await sleep(250)
  const pendingMark = pending.messages.length
  pending.bot.chat('/login TestQueue29')
  await sleep(250)
  await command(rejected, '/login TestQueue29', /Authentication is temporarily unavailable/)
  await releaseLock()
  await message(basic, /password has been changed/, basicMark)
  await message(pending, /Successfully logged in/, pendingMark)
  await command(rejected, '/login TestQueue29', /Successfully logged in/)
  pass('Queue overload rejects login safely and a retry succeeds after capacity returns')

  await acquireLock()
  basic.bot.chat('/changepassword TestAfterQueue29 TestAfterQueue29')
  await sleep(250)
  const changeMark = pending.messages.length
  pending.bot.chat('/changepassword TestQueue29 TestQueueChanged29')
  await sleep(250)
  await command(rejected, '/changepassword TestQueue29 TestQueueChanged29', /Authentication is temporarily unavailable/)
  await releaseLock()
  await message(pending, /password has been changed/, changeMark)
  await command(rejected, '/changepassword TestQueue29 TestQueueChanged29', /password has been changed/)
  pass('Rejected password change clears its in-progress guard and can be retried')

  await acquireLock()
  let old = connect('NASecurityRace')
  await message(old, /Checking your account/)
  await sleep(350)
  await disconnect(old)
  const replacement = connect('NASecurityRace')
  await message(replacement, /Checking your account/)
  // Force the old request's SQLite busy_timeout failure before releasing the new request.
  await sleep(5200)
  assert(!replacement.ended, 'Old database error must not kick the new connection')
  await releaseLock()
  await message(replacement, /Please register/)
  assert(!replacement.ended)
  pass('Database error from an old connection does not kick a reconnected player with the same UUID')

  await acquireLock()
  const failed = connect('NASecurityDbFail')
  await message(failed, /Checking your account/)
  await until(() => failed.ended, 'current database error rejects unauthenticated player', 10000)
  assert.match(failed.kicked || '', /Authentication is temporarily unavailable/)
  await releaseLock()
  pass('Runtime database failure rejects the affected unauthenticated connection')
  await stopServer()
  const firstLog = fs.readFileSync(path.join(serverDir, 'logs', 'latest.log'), 'utf8')
  assert(!/TestInitial29|TestChanged29|TestAfterQueue29|TestQueue29|TestQueueChanged29/.test(firstLog),
    'Synthetic passwords must not leak to server logs')
  pass('Authentication commands do not leak test passwords into the server log')

  await startServer({ queueWait: 1000 })
  await acquireLock()
  const waiting = connect('NASecurityWaitA')
  await message(waiting, /Checking your account/)
  await sleep(250)
  const expired = connect('NASecurityWaitB')
  await message(expired, /Checking your account/)
  await sleep(1500)
  await releaseLock()
  await message(waiting, /Please register/)
  await until(() => expired.ended, 'expired waiting request is rejected')
  assert.match(expired.kicked || '', /Authentication is temporarily unavailable/)
  pass('Expired queued database request is rejected before execution')
  await stopServer()
  await startServer({ loginTimeout: 3 })
  const timedOut = connect('NASecurityTimeout')
  await message(timedOut, /Please register/)
  await until(() => timedOut.ended, 'entity-owned login timeout', 15000)
  assert.match(timedOut.kicked || '', /Login timed out/)
  assert(timedOut.messages.filter(m => /Please register/.test(m)).length >= 2,
    'Entity-owned reminders must run before the timeout')
  pass('Entity-owned authentication reminders and login timeout work')
  server.child.stdin.write('natestdisable\n')
  await until(() => server.exited, 'disabling NordAuth stops Paper', 45000)
  assert.match(server.output, /NordAuth was disabled while the server was running; stopping server for safety/)
  pass('Manually disabling NordAuth also shuts the server down safely')
  await startServer({ badDatabase: true })
}

main().then(() => {
  const report = { passed: results, total: results.length, server: platform + ' 26.2',
    fixture: '127.0.0.1:25585, synthetic accounts only' }
  fs.writeFileSync(path.join(root, process.argv[6] === 'admin-only'
    ? 'admin-integration-results.json' : 'integration-results.json'), JSON.stringify(report, null, 2))
  console.log('ALL ' + results.length + ' LOCAL INTEGRATION SCENARIOS PASSED')
}).catch(async error => {
  console.error(error.stack)
  if (server) fs.writeFileSync(path.join(root, 'integration-failure.log'), server.output)
  process.exitCode = 1
}).finally(async () => {
  await releaseLock().catch(() => {})
  await stopServer().catch(() => {
    if (server && !server.exited) server.child.kill()
  })
})
