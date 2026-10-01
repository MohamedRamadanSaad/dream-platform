// Serves the VPS setup scripts from the preview site (the GitHub repo is private): /ops/install.sh, /ops/bootstrap.sh
import { copyFileSync, mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
const root = join(dirname(fileURLToPath(import.meta.url)), '..')
mkdirSync(join(root, 'public/ops'), { recursive: true })
for (const f of ['install.sh', 'bootstrap.sh']) copyFileSync(join(root, '../deploy', f), join(root, 'public/ops', f))
