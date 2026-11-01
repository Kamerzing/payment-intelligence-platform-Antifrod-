// Synthetic trusted-core requests. No monetary operations are performed.
import { randomUUID } from 'node:crypto';
import assert from 'node:assert/strict';

const base = process.env.PIP_URL ?? 'http://127.0.0.1:8081';
const token = process.env.PIP_CORE_TOKEN;
if (!token || token.length < 32) throw new Error('Set PIP_CORE_TOKEN in the environment.');
const url = new URL(base);
if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1') throw new Error('Scenarios require a loopback test platform.');
const oldAccount = new Date(Date.now() - 48 * 3600000).toISOString();
const youngAccount = new Date(Date.now() - 3600000).toISOString();
function command(wallet, amount = '100', target = null, created = oldAccount) {
	return { attempt_id: randomUUID(), command_type: target ? 'TRANSFER' : 'WITHDRAWAL', initiator_id: randomUUID(),
		source_wallet_id: wallet, target_wallet_id: target, hold_id: null, currency: 'RUB', amount_minor: amount,
		fee_minor: '0', fee_basis_points: 0, user_created_at: created };
}
async function evaluate(body, expected = 200) {
	const response = await fetch(new URL('/internal/v1/risk/evaluations', url), { method: 'POST',
		headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
		body: JSON.stringify(body), signal: AbortSignal.timeout(5000) });
	assert.equal(response.status, expected);
	return response.json();
}
const high = command(randomUUID(), '10000000');
const denied = await evaluate(high);
assert.equal(denied.decision, 'DENY'); assert.equal(denied.score, 70);
assert.equal((await evaluate(high)).decision_id, denied.decision_id);
await evaluate({ ...high, amount_minor: '10000001' }, 409);
const velocityWallet = randomUUID();
for (let index = 1; index <= 5; index++) {
	const result = await evaluate(command(velocityWallet));
	assert.equal(result.decision, index < 5 ? 'ALLOW' : 'DENY');
}
const fanoutWallet = randomUUID();
for (let index = 1; index <= 3; index++) {
	const result = await evaluate(command(fanoutWallet, '2500000', randomUUID(), youngAccount));
	assert.equal(result.score, index < 3 ? 30 : 70);
}
console.log('Passed: amount threshold, stable DENY replay, conflict, fifth attempt, three recipients + young account.');
console.log('Synthetic attempts remain in the test platform database. No money was changed.');
