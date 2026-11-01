export type Command = {attempt_id:string;command_type:string;initiator_id:string;source_wallet_id:string;target_wallet_id:string|null;hold_id:string|null;amount_minor:string;fee_minor:string;currency:string};
export type Decision = {decision_id:string;decision:'ALLOW'|'DENY';score:number;triggered_rules:string[];rule_set_version:string;evaluated_at:string};
export type Attempt = {attempt_id:string;command:Command;first_seen_at:string;decision:Decision};
export type Investigation = {id:string;attempt_id:string;status:'OPEN'|'IN_REVIEW'|'CLOSED';conclusion:string|null;version:number;updated_at:string};
export type History = {id:string;actor_id:string|null;status:string;conclusion:string|null;comment:string;version:number;created_at:string};
export type CaseDetail = {investigation:Investigation;history:History[]};
export type Operation = {operation_id:string;type:string;amount_minor:string;fee_minor:string;posted_at:string};
export type AttemptDetail = {attempt_id:string;command:Command;first_seen_at:string;evaluations:{decision:Decision;facts:{server_time:string;unique_attempts:number;distinct_recipients:number;account_age:string}}[];outcomes:{event_id:string;event_type:string;operation:Operation|null}[];related_operations:{operation:Operation}[];investigations:Investigation[]};
export type Page<T> = {items:T[];total:number;limit:number;offset:number};
export function accountAge(value:string):string {
	const parts=/^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?$/.exec(value);
	if(!parts) return 'не указан';
	let seconds=Math.floor(Number(parts[1]||0)*86400+Number(parts[2]||0)*3600+Number(parts[3]||0)*60+Number(parts[4]||0));
	if(!Number.isSafeInteger(seconds)||seconds<0) return 'не указан';
	if(seconds===0) return 'менее секунды';
	const labels:[[number,string],[number,string],[number,string],[number,string]]=[[86400,'дн'],[3600,'ч'],[60,'мин'],[1,'с']];
	return labels.flatMap(([unit,label])=>{const count=Math.floor(seconds/unit);seconds%=unit;return count?[`${count} ${label}`]:[];}).join(' ');
}
export async function analystApi<T>(path:string,signal?:AbortSignal,body?:unknown):Promise<T> {
	const headers:Record<string,string>={Accept:'application/json'};
	if(body!==undefined) {
		const csrfResponse=await fetch('/api/v1/auth/csrf',{credentials:'same-origin',signal,cache:'no-store'});
		if(!csrfResponse.ok) throw new Error('Не удалось подтвердить сессию. Войдите в ядро повторно.');
		const csrf=await csrfResponse.json() as {header_name:string;token:string};
		headers[csrf.header_name]=csrf.token;headers['Content-Type']='application/json';
	}
	const response=await fetch('/api/v1/admin/intelligence'+path,{method:body===undefined?'GET':'POST',headers,credentials:'same-origin',cache:'no-store',signal,body:body===undefined?undefined:JSON.stringify(body)});
	if(!response.ok) {
		if(response.status===401 || response.status===403) throw new Error('Доступен только действующему ADMIN. Войдите в Payment Core.');
		if(response.status===429) throw new Error('Очередь ИИ заполнена. Повторите запрос позже.');
		if(response.status===409 && path.endsWith('/ai-analyses')) throw new Error('Анализ этой попытки уже выполняется. Обновите отчёты.');
		if(response.status===409) throw new Error('Расследование изменилось. Обновите карточку перед сохранением.');
		if(response.status===404) throw new Error('Запись не найдена.');
		throw new Error('Не удалось загрузить данные. Повторите запрос.');
	}
	return response.json() as Promise<T>;
}
export function rubles(value:string):string {
	const minor=BigInt(value);const sign=minor<0n?'-':'';const abs=minor<0n?-minor:minor;
	return `${sign}${(abs/100n).toLocaleString('ru-RU')},${(abs%100n).toString().padStart(2,'0')} ₽`;
}
