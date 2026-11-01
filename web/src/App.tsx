import { useEffect, useState } from 'react';
import { loadPlatformStatus, type PlatformSnapshot } from './api';
import { Analyst } from './Analyst';

const roadmap = [
	['01', 'Основа платформы', 'Окружение, документация и запускаемый каркас', 'Реализовано'],
	['02', 'События и антифрод', 'Витрина и проверка расходных команд до списания', 'Реализовано'],
	['03', 'Расследования', 'Кабинет ADMIN, разметка и история изменений', 'Реализовано'],
	['04', 'ИИ-анализ', 'Локальные факты, объяснения и рекомендации', 'Доступен в кабинете'],
];

function State({ value, pending }: { value: boolean; pending: boolean }) {
	return <span className={`badge ${pending ? 'neutral' : value ? 'good' : 'warn'}`}>
		<span className="dot" />{pending ? 'Проверяем' : value ? 'Доступен' : 'Недоступен'}
	</span>;
}

export function App() {
	return window.location.pathname.startsWith('/intelligence') ? <Analyst/> : <Overview/>;
}

function Overview() {
	const [snapshot, setSnapshot] = useState<PlatformSnapshot | null>(null);
	const [loading, setLoading] = useState(true);
	const [refresh, setRefresh] = useState(0);

	useEffect(() => {
		const controller = new AbortController();
		const timeout = window.setTimeout(() => controller.abort(), 6000);
		let active = true;
		setLoading(true);
		setSnapshot(null);
		loadPlatformStatus(controller.signal).then(result => {
			if (active) { setSnapshot(result); setLoading(false); }
		}).finally(() => window.clearTimeout(timeout));
		return () => { active = false; window.clearTimeout(timeout); controller.abort(); };
	}, [refresh]);

	return <div className="layout">
		<aside className="sidebar">
			<a href="/" className="brand"><span className="brand-icon">pi</span><span>Payment<br />Intelligence</span></a>
			<div className="workspace-label">РАБОЧЕЕ ПРОСТРАНСТВО</div>
			<div className="nav-current"><span aria-hidden="true">▦</span> Обзор платформы</div>
			<div className="sidebar-note"><span className="dot" /> Локальная среда<span>Тестовые средства · RUB</span></div>
		</aside>
		<main>
			<header className="topbar"><span>Платформа / <strong>Обзор</strong></span><span className="environment">LOCAL</span></header>
			<div className="content">
				<section className="heading">
					<div><p className="eyebrow">PAYMENT INTELLIGENCE PLATFORM</p><h1>Основа для уверенных решений</h1>
						<p className="description">Операции, оценка риска и интеллектуальный анализ в одном рабочем пространстве.</p></div>
					<button onClick={() => setRefresh(value => value + 1)} disabled={loading}>{loading ? 'Проверяем…' : 'Обновить статус'}</button>
				</section>
				<section className="intro">
					<div><span className="intro-label">СТАРТОВАЯ ВЕРСИЯ</span><h2>Платформа начинается с надёжной основы</h2>
						<p>Доступны события, правила риска, проверка до списания и расследования. Кабинет аналитика открывается через Payment Core по адресу /intelligence/ после входа ADMIN. Локальный ИИ запускается по запросу из карточки попытки.</p></div>
					<div className="intro-mark" aria-hidden="true">↗</div>
				</section>
				<section aria-labelledby="system-title">
					<div className="section-heading"><h2 id="system-title">Состояние системы</h2><span aria-live="polite">{snapshot ? `Проверено в ${snapshot.checkedAt.toLocaleTimeString('ru-RU')}` : 'Ожидаем ответ'}</span></div>
					<div className="status-grid" aria-live="polite">
						<article className="card"><span className="card-label">Приложение</span><div className="card-value">Backend</div><State value={snapshot?.backend ?? false} pending={loading} /><p>{snapshot?.version ? `Версия ${snapshot.version}` : 'Сервис платформы'}</p></article>
						<article className="card"><span className="card-label">Хранилище</span><div className="card-value">PostgreSQL</div><State value={snapshot?.database ?? false} pending={loading} /><p>Проверка готовности базы данных</p></article>
						<article className="card"><span className="card-label">Платёжное ядро</span><div className="card-value">Payment Core</div><span className="badge neutral">Статус не проверен</span><p>Доставка событий проверяется отдельно</p></article>
					</div>
					{snapshot && (!snapshot.backend || !snapshot.database) && <p className="notice" role="status">Часть компонентов недоступна. Проверьте запуск приложения и базы данных, затем обновите статус.</p>}
				</section>
				<section className="roadmap" aria-labelledby="roadmap-title">
					<div className="section-heading"><h2 id="roadmap-title">Путь развития</h2><span>От операций к объяснениям</span></div>
					<div className="steps">{roadmap.map(([number, title, detail, state], index) => <article className="step" key={number}>
						<span className={`step-number ${index === 2 ? 'active' : ''}`}>{number}</span><div><h3>{title}</h3><p>{detail}</p></div><span className={`step-state ${index === 2 ? 'current' : ''}`}>{state}</span>
					</article>)}</div>
				</section>
				<footer><span>Закрытый пилот с моделируемыми средствами</span><span>ИИ по умолчанию выключен</span></footer>
			</div>
		</main>
	</div>;
}
