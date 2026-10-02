/** @type {import('tailwindcss').Config} */
export default {
  darkMode: ['class'],
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        bg: 'rgb(var(--bg) / <alpha-value>)',
        ink: 'rgb(var(--ink) / <alpha-value>)',
        night: 'rgb(var(--night) / <alpha-value>)',
        navy: 'rgb(var(--navy) / <alpha-value>)',
        gold: { DEFAULT: 'rgb(var(--gold) / <alpha-value>)', soft: 'rgb(var(--gold-soft) / <alpha-value>)', deep: 'rgb(var(--gold-deep) / <alpha-value>)', ink: 'rgb(var(--gold-ink) / <alpha-value>)' },
        pearl: 'rgb(var(--pearl) / <alpha-value>)',
        surface: { DEFAULT: 'rgb(var(--surface) / <alpha-value>)', 2: 'rgb(var(--surface-2) / <alpha-value>)' },
        line: 'rgb(var(--line) / <alpha-value>)',
        fg: { DEFAULT: 'rgb(var(--fg) / <alpha-value>)', muted: 'rgb(var(--fg-muted) / <alpha-value>)', dim: 'rgb(var(--fg-dim) / <alpha-value>)' },
        success: 'rgb(var(--success) / <alpha-value>)',
        warn: 'rgb(var(--warn) / <alpha-value>)',
        danger: 'rgb(var(--danger) / <alpha-value>)',
        info: 'rgb(var(--info) / <alpha-value>)',
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
