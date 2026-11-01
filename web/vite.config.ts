import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({command}) => ({
	base: command === 'build' ? '/intelligence/' : '/',
	plugins: [react()],
	server: {
		port: 5174, strictPort: true,
		proxy: {
			'/api': 'http://127.0.0.1:8081',
			'/actuator/health': 'http://127.0.0.1:8081',
		},
	},
	build: { outDir: 'dist', sourcemap: false },
}));
