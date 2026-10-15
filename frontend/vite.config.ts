import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { VitePWA } from 'vite-plugin-pwa'
import path from 'node:path'

export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      // our own service worker (src/sw.ts): precache + SPA fallback like before, plus the Web Push handlers
      // (push → showNotification, notificationclick → open the page) that the generated one never had
      strategies: 'injectManifest',
      srcDir: 'src',
      filename: 'sw.ts',
      registerType: 'autoUpdate',
      includeAssets: ['icons/icon-192.png', 'icons/icon-512.png'],
      manifest: {
        name: 'إلى سعادة الدارين',
        short_name: 'سعادة الدارين',
        description: 'تفسير الرؤى بوضوح وعمق نحو سعادة الدارين',
        lang: 'ar',
        dir: 'rtl',
        id: '/',
        scope: '/',
        start_url: '/',
        display: 'standalone',
        background_color: '#0A1128',
        theme_color: '#0A1128',
        icons: [
          { src: '/icons/icon-192.png', sizes: '192x192', type: 'image/png' },
          { src: '/icons/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'any maskable' },
        ],
      },
      // the generateSW defaults; the navigation fallback and its /api, /ops denylist live in src/sw.ts
      injectManifest: { globPatterns: ['**/*.{js,css,html,ico,png,svg,webmanifest,woff2}'] },
    }),
  ],
  resolve: { alias: { '@': path.resolve(__dirname, 'src') } },
  server: { port: 5173 },
})
