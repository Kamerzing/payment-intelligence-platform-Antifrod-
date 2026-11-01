import { test, expect } from '@playwright/test';

test('shows actual responses and clears successful state after a failed refresh', async ({ page }) => {
	let available = true;
	await page.route('**/api/v1/status', route => available ? route.fulfill({ json: {
		service: 'payment-intelligence-platform', status: 'UP', version: '0.1.0',
	} }) : route.fulfill({ status: 503, json: { status: 503 } }));
	await page.route('**/actuator/health/readiness', route => available
		? route.fulfill({ json: { status: 'UP' } }) : route.fulfill({ status: 503, json: { status: 'DOWN' } }));
	await page.goto('/');
	await expect(page.getByText('Доступен', { exact: true })).toHaveCount(2);
	await expect(page.getByText('Статус не проверен', { exact: true })).toBeVisible();
	await expect(page.getByText('ИИ по умолчанию выключен')).toBeVisible();
	available = false;
	await page.getByRole('button', { name: 'Обновить статус' }).click();
	await expect(page.getByText('Недоступен', { exact: true })).toHaveCount(2);
	await expect(page.getByText('Версия 0.1.0')).toHaveCount(0);
});

test('fits a narrow screen without horizontal overflow', async ({ page }) => {
	await page.setViewportSize({ width: 390, height: 844 });
	await page.route('**/api/v1/status', route => route.fulfill({ status: 503, json: {} }));
	await page.route('**/actuator/health/readiness', route => route.fulfill({ status: 503, json: {} }));
	await page.goto('/');
	await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
	await expect(page.getByRole('button', { name: 'Обновить статус' })).toBeEnabled();
	expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});
