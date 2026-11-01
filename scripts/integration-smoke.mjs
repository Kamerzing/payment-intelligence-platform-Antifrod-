// Full stage 3–6 drill on temporary databases. Requires built JARs, JDK 21 and Docker.
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { createWriteStream } from 'node:fs';
import { access, mkdir, writeFile,readFile } from 'node:fs/promises';
import {runPilot} from './pilot-checks.mjs';
import { createServer } from 'node:net';
import { resolve, join } from 'node:path';
import { createRequire } from 'node:module';
import { setTimeout as delay } from 'node:timers/promises';

const root = resolve(import.meta.dirname, '..');
const coreJar = resolve(process.env.PIP_CORE_JAR ?? join(root, '../payment-core/payment-core2/build/libs/payment-core.jar'));
const platformJar = join(root, 'build/libs/payment-intelligence-platform.jar');
const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
const pilot=process.argv.includes('--pilot'),realAi=process.argv.includes('--real-ai');
assert.ok(!realAi||pilot,'Use --real-ai together with --pilot.');
const name = `pip-${pilot?'pilot':'stage56'}-${randomBytes(6).toString('hex')}`;
const reportDir = join(root, 'build', name);
const secret = () => randomBytes(32).toString('hex');
const coreToken = secret(), exportToken = secret(), analystToken = secret(), dbPassword = secret(), adminPassword = secret();
const cleanEnv = Object.fromEntries(Object.entries(process.env).filter(([key]) => !/^(SPRING_|SPRINGDOC_|PIP_|PAYMENT_)/i.test(key)));
let containerCreated = false;
let core, platform, browser;
const checks = [];
let pilotMeasurements;

async function command(file, args, { env = cleanEnv, input = '', timeout = 90000 } = {}) {
	return new Promise((accept, reject) => {
		const child = spawn(file, args, { cwd: root, env, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
		let output = ''; child.stdout.on('data', data => { output += data; });
		child.stderr.on('data', () => {}); // Error text can contain configuration; report only exit code.
		const timer = setTimeout(() => child.kill(), timeout);
		child.on('error', error => { clearTimeout(timer); reject(new Error(`${file}: ${error.code}`)); });
		child.on('exit', code => { clearTimeout(timer); code === 0 ? accept(output.trim()) : reject(new Error(`${file} exited ${code}`)); });
		child.stdin.end(input);
	});
}
async function freePort() {
	const server = createServer(); await new Promise((ok, fail) => { server.once('error', fail); server.listen(0, '127.0.0.1', ok); });
	const port = server.address().port; await new Promise(ok => server.close(ok)); return port;
}
function start(jar, env, log) {
	const output = createWriteStream(join(reportDir, log));
	const child = spawn(java, ['-Xmx256m', '-jar', jar], { cwd: reportDir, env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
	child.stdout.pipe(output); child.stderr.pipe(output, { end: false });
	child.once('exit', () => output.end()); child.once('error', () => output.end());
	return child;
}
async function stop(child) {
	if (!child || child.exitCode !== null || child.signalCode !== null) return;
	await new Promise((ok, fail) => {
		const timer = setTimeout(() => fail(new Error('Test process did not stop.')), 15000);
		child.once('exit', () => { clearTimeout(timer); ok(); }); child.kill();
	});
}
async function waitFor(check, message,tries=160) {
	for (let index = 0; index < tries; index++) {
		try { if (await check()) return; } catch { }
		await delay(500);
	}
	throw new Error(message);
}
async function ready(base) { return (await fetch(base + '/actuator/health/readiness', { signal: AbortSignal.timeout(2000) })).ok; }
async function request(base, path, options = {}, expected = 200) {
	const response = await fetch(base + path, { ...options, signal: AbortSignal.timeout(5000) });
	assert.equal(response.status, expected, `${path}: HTTP ${response.status}`); return response;
}
function sql(query) { return command('docker', ['exec', '-i', name, 'psql', '-U', 'postgres', '-d', 'postgres', '-At', '-v', 'ON_ERROR_STOP=1'], { input: query }); }
function queryDb(database, query) { return command('docker', ['exec', '-i', name, 'psql', '-U', 'postgres', '-d', database, '-At', '-v', 'ON_ERROR_STOP=1'], { input: query }); }
async function resourceSnapshot(){
	const sample={captured_at:new Date()};
	if(process.platform==='win32'){
		const ids=[core?.pid,platform?.pid].filter(Boolean).join(',');
		try{Object.assign(sample,JSON.parse(await command('powershell.exe',['-NoProfile','-Command',`$os=Get-CimInstance Win32_OperatingSystem; @{free_mb=[math]::Round($os.FreePhysicalMemory/1024);total_mb=[math]::Round($os.TotalVisibleMemorySize/1024);jvm=@(Get-Process -Id ${ids} | ForEach-Object { @{working_set_mb=[math]::Round($_.WorkingSet64/1MB);private_mb=[math]::Round($_.PrivateMemorySize64/1MB)} })} | ConvertTo-Json -Compress -Depth 4`],{timeout:5000})));}catch{sample.host_unmeasured=true;}
	}
	try{sample.gpu=await command('nvidia-smi',['--query-gpu=name,memory.total,memory.used,utilization.gpu','--format=csv,noheader'],{timeout:5000});}catch{sample.gpu_unmeasured=true;}
	try{const response=await fetch('http://127.0.0.1:11435/api/ps',{signal:AbortSignal.timeout(2000)});const body=await response.json();sample.loaded_models=body.models.map(m=>({model:m.name,digest:m.digest,size:m.size,size_vram:m.size_vram,context_length:m.context_length}));}catch{sample.runtime_unmeasured=true;}
	return sample;
}

try {
	await Promise.all([access(coreJar), access(platformJar)]); await mkdir(reportDir, { recursive: true });
	await command('docker', ['info', '--format', '{{.ServerVersion}}']);
	await command('docker', ['run', '-d', '--rm', '--name', name, '--label', `pip.test=${name}`, '--memory=256m',
		'--tmpfs', '/var/lib/postgresql:rw,size=256m', '-p', '127.0.0.1::5432', '-e', 'POSTGRES_PASSWORD',
		'postgres:18.6-alpine', '-c', 'shared_buffers=32MB', '-c', 'max_connections=30'],
		{ env: { ...cleanEnv, POSTGRES_PASSWORD: secret() } });
	containerCreated = true;
	await waitFor(async () => { await command('docker', ['exec', name, 'pg_isready', '-U', 'postgres'], { timeout: 3000 }); return true; }, 'PostgreSQL did not start.');
	const binding = await command('docker', ['port', name, '5432/tcp']);
	const dbPort = Number(binding.trim().split(':').at(-1));
	await sql(`CREATE ROLE core_test LOGIN PASSWORD '${dbPassword}'; CREATE DATABASE core_test OWNER core_test;
CREATE ROLE platform_test LOGIN PASSWORD '${dbPassword}'; CREATE DATABASE platform_test OWNER platform_test;`);
	const corePort = await freePort(), platformPort = await freePort();
	const coreUrl = `http://127.0.0.1:${corePort}`, platformUrl = `http://127.0.0.1:${platformPort}`;
	const coreEnv = { ...cleanEnv, SPRING_DATASOURCE_URL: `jdbc:postgresql://127.0.0.1:${dbPort}/core_test`,
		SPRING_DATASOURCE_USERNAME: 'core_test', SPRING_DATASOURCE_PASSWORD: dbPassword, SERVER_ADDRESS: '127.0.0.1', SERVER_PORT: String(corePort),
		SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE: '3', PIP_CORE_TOKEN: coreToken, PIP_EXPORT_TOKEN: exportToken,
		PIP_ANALYST_TOKEN: analystToken, PAYMENT_INTELLIGENCE_RISK_URL: `${platformUrl}/internal/v1/risk/evaluations`, PAYMENT_INTELLIGENCE_PLATFORM_URL: platformUrl,
		PAYMENT_INTELLIGENCE_DELIVERY_ENABLED: 'true', PAYMENT_INTELLIGENCE_DELIVERY_URL: `${platformUrl}/internal/v1/events` };
	const platformEnv = { ...cleanEnv, PIP_DB_URL: `jdbc:postgresql://127.0.0.1:${dbPort}/platform_test`,
		PIP_DB_USER: 'platform_test', PIP_DB_PASSWORD: dbPassword, PIP_APP_PORT: String(platformPort),
		PIP_CORE_TOKEN: coreToken, PIP_EXPORT_TOKEN: exportToken, PIP_ANALYST_TOKEN: analystToken, PIP_CORE_URL: coreUrl };
	if(pilot){const lock=JSON.parse(await readFile(join(root,'docs/ai-model-lock.json'),'utf8'));Object.assign(platformEnv,{PIP_AI_ENABLED:'true',PIP_AI_MODEL_DIGEST:lock.digest,PIP_AI_RUNTIME_VERSION:lock.runtime_version,PIP_AI_URL:'http://127.0.0.1:1'});}
	await command(java, ['-Xmx192m', '-jar', coreJar, '--bootstrap-admin', '--credentials-stdin'],
		{ env: { ...coreEnv, PAYMENT_INTELLIGENCE_DELIVERY_ENABLED: 'false' }, input: `smoke.admin\n${adminPassword}\n` });
	core = start(coreJar, coreEnv, 'core.log');
	await waitFor(() => ready(coreUrl), 'Core did not become ready.');
	let cookie = '', csrf;
	async function session(path, method = 'GET', body, key, expected) {
		const headers = { Cookie: cookie };
		if (body) headers['Content-Type'] = 'application/json';
		if (method === 'POST') headers[csrf.header_name] = csrf.token;
		if (key) headers['Idempotency-Key'] = key;
		const response = await request(coreUrl, path, { method, headers, body: body ? JSON.stringify(body) : undefined }, expected ?? (path === '/api/v1/admin/deposits' ? 201 : 200));
		const setCookie = response.headers.getSetCookie().find(value => value.startsWith('PAYMENT_SESSION='));
		if (setCookie) cookie = setCookie.split(';')[0]; return response.json();
	}
	csrf = await session('/api/v1/auth/csrf'); await session('/api/v1/auth/login', 'POST', { username: 'smoke.admin', password: adminPassword });
	csrf = await session('/api/v1/auth/csrf'); const wallet = await session('/api/v1/wallets/me');
	const first = await session('/api/v1/admin/deposits', 'POST', { wallet_id: wallet.id, amount_minor: '10000' }, 'before-platform');
	assert.equal(await queryDb('core_test', 'SELECT count(*) FROM intelligence_outbox WHERE delivered_at IS NULL'), '1');
	checks.push('core commits outbox while platform is offline');
	platform = start(platformJar, platformEnv, 'platform-1.log');
	await waitFor(() => ready(platformUrl), 'Platform did not become ready.');
	await waitFor(async () => await queryDb('core_test', 'SELECT count(*) FROM intelligence_outbox WHERE delivered_at IS NULL') === '0', 'Outbox was not delivered.');
	assert.equal(await queryDb('platform_test', 'SELECT count(*) FROM operation_view'), '1');
	checks.push('real authenticated HTTP delivery reaches platform projection');
	const replay = await session('/api/v1/admin/deposits', 'POST', { wallet_id: wallet.id, amount_minor: '10000' }, 'before-platform');
	assert.equal(replay.id, first.id); assert.equal(await queryDb('core_test', 'SELECT count(*) FROM intelligence_outbox'), '1');
	checks.push('monetary idempotency does not append a second event');
	const html = await (await request(platformUrl, '/')).text(); assert.match(html, /id="root"/);
	for (const asset of [...html.matchAll(/(?:src|href)="(\/(?:intelligence\/)?assets\/[^\"]+)"/g)].map(match => match[1])) await request(platformUrl, asset);
	await request(platformUrl, '/internal/v1/risk/evaluations', { method: 'POST' }, 401);
	await request(platformUrl, '/api/v1/investigations', { method: 'POST' }, 403);
	checks.push('bundled UI, machine authentication and browser CSRF');
	await stop(platform); platform = null;
	await session('/api/v1/admin/deposits', 'POST', { wallet_id: wallet.id, amount_minor: '20000' }, 'during-outage');
	await command(java, ['-Xmx192m', '-jar', platformJar, '--import-history'], { env: platformEnv });
	assert.equal(await queryDb('platform_test', 'SELECT count(*) FROM operation_view'), '2');
	assert.equal(await queryDb('platform_test', 'SELECT count(*) FROM history_import WHERE complete'), '1');
	await command(java, ['-Xmx192m', '-jar', platformJar, '--rebuild-view'], { env: platformEnv });
	assert.equal(await queryDb('platform_test', 'SELECT count(*) FROM operation_view'), '2');
	checks.push('real core export, platform CLI import and reproducible rebuild');
	platform = start(platformJar, platformEnv, 'platform-2.log');
	await waitFor(() => ready(platformUrl), 'Platform restart failed.');
	await waitFor(async () => await queryDb('core_test', 'SELECT count(*) FROM intelligence_outbox WHERE delivered_at IS NULL') === '0', 'Delivery did not recover.');
	assert.equal(await queryDb('platform_test', 'SELECT count(*) FROM integration_event'), '2');
	checks.push('outage recovery and snapshot/live deduplication');
	await command(process.execPath, [join(root, 'scripts/risk-scenarios.mjs')], { env: { ...cleanEnv, PIP_CORE_TOKEN: coreToken, PIP_URL: platformUrl } });
	checks.push('synthetic amount, velocity, fanout and replay scenarios');
	const saved = JSON.parse(await queryDb('platform_test', "SELECT command FROM risk_attempt a WHERE EXISTS(SELECT 1 FROM risk_evaluation e WHERE e.attempt_id=a.attempt_id AND e.decision='DENY') ORDER BY attempt_id LIMIT 1"));
	const evaluate = () => request(platformUrl, '/internal/v1/risk/evaluations', { method: 'POST', headers: { Authorization: `Bearer ${coreToken}`, 'Content-Type': 'application/json' }, body: JSON.stringify(saved) }).then(response => response.json());
	const beforeRestart = await evaluate(); await stop(platform); platform = start(platformJar, platformEnv, 'platform-3.log');
	await waitFor(() => ready(platformUrl), 'Second platform restart failed.');
	assert.deepEqual(await evaluate(), beforeRestart);
	assert.equal((await session('/api/v1/admin/reconciliation')).consistent, true);
	checks.push('persisted DENY survives JVM restart; core ledger remains balanced');
	await session('/api/v1/admin/deposits','POST',{wallet_id:wallet.id,amount_minor:'30000000'},'risk-funding');
	const small={amount_minor:'100',expected_fee_minor:'1',expected_fee_basis_points:100};
	const posted=await session('/api/v1/withdrawals','POST',small,'allowed',201);
	const attemptsBeforeReplay=await queryDb('platform_test','SELECT count(*) FROM risk_attempt');
	assert.equal((await session('/api/v1/withdrawals','POST',small,'allowed',201)).id,posted.id);
	assert.equal(await queryDb('platform_test','SELECT count(*) FROM risk_attempt'),attemptsBeforeReplay);
	checks.push('real risk ALLOW posts once; saved success bypasses reevaluation');
	const large={amount_minor:'10000000',expected_fee_minor:'100000',expected_fee_basis_points:100};
	const operationsBeforeDeny=await queryDb('core_test','SELECT count(*) FROM payment_operation');
	assert.equal((await session('/api/v1/withdrawals','POST',large,'denied',409)).code,'RISK_REJECTED');
	assert.equal(await queryDb('core_test','SELECT count(*) FROM payment_operation'),operationsBeforeDeny);
	const attemptId=await queryDb('core_test',"SELECT attempt_id FROM risk_command_attempt WHERE idempotency_key='denied'");
	await waitFor(async()=>await queryDb('platform_test',`SELECT count(*) FROM integration_event WHERE attempt_id='${attemptId}' AND operation_id IS NULL`)==='1','Rejection event was not delivered.');
	assert.equal((await session('/api/v1/withdrawals','POST',small,'denied',409)).code,'IDEMPOTENCY_CONFLICT');
	await stop(core);core=start(coreJar,coreEnv,'core-restart.log');await waitFor(()=>ready(coreUrl),'Core restart failed.');
	assert.equal((await session('/api/v1/withdrawals','POST',large,'denied',409)).code,'RISK_REJECTED');
	assert.equal(await queryDb('core_test',`SELECT count(*) FROM intelligence_outbox WHERE attempt_id='${attemptId}'`),'1');
	checks.push('real DENY creates no money operation and survives core restart with one rejection event');
	const analystPage=await request(coreUrl,'/intelligence/',{headers:{Cookie:cookie}});
	const analystHtml=await analystPage.text();const assets=[...analystHtml.matchAll(/(?:src|href)="(\/intelligence\/assets\/[^\"]+)"/g)].map(m=>m[1]);assert.ok(assets.length>=2);
	for(const asset of assets)await request(coreUrl,asset,{headers:{Cookie:cookie}});
	await request(coreUrl,'/api/v1/admin/intelligence/dashboard',{},401);
	await request(platformUrl,'/internal/v1/analyst/dashboard',{headers:{Authorization:`Bearer ${coreToken}`,'X-Analyst-Id':randomUUID()}},401);
	const caseRow=(await session('/api/v1/admin/intelligence/attempts/'+attemptId)).investigations[0];assert.ok(caseRow);
	const actorContext=await session('/api/v1/admin/intelligence/session');
	await session('/api/v1/admin/intelligence/investigations/'+caseRow.id,'POST',{version:1,status:'IN_REVIEW',conclusion:null,comment:'Проверяю отказ пилота'});
	await session('/api/v1/admin/intelligence/investigations/'+caseRow.id,'POST',{version:2,status:'CLOSED',conclusion:'INSUFFICIENT_DATA',comment:'Данных для подтверждения недостаточно'});
	const closed=await session('/api/v1/admin/intelligence/investigations/'+caseRow.id);assert.equal(closed.investigation.status,'CLOSED');assert.equal(closed.history.length,3);assert.equal(closed.history[2].actor_id,actorContext.actor_id);
	await session('/api/v1/admin/intelligence/investigations/'+caseRow.id,'POST',{version:2,status:'OPEN',conclusion:null,comment:'Устаревший запрос'},undefined,409);
	checks.push('authenticated ADMIN gateway serves UI, facts, case workflow and immutable audit');
	await queryDb('core_test',"UPDATE app_user SET role='USER' WHERE username='smoke.admin'");
	await session('/api/v1/admin/intelligence/dashboard','GET',undefined,undefined,403);
	await queryDb('core_test',"UPDATE app_user SET role='ADMIN' WHERE username='smoke.admin'");
	await request(coreUrl,'/api/v1/admin/intelligence/investigations/'+caseRow.id,{method:'POST',headers:{Cookie:cookie,'Content-Type':'application/json'},body:'{}'},403);
	checks.push('gateway rechecks revoked ADMIN rights and enforces browser CSRF');
	const reserve=await session('/api/v1/holds','POST',{type:'WITHDRAWAL',...large},'reserve-high',201);
	assert.equal((await session(`/api/v1/holds/${reserve.id}/capture`,'POST',{},'capture-denied',409)).code,'RISK_REJECTED');
	assert.equal((await session('/api/v1/holds/'+reserve.id)).state,'ACTIVE');
	await stop(platform);platform=null;
	assert.equal((await session('/api/v1/withdrawals','POST',small,'outage',503)).code,'RISK_UNAVAILABLE');
	const outageAttempt=await queryDb('core_test',"SELECT attempt_id FROM risk_command_attempt WHERE idempotency_key='outage'");
	assert.equal((await session(`/api/v1/holds/${reserve.id}/cancel`,'POST',{},'cancel-outage',201)).state,'CANCELLED');
	await session('/api/v1/admin/reversals','POST',{operation_id:posted.id},'refund-outage',201);
	checks.push('capture DENY preserves ACTIVE hold; outage blocks debit but permits cancellation and refund');
	platform=start(platformJar,platformEnv,'platform-recovered.log');await waitFor(()=>ready(platformUrl),'Risk service recovery failed.');
	await session('/api/v1/withdrawals','POST',small,'outage',201);
	assert.equal(await queryDb('core_test',"SELECT attempt_id FROM risk_command_attempt WHERE idempotency_key='outage'"),outageAttempt);
	assert.equal(await queryDb('platform_test',`SELECT count(*) FROM risk_attempt WHERE attempt_id='${outageAttempt}'`),'1');
	const testUser=await session('/api/v1/admin/users','POST',{username:'smoke.user',password:adminPassword},undefined,201);
	const otherWallet=await queryDb('core_test',`SELECT id FROM wallet WHERE owner_id='${testUser.id}'`);
	await session('/api/v1/admin/deposits','POST',{wallet_id:otherWallet,amount_minor:'100000'},'user-fund');
	const adminCookie=cookie,adminCsrf=csrf;cookie='';csrf=await session('/api/v1/auth/csrf');
	await session('/api/v1/auth/login','POST',{username:'smoke.user',password:adminPassword});csrf=await session('/api/v1/auth/csrf');
	await session('/api/v1/admin/intelligence/dashboard','GET',undefined,undefined,403);
	await session('/api/v1/transfers','POST',{...small,target_wallet_id:wallet.id},'real-transfer',201);
	const held=await session('/api/v1/holds','POST',{...small,type:'TRANSFER',target_wallet_id:wallet.id},'reserve-small',201);
	assert.equal((await session(`/api/v1/holds/${held.id}/capture`,'POST',{},'real-capture',201)).state,'CAPTURED');
	cookie=adminCookie;csrf=adminCsrf;
	checks.push('real transfer and reserve capture pass risk; ordinary USER cannot access analyst data');
	await command(java,['-Xmx192m','-jar',platformJar,'--import-history'],{env:platformEnv});
	await command(java,['-Xmx192m','-jar',platformJar,'--rebuild-view'],{env:platformEnv});
	assert.equal(await queryDb('platform_test','SELECT count(*) FROM operation_view'),await queryDb('core_test','SELECT count(*) FROM payment_operation'));
	assert.equal((await session('/api/v1/admin/reconciliation')).consistent,true);
	checks.push('same-key recovery and mixed posted/rejected history import preserve exact ledger and projection');
	const { chromium }=createRequire(new URL('../web/package.json',import.meta.url))('@playwright/test');
	browser=await chromium.launch({headless:true});
	const page=await browser.newPage({viewport:{width:1440,height:1000}});
	await page.goto(coreUrl);await page.getByLabel('Логин',{exact:true}).fill('smoke.admin');await page.getByLabel('Пароль',{exact:true}).fill(adminPassword);
	await page.getByRole('button',{name:'Войти →',exact:true}).click();
	await page.getByRole('button',{name:'Кабинет аналитика ↗',exact:true}).click();await page.getByRole('heading',{name:'Риск под наблюдением'}).waitFor();
	await page.getByRole('button',{name:'Расследования',exact:true}).click();await page.getByRole('combobox',{name:'Статус',exact:true}).selectOption('CLOSED');
	const caseCard=page.locator('.case-list article').filter({hasText:caseRow.id});await caseCard.getByRole('button').click();
	await page.getByLabel('Новый статус').selectOption('IN_REVIEW');await page.getByLabel('Комментарий').fill('Повторная проверка по новым сведениям');
	await page.getByRole('button',{name:'Сохранить оценку',exact:true}).click();await page.getByText('Текущий статус: В работе',{exact:true}).waitFor();
	await page.getByRole('button',{name:'Карточка попытки',exact:true}).click();await page.getByText('HIGH_AMOUNT',{exact:true}).waitFor();
	await page.screenshot({path:join(reportDir,'analyst-desktop.png'),fullPage:true});
	await page.setViewportSize({width:390,height:844});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth),true);
	await page.screenshot({path:join(reportDir,'analyst-mobile.png'),fullPage:true});
	await browser.close();browser=null;
	checks.push('real Chromium ADMIN session opens gateway UI, reopens case, reads risk facts and fits mobile');
	if(pilot)pilotMeasurements=await runPilot({coreUrl,platformUrl,coreToken,wallet:wallet.id,attemptId,checks,reportDir,name,realAi,request,session,queryDb,sql,waitFor,cookie:()=>cookie,
		demonstrateAi:async()=>{browser=await chromium.launch({headless:true});const aiPage=await browser.newPage({viewport:{width:1440,height:1000}});await aiPage.goto(coreUrl);await aiPage.getByLabel('Логин',{exact:true}).fill('smoke.admin');await aiPage.getByLabel('Пароль',{exact:true}).fill(adminPassword);await aiPage.getByRole('button',{name:'Войти →',exact:true}).click();await aiPage.getByRole('button',{name:'Кабинет аналитика ↗',exact:true}).click();await aiPage.getByLabel('Поиск по UUID').fill(attemptId);await aiPage.getByRole('button',{name:'Применить фильтры'}).click();await aiPage.getByRole('button',{name:'Вывод',exact:true}).click();await aiPage.getByText('Отчёт локальной модели',{exact:true}).waitFor();await aiPage.screenshot({path:join(reportDir,'ai-desktop.png'),fullPage:true});await aiPage.setViewportSize({width:390,height:844});assert.equal(await aiPage.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);await aiPage.screenshot({path:join(reportDir,'ai-mobile.png'),fullPage:true});await browser.close();browser=null;},
		stopServices:async()=>{await Promise.all([stop(platform),stop(core)]);platform=null;core=null;},
		enableRealAi:async()=>{await stop(platform);platformEnv.PIP_AI_URL='http://127.0.0.1:11435';platform=start(platformJar,platformEnv,'platform-real-ai.log');await waitFor(()=>ready(platformUrl),'AI platform did not restart.');},
		resourceSnapshot,
		startRestored:async()=>{coreEnv.SPRING_DATASOURCE_URL=coreEnv.SPRING_DATASOURCE_URL.replace('/core_test','/core_test_restored');platformEnv.PIP_DB_URL=platformEnv.PIP_DB_URL.replace('/platform_test','/platform_test_restored');platform=start(platformJar,platformEnv,'platform-restored.log');core=start(coreJar,coreEnv,'core-restored.log');await waitFor(()=>ready(platformUrl),'Restored platform not ready.');await waitFor(()=>ready(coreUrl),'Restored core not ready.');await request(coreUrl,'/api/v1/admin/intelligence/session',{headers:{Cookie:cookie}},401);checks.push('pilot: restored browser sessions are invalidated before opening access');cookie='';csrf=await session('/api/v1/auth/csrf');await session('/api/v1/auth/login','POST',{username:'smoke.admin',password:adminPassword});csrf=await session('/api/v1/auth/csrf');},
		verifyRestored:async()=>{assert.equal((await session('/api/v1/admin/reconciliation')).consistent,true);assert.equal((await session('/api/v1/withdrawals','POST',large,'denied',409)).code,'RISK_REJECTED');assert.equal((await session('/api/v1/withdrawals','POST',small,'allowed',201)).id,posted.id);assert.ok((await session('/api/v1/admin/intelligence/attempts/'+attemptId+'/ai-analyses')).items.length>0);}
	});
	const passed=!pilotMeasurements||(pilotMeasurements.local_lite_targets_met&&(!realAi||pilotMeasurements.full_stand_targets_met));
	await writeFile(join(reportDir, 'result.json'), JSON.stringify({ checked_at: new Date(), checks, functional_passed:true,local_lite_targets_met:pilotMeasurements?.local_lite_targets_met,full_stand_targets_met:pilotMeasurements?.full_stand_targets_met,passed,pilot_measurements:pilotMeasurements }, null, 2) + '\n');
	console.log(`Completed ${checks.length} integration groups; local-lite targets met: ${pilotMeasurements?pilotMeasurements.local_lite_targets_met:true}; simultaneous AI targets met: ${pilotMeasurements?.full_stand_targets_met??'unmeasured'}. Report: build/${name}/result.json`);
	process.exitCode=passed?0:1;
} catch(error) {
	await writeFile(join(reportDir,'failure.json'),JSON.stringify({checked_at:new Date(),passed:false,completed_checks:checks,failure_type:error.name},null,2)+'\n').catch(()=>{});throw error;
} finally {
	const stopping = await Promise.allSettled([browser?.close(),stop(platform), stop(core)]);
	if (containerCreated) {
		const label = await command('docker', ['inspect', '--format', '{{index .Config.Labels "pip.test"}}', name]);
		assert.equal(label, name); await command('docker', ['rm', '-f', name]);
	}
	if (stopping.some(result => result.status === 'rejected')) throw new Error('A test JVM did not terminate; inspect only the PIDs from this drill.');
}
