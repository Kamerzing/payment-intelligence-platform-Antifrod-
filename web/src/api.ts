export type PlatformSnapshot = {
	backend: boolean;
	database: boolean;
	version: string | null;
	checkedAt: Date;
};

async function readJson(path: string, signal: AbortSignal): Promise<unknown> {
	const response = await fetch(path, { signal, headers: { Accept: 'application/json' }, cache: 'no-store' });
	if (!response.ok) throw new Error('Service unavailable');
	return response.json();
}

function isRecord(value: unknown): value is Record<string, unknown> {
	return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export async function loadPlatformStatus(signal: AbortSignal): Promise<PlatformSnapshot> {
	const [status, readiness] = await Promise.allSettled([
		readJson('/api/v1/status', signal), readJson('/actuator/health/readiness', signal),
	]);
	const payload = status.status === 'fulfilled' && isRecord(status.value) ? status.value : null;
	const backend = payload?.service === 'payment-intelligence-platform' && payload?.status === 'UP';
	const database = readiness.status === 'fulfilled' && isRecord(readiness.value) && readiness.value.status === 'UP';
	return {
		backend, database,
		version: backend && typeof payload?.version === 'string' ? payload.version : null,
		checkedAt: new Date(),
	};
}
