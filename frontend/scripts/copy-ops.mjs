// Serves the VPS setup scripts from the preview site (the GitHub repo is private): /ops/install.sh, /ops/bootstrap.sh
// Skips quietly when ../deploy is outside the build context (e.g. the frontend Docker build on the VPS).
import { copyFileSync, existsSync, mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const src = join(root, '../deploy')
if (!existsSync(src)) {
  console.log('copy-ops: ../deploy not found, skipping')
} else {
  mkdirSync(join(root, 'public/ops'), { recursive: true })
  for (const f of ['install.sh', 'bootstrap.sh']) {
    const from = join(src, f)
    if (existsSync(from)) copyFileSync(from, join(root, 'public/ops', f))
  }
}
