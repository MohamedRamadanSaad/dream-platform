/** @type {import('tailwindcss').Config} */
export default {
  darkMode: ['class'],
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        // Brand colours are the same in both themes, so they are literal hex values here (kept in sync with the
        // --night/--gold… variables in globals.css): Tailwind can only apply opacity modifiers such as bg-gold/15
        // or bg-night/60 to colours it can parse — with var(--gold) those classes were silently dropped.
        ink: '#1e3a5f',
        night: '#0a1128',
        navy: '#16244a',
        gold: { DEFAULT: '#d4af37', soft: '#eadbaa', deep: '#a8872a' },
        pearl: '#f4efe6',
        surface: { DEFAULT: 'var(--surface)', 2: 'var(--surface-2)' },
        line: 'var(--line)',
        fg: { DEFAULT: 'var(--fg)', muted: 'var(--fg-muted)', dim: 'var(--fg-dim)' },
        success: '#2E7D5B',
        warn: '#C27C2A',
        danger: '#B5442D',
        info: '#3B6EA8',
        // readable status text on either theme (the solid status colours are too dark on night surfaces)
        'ok-ink': 'var(--ok-ink)',
        'bad-ink': 'var(--bad-ink)',
        'info-ink': 'var(--info-ink)',
        viz: { line: 'var(--viz-line)', prev: 'var(--viz-prev)', bar: 'var(--viz-bar)', 1: 'var(--viz-1)', 2: 'var(--viz-2)', 3: 'var(--viz-3)', grid: 'var(--viz-grid)' },
      },
      fontFamily: {
        display: ['"IBM Plex Sans Arabic"', '"IBM Plex Sans"', 'system-ui', 'sans-serif'],
        body: ['"IBM Plex Sans Arabic"', '"IBM Plex Sans"', 'system-ui', 'sans-serif'],
        quran: ['Amiri', 'serif'],
      },
      borderRadius: { xl2: '1.75rem' },
      boxShadow: { calm: '0 24px 48px -24px rgba(10,17,40,.35)' },
    },
  },
  plugins: [],
}
