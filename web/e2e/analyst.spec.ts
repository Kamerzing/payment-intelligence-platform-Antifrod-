import {test,expect,type Page} from '@playwright/test';
const attempt='10000000-0000-0000-0000-000000000001',caseId='20000000-0000-0000-0000-000000000001',actor='30000000-0000-0000-0000-000000000001';
const command={attempt_id:attempt,command_type:'WITHDRAWAL',initiator_id:actor,source_wallet_id:actor,target_wallet_id:null,hold_id:null,amount_minor:'10000000',fee_minor:'100000',currency:'RUB'};
const decision={decision_id:actor,decision:'DENY',score:70,triggered_rules:['HIGH_AMOUNT'],rule_set_version:'pilot-v1',evaluated_at:'2026-11-01T00:00:00Z'};
const investigation={id:caseId,attempt_id:attempt,status:'OPEN',conclusion:null,version:1,updated_at:'2026-11-01T00:00:00Z'};
async function dashboard(page:Page){await page.route('**/api/v1/admin/intelligence/dashboard',r=>r.fulfill({json:{attempts:1,denied:1,operations:0,open_investigations:1,last_event_at:''}}));await page.route('**/api/v1/admin/intelligence/attempts/*/ai-analyses',r=>r.fulfill({json:{items:[],enabled:false}}));}

test('filters clear previous data on failure and retry recovers without stale pagination',async({page})=>{
	await dashboard(page);let fails=false;
	await page.route('**/api/v1/admin/intelligence/attempts?**',route=>fails?route.fulfill({status:503,json:{}}):route.fulfill({json:{items:[{attempt_id:attempt,command,decision,first_seen_at:'2026-11-01T00:00:00Z'}],total:1,limit:20,offset:0}}));
	await page.goto('/intelligence/');await expect(page.getByText(attempt,{exact:true})).toBeVisible();
	fails=true;await page.getByLabel('Решение',{exact:true}).selectOption('DENY');await page.getByRole('button',{name:'Применить фильтры'}).click();
	await expect(page.getByText('Не удалось загрузить данные. Повторите запрос.')).toBeVisible();await expect(page.getByText(attempt,{exact:true})).toHaveCount(0);
	fails=false;await page.getByRole('button',{name:'Повторить загрузку'}).click();await expect(page.getByText(attempt,{exact:true})).toBeVisible();
});
test('analyst investigates denial closes case and sees immutable history',async({page})=>{
	await dashboard(page);let current={...investigation};const history=[{id:caseId,actor_id:null as string|null,status:'OPEN',conclusion:null as string|null,comment:'',version:1,created_at:'2026-11-01T00:00:00Z'}];
	await page.route('**/api/v1/admin/intelligence/attempts?**',r=>r.fulfill({json:{items:[{attempt_id:attempt,command,decision,first_seen_at:'2026-11-01T00:00:00Z'}],total:1,limit:20,offset:0}}));
	await page.route('**/api/v1/admin/intelligence/attempts/'+attempt,r=>r.fulfill({json:{attempt_id:attempt,command,first_seen_at:'2026-11-01T00:00:00Z',evaluations:[{decision,facts:{server_time:'2026-11-01T00:00:00Z',unique_attempts:1,distinct_recipients:0,account_age:'P2D'}}],outcomes:[{event_id:caseId,event_type:'RISK_REJECTED',operation:null}],related_operations:[],investigations:[current]}}));
	await page.route('**/api/v1/auth/csrf',r=>r.fulfill({json:{header_name:'X-CSRF-TOKEN',token:'test-only'}}));
	await page.route('**/api/v1/admin/intelligence/investigations/'+caseId,async route=>{
		if(route.request().method()==='POST') {expect(route.request().headers()['x-csrf-token']).toBe('test-only');const change=route.request().postDataJSON();expect(change.version).toBe(1);current={...current,...change,version:2};history.push({id:actor,actor_id:actor,status:change.status,conclusion:change.conclusion,comment:change.comment,version:2,created_at:'2026-11-01T00:01:00Z'});}
		await route.fulfill({json:{investigation:current,history}});
	});
	await page.goto('/intelligence/');await page.getByRole('button',{name:'Вывод',exact:true}).click();await expect(page.getByText('Ядро сохранило отказ без проведения операции.')).toBeVisible();
	await page.getByRole('button',{name:'Открыть расследование · Открыто'}).click();await page.getByLabel('Новый статус').selectOption('CLOSED');await page.getByLabel('Вывод',{exact:true}).selectOption('SUSPICIOUS');
	await page.getByLabel('Комментарий').fill('Подозрительно по имеющимся фактам');await page.getByRole('button',{name:'Сохранить оценку'}).click();
	await expect(page.getByText('Текущий статус: Закрыто')).toBeVisible();await expect(page.getByText('Подозрительно по имеющимся фактам',{exact:true})).toBeVisible();
	await expect(page.getByText('Изменение не меняет решение антифрода и не возобновляет отклонённую команду.')).toBeVisible();
});
test('mobile queue and access error fit screen',async({page})=>{
	await page.setViewportSize({width:390,height:844});await dashboard(page);
	await page.route('**/api/v1/admin/intelligence/attempts?**',r=>r.fulfill({status:403,json:{}}));
	await page.goto('/intelligence/');await expect(page.getByText('Доступен только действующему ADMIN. Войдите в Payment Core.')).toBeVisible();
	await page.screenshot({path:'test-results/analyst-mobile.png',fullPage:true});
	expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
});

test('AI outage is explicitly a rules explanation and retry retains request key with CSRF',async({page})=>{
	await dashboard(page);let first=true;let key='';let created=false;
	await page.route('**/api/v1/admin/intelligence/attempts?**',r=>r.fulfill({json:{items:[{attempt_id:attempt,command,decision,first_seen_at:'2026-11-01T00:00:00Z'}],total:1,limit:20,offset:0}}));
	await page.route('**/api/v1/admin/intelligence/attempts/'+attempt,r=>r.fulfill({json:{attempt_id:attempt,command,first_seen_at:'2026-11-01T00:00:00Z',evaluations:[{decision,facts:{unique_attempts:1,distinct_recipients:0,account_age:'P2D'}}],outcomes:[],related_operations:[],investigations:[]}}));
	await page.route('**/api/v1/auth/csrf',r=>r.fulfill({json:{header_name:'X-CSRF-TOKEN',token:'ai-csrf'}}));
	const job={id:caseId,status:'FALLBACK',reason:'MODEL_UNAVAILABLE',created_at:'2026-11-01T00:00:00Z',metadata:{prompt_version:'facts-catalog-v1'},report:{source:'RULES_FALLBACK',summary:'Попытка отклонена правилами риска.',evidence:[{id:'rule:HIGH_AMOUNT:pilot-v1',tool:'read_rules',text:'Крупная сумма.'}],recommendations:[{code:'REVIEW_CONTEXT',text:'Проверьте контекст.'}],limitations:['Срабатывание правил не доказывает мошенничество.']}};
	await page.route('**/api/v1/admin/intelligence/attempts/'+attempt+'/ai-analyses',async route=>{
		if(route.request().method()==='POST'){expect(route.request().headers()['x-csrf-token']).toBe('ai-csrf');const body=route.request().postDataJSON();if(first){key=body.request_key;first=false;return route.fulfill({status:503,json:{}});}expect(body.request_key).toBe(key);created=true;return route.fulfill({json:job});}
		return route.fulfill({json:{items:created?[job]:[],enabled:true}});
	});
	await page.goto('/intelligence/');await page.getByRole('button',{name:'Вывод',exact:true}).click();await expect(page.getByText('Анализ ещё не запрашивался.')).toBeVisible();await page.getByRole('button',{name:'Запросить ИИ-анализ',exact:true}).click();
	await expect(page.getByRole('button',{name:'Повторить запрос анализа',exact:true})).toBeVisible();await page.getByRole('button',{name:'Повторить запрос анализа',exact:true}).click();
	await expect(page.getByText('Объяснение по правилам · ИИ не сформировал отчёт')).toBeVisible();await expect(page.getByText('Локальная модель недоступна.')).toBeVisible();await expect(page.getByText('rule:HIGH_AMOUNT:pilot-v1',{exact:true})).toBeVisible();
	await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
});
