/** @type {import('tailwindcss').Config} */
export default {
  darkMode: ['class'],
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        ink: 'var(--ink)',
        night: 'var(--night)',
        navy: 'var(--navy)',
        gold: { DEFAULT: 'var(--gold)', soft: 'var(--gold-soft)', deep: 'var(--gold-deep)' },
        pearl: 'var(--pearl)',
        surface: { DEFAULT: 'var(--surface)', 2: 'var(--surface-2)' },
        line: 'var(--line)',
        fg: { DEFAULT: 'var(--fg)', muted: 'var(--fg-muted)', dim: 'var(--fg-dim)' },
        success: '#2E7D5B',
        warn: '#C27C2A',
        danger: '#B5442D',
        info: '#3B6EA8',
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
