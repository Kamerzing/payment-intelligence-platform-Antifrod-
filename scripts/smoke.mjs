import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';

const base = process.env.PIP_SMOKE_URL || 'http://127.0.0.1:8081';
const checks = [];
async function request(path, expectedStatus, options = {}) {
	const response = await fetch(new URL(path, base), { ...options, signal: AbortSignal.timeout(8000) });
	assert.equal(response.status, expectedStatus, `${path}: unexpected HTTP status`);
	assert.match(response.headers.get('x-request-id') || '', /^[0-9a-f-]{36}$/);
	checks.push({ path, status: response.status });
	return response;
}

const status = await (await request('/api/v1/status', 200)).json();
assert.equal(status.service, 'payment-intelligence-platform');
assert.equal(status.status, 'UP');
assert.equal(status.capabilities.payment_core, 'DELIVERY_NOT_VERIFIED');
assert.equal(status.capabilities.risk, 'RULES_IMPLEMENTED');
assert.equal(status.capabilities.ai, 'DISABLED');
for (const path of ['/actuator/health/readiness', '/actuator/health/liveness']) {
	const health = await (await request(path, 200)).json();
	assert.equal(health.status, 'UP');
	assert.equal(health.components, undefined);
}
const page = await request('/', 200);
assert.match(page.headers.get('content-security-policy') || '', /default-src 'self'/);
const html = await page.text();
assert.match(html, /id="root"/);
const assets = [...new Set([...html.matchAll(/(?:src|href)="(\/(?:intelligence\/)?assets\/[^\"]+)"/g)].map(match => match[1]))];
assert.ok(assets.length >= 2, 'The JAR must include built JS and CSS');
for (const asset of assets) await request(asset, 200);
for (const [path, expectedStatus, options] of [
	['/api/v1/investigations', 401, {}],
	['/internal/v1/risk/evaluations', 401, { method: 'POST' }],
	['/api/v1/investigations', 403, { method: 'POST' }],
	['/assets/missing.js', 404, {}],
]) {
	const response = await request(path, expectedStatus, options);
	const error = await response.json();
	assert.equal(error.status, expectedStatus);
	assert.equal(error.request_id, response.headers.get('x-request-id'));
	assert.equal(typeof error.code, 'string');
	assert.equal(typeof error.timestamp, 'string');
}
const api = await (await request('/v3/api-docs', 200)).json();
assert.ok(api.paths['/api/v1/status']);
await request('/swagger-ui/index.html', 200);
const reportDirectory = new URL('../build/', import.meta.url);
await mkdir(reportDirectory, { recursive: true });
await writeFile(new URL('smoke.json', reportDirectory), JSON.stringify({ checked_at: new Date(), checks }, null, 2) + '\n');
console.log(`Smoke OK: ${checks.length} checks; bundled UI, readiness, errors, closed APIs and local OpenAPI.`);
