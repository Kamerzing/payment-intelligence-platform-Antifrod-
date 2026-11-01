// Bounded tests on the caller's explicitly owned temporary databases only.
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {spawn} from 'node:child_process';
import {createReadStream,createWriteStream} from 'node:fs';
import {writeFile} from 'node:fs/promises';
import {pipeline} from 'node:stream/promises';
import {join} from 'node:path';
import {setTimeout as delay} from 'node:timers/promises';

async function archive(args,path,restore=false){
	const child=spawn('docker',args,{windowsHide:true,stdio:restore?['pipe','ignore','ignore']:['ignore','pipe','ignore']});
	const exit=new Promise((ok,fail)=>{child.on('error',fail);child.on('exit',code=>code===0?ok():fail(new Error('Archive command failed: '+code)));});
	await Promise.all([restore?pipeline(createReadStream(path),child.stdin):pipeline(child.stdout,createWriteStream(path,{flags:'wx'})),exit]);
}
export async function runPilot(c){
	const measurements={risk:{},delivery:{},resources:[],ai:{},restore:{}};
	const save=()=>writeFile(join(c.reportDir,'pilot-measurements.json'),JSON.stringify(measurements,null,2)+'\n');
	measurements.resources.push({phase:'before_load',...await c.resourceSnapshot()});
	const evaluate=async body=>{const start=performance.now();const response=await c.request(c.platformUrl,'/internal/v1/risk/evaluations',{method:'POST',headers:{Authorization:`Bearer ${c.coreToken}`,'Content-Type':'application/json'},body:JSON.stringify(body)});return {body:await response.json(),ms:performance.now()-start};};
	const command=(wallet=randomUUID())=>({attempt_id:randomUUID(),command_type:'WITHDRAWAL',initiator_id:randomUUID(),source_wallet_id:wallet,target_wallet_id:null,hold_id:null,currency:'RUB',amount_minor:'100',fee_minor:'1',fee_basis_points:100,user_created_at:'2026-01-01T00:00:00Z'});
	// Open arrivals at five requests per second; every result and actual arrival time is recorded.
	const starts=[],jobs=[];const begin=performance.now();
	for(let i=0;i<100;i++){await delay(Math.max(0,begin+i*200-performance.now()));starts.push(performance.now()-begin);jobs.push(evaluate(command()));}
	const results=await Promise.all(jobs);assert.ok(results.every(r=>r.body.decision==='ALLOW'));
	measurements.resources.push({phase:'after_risk_load',...await c.resourceSnapshot()});
	const sorted=results.map(r=>r.ms).sort((a,b)=>a-b);measurements.risk={requests:100,arrival_rate:5,duration_ms:performance.now()-begin,p50_ms:sorted[49],p95_ms:sorted[94],max_ms:sorted[99],errors:0,arrivals_ms:starts,target_p95_ms:200,met:sorted[94]<=200};
	await save();
	c.checks.push('pilot: 100 real HTTP risk evaluations at 5 arrivals/s with measured latency');
	const wallet=randomUUID();const burst=await Promise.all(Array.from({length:12},()=>evaluate(command(wallet))));assert.equal(burst.filter(r=>r.body.decision==='ALLOW').length,4);assert.equal(burst.filter(r=>r.body.decision==='DENY').length,8);
	const repeated=command();const repeat=await Promise.all(Array.from({length:12},()=>evaluate(repeated)));assert.equal(new Set(repeat.map(r=>r.body.decision_id)).size,1);
	c.checks.push('pilot: concurrent unique requests cannot bypass fifth-attempt rule; repeats count once');
	const deliveryStart=performance.now();const deposited=await c.session('/api/v1/admin/deposits','POST',{wallet_id:c.wallet,amount_minor:'10000'},'pilot-delivery');
	for(let i=0;i<200;i++){if(await c.queryDb('platform_test',`SELECT count(*) FROM operation_view WHERE operation_id='${deposited.id}'`)==='1')break;await delay(25);}
	const delivered=await c.queryDb('platform_test',`SELECT count(*) FROM operation_view WHERE operation_id='${deposited.id}'`);assert.equal(delivered,'1');measurements.delivery={observed_ms:performance.now()-deliveryStart,target_ms:5000};measurements.delivery.met=measurements.delivery.observed_ms<=5000;
	const aiPath='/api/v1/admin/intelligence/attempts/'+c.attemptId+'/ai-analyses';const key=randomUUID();
	await c.request(c.coreUrl,aiPath,{method:'POST',headers:{Cookie:c.cookie(),'Content-Type':'application/json'},body:JSON.stringify({request_key:key})},403);
	const enqueued=await c.session(aiPath,'POST',{request_key:key});assert.equal((await c.session(aiPath,'POST',{request_key:key})).id,enqueued.id);
	await c.waitFor(async()=>{const rows=await c.session(aiPath);return rows.items.some(r=>r.id===enqueued.id&&r.status==='FALLBACK');},'AI outage did not finish with fallback.');
	const fallback=(await c.session(aiPath)).items.find(r=>r.id===enqueued.id);assert.equal(fallback.report.source,'RULES_FALLBACK');assert.equal(fallback.reason,'MODEL_UNAVAILABLE');
	assert.equal((await evaluate(command())).body.decision,'ALLOW');
	measurements.ai.outage={status:fallback.status,reason:fallback.reason,risk_unaffected:true};c.checks.push('pilot: AI outage, retry deduplication and CSRF; risk remains available');
	await save();
	if(c.realAi){
		await c.enableRealAi();const created=await c.session(aiPath,'POST',{request_key:randomUUID()});const started=performance.now();
		const duringAi=(async()=>{const requests=[];for(let i=0;i<20;i++){requests.push(evaluate(command()));await delay(200);}return Promise.all(requests);})();
		let sampleTime=0;
		await c.waitFor(async()=>{const rows=await c.session(aiPath);if(performance.now()-sampleTime>10000){measurements.resources.push({phase:'during_local_ai',...await c.resourceSnapshot()});await save();sampleTime=performance.now();}return rows.items.some(r=>r.id===created.id&&['SUCCEEDED','FALLBACK'].includes(r.status));},'Local model did not finish.',480);
		const generated=(await c.session(aiPath)).items.find(r=>r.id===created.id);measurements.ai.local={status:generated.status,reason:generated.reason,duration_ms:performance.now()-started,metadata:generated.metadata};
		const parallel=await duringAi;assert.ok(parallel.every(r=>r.body.decision==='ALLOW'));const times=parallel.map(r=>r.ms).sort((a,b)=>a-b);measurements.ai.parallel_risk={requests:parallel.length,p95_ms:times[18],errors:0};
		await save();
		assert.equal(generated.status,'SUCCEEDED','Real local model must produce a fact-checked report.');assert.equal(generated.report.source,'LOCAL_MODEL');
		assert.ok(generated.report.evidence.some(f=>f.id.startsWith('rule:HIGH_AMOUNT:')));assert.equal((await evaluate(command())).body.decision,'ALLOW');
		await c.demonstrateAi();
		c.checks.push('pilot: actual pinned Qwen3 produces a validated Russian report through current ADMIN authorization');
	}
	// Freeze both writers before the paired snapshots. A restore into separate empty DBs never overwrites source DBs.
	await c.stopServices();
	const tables={core_test:['payment_operation','ledger_posting','ledger_account','payment_hold','command_result','risk_command_attempt','intelligence_outbox'],platform_test:['risk_attempt','risk_evaluation','integration_event','operation_view','investigation','investigation_history','ai_analysis']};
	const fingerprint=async(db,table)=>c.queryDb(db,`SELECT count(*)||':'||md5(coalesce(string_agg(to_jsonb(t)::text,'' ORDER BY to_jsonb(t)::text),'')) FROM ${table} t`);
	const original={};for(const [db,list]of Object.entries(tables)){original[db]={};for(const table of list)original[db][table]=await fingerprint(db,table);}
	for(const [db,role]of [['core_test','core_test'],['platform_test','platform_test']]){
		const path=join(c.reportDir,db+'.dump');await archive(['exec',c.name,'pg_dump','-U','postgres','-d',db,'-Fc','--no-owner'],path);
		const restored=db+'_restored';await c.sql(`CREATE DATABASE ${restored} OWNER ${role};`);
		await archive(['exec','-i',c.name,'pg_restore','-U','postgres','--role='+role,'--no-owner','--exit-on-error','--single-transaction','-d',restored],path,true);
		for(const table of tables[db])assert.equal(await fingerprint(restored,table),original[db][table],`${db}.${table} restore differs`);
	}
	// Financial keys survive; browser sessions must not survive recovery.
	await c.queryDb('core_test_restored','DELETE FROM spring_session');
	measurements.restore={paired_snapshot:true,source_databases_preserved:true,table_fingerprints:original};
	await c.startRestored();await c.verifyRestored();c.checks.push('pilot: paired pg_dump/pg_restore into separate databases preserves exact ledger, keys, decisions, cases, audit and AI reports; restored JARs pass reconciliation');
	measurements.local_lite_targets_met=measurements.risk.met&&measurements.delivery.met;
	measurements.full_stand_targets_met=c.realAi?measurements.local_lite_targets_met&&measurements.ai.parallel_risk.p95_ms<=200:null;
	measurements.functional_passed=true;
	await writeFile(join(c.reportDir,'pilot-measurements.json'),JSON.stringify(measurements,null,2)+'\n');return measurements;
}
