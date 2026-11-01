import {describe,it,expect} from 'vitest';
import {rubles,accountAge} from './analyst-api';
describe('точные суммы аналитика',()=>{
	it('сохраняет копейки за пределами Number',()=>{expect(rubles('9223372036854775807').replaceAll('\u00a0',' ')).toBe('92 233 720 368 547 758,07 ₽');});
	it('отображает копейки и нулевую комиссию',()=>{expect(rubles('1')).toBe('0,01 ₽');expect(rubles('0')).toBe('0,00 ₽');});
});
describe('возраст учётной записи',()=>{
	it('показывает русский интервал вместо служебного формата',()=>{expect(accountAge('PT25H1M2.3S')).toBe('1 дн 1 ч 1 мин 2 с');expect(accountAge('P2D')).toBe('2 дн');});
	it('явно обрабатывает короткий и отсутствующий интервал',()=>{expect(accountAge('PT0.1S')).toBe('менее секунды');expect(accountAge('missing')).toBe('не указан');});
});
