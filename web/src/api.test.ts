import { afterEach, describe, expect, it, vi } from 'vitest';
import { loadPlatformStatus } from './api';

afterEach(() => vi.unstubAllGlobals());

describe('platform status', () => {
	it('reads backend and database independently', async () => {
		vi.stubGlobal('fetch', vi.fn(async (path: string) => Response.json(path.includes('readiness')
			? { status: 'UP' } : { service: 'payment-intelligence-platform', status: 'UP', version: '0.1.0' })));
		const result = await loadPlatformStatus(new AbortController().signal);
		expect(result).toMatchObject({ backend: true, database: true, version: '0.1.0' });
	});

	it('does not report a healthy database when readiness fails', async () => {
		vi.stubGlobal('fetch', vi.fn(async (path: string) => path.includes('readiness')
			? Response.json({ status: 'DOWN' }, { status: 503 })
			: Response.json({ service: 'payment-intelligence-platform', status: 'UP', version: '0.1.0' })));
		expect(await loadPlatformStatus(new AbortController().signal)).toMatchObject({ backend: true, database: false });
	});

	it('rejects malformed status and does not invent a version', async () => {
		vi.stubGlobal('fetch', vi.fn(async () => Response.json({ status: 'UP', version: 'fake' })));
		expect(await loadPlatformStatus(new AbortController().signal)).toMatchObject({ backend: false, version: null });
	});

	it('handles network failures without retaining previous health', async () => {
		vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Network error'); }));
		expect(await loadPlatformStatus(new AbortController().signal))
			.toMatchObject({ backend: false, database: false, version: null });
	});
});
