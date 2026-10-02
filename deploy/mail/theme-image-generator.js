// Renders every theme of backend/src/main/resources/mail/themes.json into <out>/<key>/{header,footer}.jpg
// using theme-image-generator.html. usage: node deploy/mail/theme-image-generator.js frontend/public/email/themes
// (needs the playwright package and a Chromium; set CHROMIUM=/path/to/chromium if it is not the default one)
const { chromium } = require('playwright')
const path = require('path')
const fs = require('fs')

const OUT = path.resolve(process.argv[2] || 'frontend/public/email/themes')
const REGISTRY = path.join(__dirname, '../../backend/src/main/resources/mail/themes.json')
const keys = JSON.parse(fs.readFileSync(REGISTRY, 'utf8')).map((t) => t.key)

;(async () => {
  const browser = await chromium.launch(process.env.CHROMIUM ? { executablePath: process.env.CHROMIUM } : {})
  const page = await browser.newPage({ viewport: { width: 1200, height: 360 } })
  for (const key of keys) {
    for (const part of ['header', 'footer']) {
      await page.goto('file://' + path.join(__dirname, 'theme-image-generator.html') + `?theme=${key}&part=${part}`)
      await page.waitForTimeout(150)
      const dir = path.join(OUT, key)
      fs.mkdirSync(dir, { recursive: true })
      await (await page.$('svg')).screenshot({ path: path.join(dir, part + '.jpg'), type: 'jpeg', quality: 86 })
      console.log(`${key}/${part}.jpg`)
    }
  }
  await browser.close()
})()
