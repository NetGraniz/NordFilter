'use strict'
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { spawn } = require('node:child_process')
const mineflayer = require('mineflayer')
const root = path.resolve(process.argv[2] || '')
const java = process.argv[3]
const oldChat=process.argv.includes('--old-chat'),chatVersion=oldChat?'0.1.2':'0.1.3'
assert.equal(root, 'C:\\Users\\artyo\\Documents\\Codex\\nordfilter-test-20261004')
assert(java)
const results = [], children = [], clients = new Set()
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
let paper, proxy, seq = 0
const source = path.resolve(__dirname, '..')
const data = path.join(root,'paper/plugins/NordFilter/data.yml')
const words = path.join(root,'paper/plugins/NordFilter/banwords.yml')
const config = path.join(root,'paper/plugins/NordFilter/config.yml')
async function until(fn, label, timeout = 15000) {
  const start = Date.now()
  while (!await fn()) { if (Date.now() - start > timeout) throw Error('Timeout: ' + label); await sleep(50) }
}
function pass(name) { results.push(name); console.log('PASS: ' + name) }
function fixture() {
  const previous = 'C:\\Users\\artyo\\Documents\\Codex\\nordqueue-test-20261003'
  const secret = require('node:crypto').randomBytes(32).toString('hex')
  for (const sub of ['paper/plugins/NordChat', 'paper/plugins/NordFilter', 'paper/config', 'proxy/plugins/nordqueue'])
    fs.mkdirSync(path.join(root, sub), { recursive: true })
  for(const version of ['0.1.2','0.1.3']){
    const previousJar=path.join(root,'paper/plugins/NordChat-'+version+'.jar')
    if(fs.existsSync(previousJar))fs.renameSync(previousJar,path.join(root,'retired-chat-'+version+'-'+Date.now()+'.jar'))
  }
  const chatJar=oldChat?'Z:\\Minecraft server\\plugins\\NordChat-0.1.2.jar':'Z:\\Minecraft Plagins\\NordChat\\releases\\0.1.3\\NordChat-0.1.3.jar'
  fs.copyFileSync(chatJar,path.join(root,'paper/plugins/NordChat-'+chatVersion+'.jar'))
  fs.copyFileSync(path.join(__dirname, 'build/FilterTestProbe.jar'), path.join(root, 'paper/plugins/FilterTestProbe.jar'))
  fs.copyFileSync(path.join(source,'build/NordFilter-1.1.0.jar'),path.join(root,'paper/plugins/NordFilter-1.1.0.jar'))
  fs.copyFileSync('Z:\\Minecraft Plagins\\NordQueue\\releases\\1.1.2\\NordQueue-1.1.2.jar', path.join(root, 'proxy/plugins/NordQueue-1.1.2.jar'))
  fs.writeFileSync(path.join(root, 'paper/eula.txt'), 'eula=true\n')
  fs.writeFileSync(path.join(root, 'paper/server.properties'), [
    'server-ip=127.0.0.1','server-port=25676','online-mode=false','enforce-secure-profile=false',
    'max-players=20','view-distance=2','simulation-distance=2','enable-rcon=false','enable-query=false',
    'level-name=nordchat-synthetic-world','level-type=minecraft:flat','generate-structures=false',
    'spawn-protection=0','pause-when-empty-seconds=-1','gamemode=creative','force-gamemode=true','difficulty=peaceful',''].join('\n'))
  const original = fs.readFileSync(path.join(previous,'paper/config/paper-global.yml'), 'utf8')
  const velocitySection = /  velocity:\r?\n    enabled: (?:true|false)\r?\n    online-mode: (?:true|false)\r?\n    secret: [^\r\n]*/
  assert(velocitySection.test(original))
  fs.writeFileSync(path.join(root, 'paper/config/paper-global.yml'), original.replace(velocitySection,
    '  velocity:\n    enabled: true\n    online-mode: false\n    secret: "'+secret+'"'))
  fs.writeFileSync(path.join(root, 'paper/plugins/NordChat/config.yml'),
    'temporary-ignore-days: 7\nprivate-message-cooldown-millis: 500\nclickable-chat-names: true\n')
  fs.copyFileSync('Z:\\Minecraft Plagins\\NordFilter\\src\\main\\resources\\config.yml', path.join(root, 'paper/plugins/NordFilter/config.yml'))
  fs.writeFileSync(path.join(root, 'paper/plugins/NordFilter/banwords.yml'), 'words:\n  - syntheticforbidden\n')
  fs.writeFileSync(data,'{}\n')
  fs.writeFileSync(path.join(root,'paper/plugins/NordChat/players.yml'),'{}\n')
  fs.writeFileSync(path.join(root, 'proxy/local-test-forwarding.secret'), secret)
  let velocity = fs.readFileSync(path.join(previous,'proxy/velocity.toml'),'utf8')
    .replaceAll('25615','25675').replaceAll('25616','25676').replaceAll('25617','25677')
    .replace(/player-info-forwarding-mode = "[^"]+"/,'player-info-forwarding-mode = "modern"')
  fs.writeFileSync(path.join(root,'proxy/velocity.toml'),velocity)
  fs.writeFileSync(path.join(root, 'proxy/plugins/nordqueue/config.properties'),
    'main-capacity=20\nminimum-wait-seconds=1\ntransfer-interval-seconds=1\n')
  let limbo = fs.readFileSync(path.join(previous,'queue/settings.yml'),'utf8').replaceAll('25617','25677')
    .replace(/  type: (?:NONE|MODERN)/,'  type: MODERN').replace(/  secret: "[^"]+"/,'  secret: "'+secret+'"')
  assert(limbo.includes('type: MODERN'))
  fs.writeFileSync(path.join(root,'queue/settings.yml'),limbo)
}
function start(name, jar, heap) {
  const child = spawn(java, ['-Xms64M', '-Xmx' + heap, '-jar', jar, ...(name === 'paper' ? ['nogui'] : [])],
    { cwd: path.join(root, name), windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] })
  const handle = { child, name, output: '', exited: false, index: children.filter(c => c.name === name).length }
  children.push(handle)
  child.stdout.on('data', b => { handle.output += b.toString().replace(/\x1b\[[0-9;]*m/g, '') })
  child.stderr.on('data', b => { handle.output += b.toString().replace(/\x1b\[[0-9;]*m/g, '') })
  child.on('exit', () => { handle.exited = true })
  child.on('error', e => { handle.output += String(e); handle.exited = true })
  return handle
}
async function startPaper() {
  paper = start('paper', 'server.jar', '2G')
  await until(() => /Done \(/.test(paper.output) || paper.exited, 'Paper startup', 120000)
  assert(!paper.exited, paper.output.slice(-3000))
  assert(paper.output.includes('Enabling NordChat v'+chatVersion))
  assert.match(paper.output,/Enabling NordFilter v1\.1\.0/)
}
function connect(name) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25675, username: name, auth: 'offline',
    version: '26.2', hideErrors: true, checkTimeoutInterval: 30000 })
  const c = { bot, name, messages: [], originals: [], errors: [], ended: false, joinOffset: paper.output.length }
  clients.add(c)
  // Mineflayer retains the signed original in messagestr; a real client displays
  // the unsigned decorated component when Paper rewrites/renders a message.
  bot.on('message', m => c.messages.push(m.unsigned ? m.unsigned.toString() : m.toString()))
  bot._client.on('player_chat',packet=>c.originals.push(packet.plainMessage))
  bot.on('end', () => { c.ended = true })
  bot.on('error', e => c.errors.push(String(e)))
  bot.on('kicked', r => { c.kicked = JSON.stringify(r) })
  return c
}
async function joined(c) {
  await until(()=>paper.output.slice(c.joinOffset).includes(c.name+' joined the game')||c.ended,'join '+c.name,25000)
  assert(!c.ended,JSON.stringify({kicked:c.kicked,errors:c.errors}));await sleep(500)
}
async function disconnected(c) { if (!c.ended) c.bot.quit(); await until(() => c.ended, 'disconnect'); await sleep(300) }
async function expect(c, regex, from = 0) {
  await until(() => c.messages.slice(from).some(m => regex.test(m)) || c.ended, c.name + ' receives ' + regex)
  assert(c.messages.slice(from).some(m => regex.test(m)), JSON.stringify({ messages:c.messages,kicked:c.kicked,errors:c.errors }))
}
async function cmd(c, text, regex) { const mark=c.messages.length; c.bot.chat(text); await expect(c,regex,mark) }
async function probe(command) {
  const from=paper.output.length; paper.child.stdin.write('fptest '+command+'\n')
  await until(()=>paper.output.slice(from).includes('FPTEST_OK '+command)||paper.output.slice(from).includes('FPTEST_FAILED'),'probe '+command)
  assert(!paper.output.slice(from).includes('FPTEST_FAILED'),paper.output.slice(from))
}
async function state() {
  const id='s'+(++seq);await probe('state '+id)
  const m=paper.output.match(new RegExp('FSTATE '+id+' pending=(-?\\d+) records=(-?\\d+) writers=(\\d+) bridge=(\\d+) histories=(-?\\d+) problem=([^\\r\\n]*)'))
  assert(m,paper.output.slice(-2000));return{pending:+m[1],records:+m[2],writers:+m[3],bridge:+m[4],histories:+m[5],problem:m[6]}
}
async function player(name) {
  const id='p'+(++seq);await probe('player '+name+' '+id)
  const m=paper.output.match(new RegExp('PSTATE '+id+' level=(\\d+) until=(\\d+)'))
  assert(m);return{level:+m[1],until:+m[2]}
}
async function settled() {await until(async()=>{const s=await state();return s.pending===0&&s.problem===''},'punishments persisted',20000)}
async function admin(text,regex) {
  const from=paper.output.length;paper.child.stdin.write('nordfilter '+text+'\n')
  await until(()=>regex.test(paper.output.slice(from)),'admin '+text,10000)
}
async function reset(name='NFAlpha'){await admin('reset '+name,/Change queued/);await settled();await sleep(350)}
async function reload(valid=true){await admin('reload',/Reload queued/);await until(()=>paper.output.includes(valid?'Moderation configuration reloaded.':'Moderation reload rejected;'),'reload outcome')}
async function stopPaper() {
  if(paper&&!paper.exited){paper.child.stdin.write('stop\n');await until(()=>paper.exited,'Paper shutdown',45000)}
}
async function cleanup() {
  for(const c of clients)if(!c.ended)c.bot.quit()
  if(proxy&&!proxy.exited){proxy.child.stdin.write('shutdown\n');await until(()=>proxy.exited,'proxy shutdown',20000)}
  await stopPaper()
  for(const h of children.filter(c=>c.name==='queue'&&!c.exited)){
    h.child.kill();await until(()=>h.exited,'owned isolated limbo shutdown',10000)
  }
  for(const h of children)fs.writeFileSync(path.join(root,h.name+'-'+h.index+'-runtime.log'),h.output)
}

async function run(){
  fixture();await startPaper()
  const queue=start('queue','NanoLimbo.jar','256M')
  await until(()=>/NanoLimbo started|Listening|Server started/.test(queue.output)||queue.exited,'limbo startup',30000);assert(!queue.exited,queue.output)
  proxy=start('proxy','velocity.jar','512M');await until(()=>/Done \(/.test(proxy.output)||proxy.exited,'proxy startup',30000);assert(!proxy.exited,proxy.output)
  let a=connect('NFAlpha');await joined(a);let b=connect('NFBeta');await joined(b)
  a.bot.chat('CLEAN_PUBLIC');await expect(b,/CLEAN_PUBLIC/)
  await cmd(a,'/msg NFBeta CLEAN_PRIVATE',/CLEAN_PRIVATE/);await expect(b,/CLEAN_PRIVATE/)
  await admin('status NFAlpha',/level 0/);assert.equal((await state()).records,0)
  assert.equal(fs.readFileSync(data,'utf8'),'{}\n')
  pass('Normal chat/PM through MODERN forwarding; reads create no punishment records')
  await cmd(a,'Привет',/Latin letters/);assert.equal((await player(a.name)).level,0)
  a.bot.chat('Emoji yes \u{1f600}');await expect(b,/Emoji yes/)
  // This Mineflayer NBT decoder replaces Java modified-UTF8 surrogate pairs.
  // Assert the UTF8 wire original, not its broken decorated emoji rendering.
  if(!oldChat)await until(()=>b.originals.includes('Emoji yes \u{1f600}'),'emoji original delivered')
  pass('Non-Latin letters rejected without punishment; Latin and emoji accepted')
  const aliases=['msg','tell','minecraft:msg','minecraft:tell','minecraft:w','nordchat:msg','whisper','pm','w','fpm','froute','reply','r','last']
  for(const alias of aliases){
    await reset();const from=b.messages.length
    const tail=['reply','r','last'].includes(alias)?'syntheticforbidden':'NFBeta syntheticforbidden'
    await cmd(a,'/'+alias+' '+tail,/muted/)
    assert((await player(a.name)).until>Date.now())
    await sleep(300);assert(!b.messages.slice(from).some(m=>m.includes('syntheticforbidden')))
  }
  pass('Fourteen PM aliases: core, namespaces, canonical alias, earlier command rewrite and replies blocked')
  await reset()
  for(const msg of ['REPEAT_A','REPEAT_B','REPEAT_A','REPEAT_B']){a.bot.chat(msg);await sleep(120)}
  await cmd(a,'REPEAT_A',/muted/);await settled()
  pass('Alternating identical messages cannot evade duplicate threshold')
  await reset()
  a.bot.chat('REJOIN_DUPLICATE');await sleep(200);a.bot.chat('REJOIN_DUPLICATE');await sleep(200)
  await disconnected(a);a=connect('NFAlpha');await joined(a)
  await cmd(a,'REJOIN_DUPLICATE',/muted/);await settled()
  pass('Reconnect cannot reset duplicate counter inside the time window')
  await reset();await probe('permission NFAlpha nordfilter.bypass true')
  a.bot.chat('syntheticforbidden BYPASS_OK');await expect(b,/BYPASS_OK/)
  await probe('permission NFAlpha nordfilter.bypass false')
  await cmd(a,'syntheticforbidden',/muted/);await settled()
  pass('Bypass checked against current main-thread permission, including revocation')
  const prior=await player(a.name)
  await cmd(a,'/nordfilter unmute NFAlpha',/permission|unknown|command/i)
  const mark=a.messages.length;await probe('direct NFAlpha unmute NFAlpha');await expect(a,/do not have permission/,mark)
  assert.deepEqual(await player(a.name),prior)
  pass('Administrator action denied by command metadata and direct executor')
  const absent=b.messages.length
  await cmd(a,'MUTED_GLOBAL_MARKER',/muted/);await sleep(300)
  await cmd(a,'/tell NFBeta MUTED_PM_MARKER',/muted/);await sleep(300)
  assert(!b.messages.slice(absent).some(m=>/MUTED_(?:GLOBAL|PM)_MARKER/.test(m)))
  pass('Existing punishment blocks both public and private chat')
  await probe('slow 2000')
  await admin('unmute NFAlpha',/Change queued/)
  await sleep(200);assert((await player(a.name)).until>Date.now())
  const begin=Date.now();await probe('state responsive');assert(Date.now()-begin<1500)
  await probe('slow 0');await settled();assert.equal((await player(a.name)).until,0)
  pass('Slow disk keeps mute until durable release and leaves main-thread diagnostics responsive')
  await cmd(a,'syntheticforbidden',/muted/);await settled()
  assert.equal((await player(a.name)).level,2)
  const before=fs.readFileSync(data);await probe('fault true')
  await admin('unmute NFAlpha',/Change queued/)
  await until(async()=>(await state()).problem.includes('save failed'),'save fault detected')
  assert(fs.readFileSync(data).equals(before));assert((await player(a.name)).until>Date.now())
  const blocked=a.messages.length;b.bot.chat('STORAGE_FAIL_CLOSED');await sleep(500)
  assert(!a.messages.slice(blocked).some(m=>m.includes('STORAGE_FAIL_CLOSED')))
  await probe('fault false');await settled();assert.equal((await player(a.name)).until,0)
  a.bot.chat('AFTER_STORAGE_RECOVERY');await expect(b,/AFTER_STORAGE_RECOVERY/)
  pass('Failed release preserves file/mute, blocks chat while unhealthy, retries successfully; escalation retained')
  for(let i=0;i<3;i++){
    const from=paper.output.length;await admin('reload',/Reload queued/)
    await until(()=>paper.output.slice(from).includes('Moderation configuration reloaded.'),'valid reload')
  }
  assert.equal((await state()).writers,1)
  pass('Reload keeps exactly one owned storage worker')
  const validWords=fs.readFileSync(words);fs.writeFileSync(words,'words: [BROKEN\n')
  let from=paper.output.length;await admin('reload',/Reload queued/)
  await until(()=>paper.output.slice(from).includes('Moderation reload rejected;'),'invalid reload')
  await reset();await cmd(a,'syntheticforbidden',/muted/);await settled()
  fs.writeFileSync(words,validWords);from=paper.output.length;await admin('reload',/Reload queued/)
  await until(()=>paper.output.slice(from).includes('Moderation configuration reloaded.'),'restore matcher')
  pass('Malformed reload retains previously verified banned-word matcher')
  // First-tier 30s can expire during a genuine JVM restart. Escalate to tier two.
  await admin('unmute NFAlpha',/Change queued/);await settled();await sleep(350)
  await cmd(a,'syntheticforbidden',/muted/);await settled()
  assert.equal((await player(a.name)).level,2)
  const persisted=await player(a.name)
  await disconnected(a);await disconnected(b);await stopPaper();await startPaper()
  a=connect('NFAlpha');await joined(a);b=connect('NFBeta');await joined(b)
  assert.deepEqual(await player(a.name),persisted)
  await cmd(a,'/msg NFBeta PERSISTED_MUTE',/muted/);await reset()
  a.bot.chat('AFTER_RESTART');await expect(b,/AFTER_RESTART/)
  pass('Punishment survives graceful restart; admin reset and subsequent chat work')
  await disconnected(a);await disconnected(b);await stopPaper()
  const goodConfig=fs.readFileSync(config);fs.writeFileSync(config,'spam: [MALFORMED]\n')
  await startPaper();a=connect('NFAlpha');await joined(a);b=connect('NFBeta');await joined(b)
  let no=b.messages.length;a.bot.chat('BAD_SETTINGS_PUBLIC');await sleep(500)
  assert(!b.messages.slice(no).some(m=>m.includes('BAD_SETTINGS_PUBLIC')))
  await cmd(a,'/tell NFBeta BAD_SETTINGS_PM',/Moderation is unavailable/)
  fs.writeFileSync(config,goodConfig);from=paper.output.length;await admin('reload',/Reload queued/)
  await until(()=>paper.output.slice(from).includes('Moderation configuration reloaded.'),'initial config recovery')
  a.bot.chat('CONFIG_RECOVERED');await expect(b,/CONFIG_RECOVERED/)
  pass('Invalid initial settings fail closed; corrected settings can reload without restart')
  await disconnected(a);await disconnected(b);await stopPaper()
  fs.writeFileSync(data,'players: [MALFORMED_SYNTHETIC]\n');const corrupt=fs.readFileSync(data)
  await startPaper();a=connect('NFAlpha');await joined(a);b=connect('NFBeta');await joined(b)
  no=b.messages.length;a.bot.chat('CORRUPT_PUBLIC');await sleep(500)
  assert(!b.messages.slice(no).some(m=>m.includes('CORRUPT_PUBLIC')))
  await cmd(a,'/tell NFBeta CORRUPT_PRIVATE',/Moderation is unavailable/)
  assert.match(paper.output,/Invalid\/unreadable data.yml/)
  assert.equal((await state()).writers,0);assert(fs.readFileSync(data).equals(corrupt))
  pass('Corrupt punishment data blocks public/PM, does not start writer or overwrite original file')
}
run().then(()=>console.log('ALL_FILTER_INTEGRATION_TESTS_PASSED count='+results.length))
 .catch(e=>{console.error(e.stack);if(paper)console.error(paper.output.slice(-7000));for(const c of clients)console.error(c.name,JSON.stringify({messages:c.messages.slice(-15),errors:c.errors,kicked:c.kicked}));process.exitCode=1})
 .finally(async()=>{try{await cleanup()}catch(e){console.error(e.stack);process.exitCode=1}
 fs.writeFileSync(path.join(root,'results.json'),JSON.stringify({passed:process.exitCode!==1,chatVersion,results},null,2))})

