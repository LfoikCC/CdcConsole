import { cp, mkdir, rm, readdir, readFile, writeFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { existsSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import { resolve } from 'node:path'
import { validateManifest } from './validate-dbx-manifest.mjs'

const root = resolve(import.meta.dirname, '..')

async function packagePlugin(directory, archive) {
  validateManifest(JSON.parse(await readFile(resolve(directory, 'manifest.json'), 'utf8')))
  const files = {}
  async function collect(relative = '') {
    const entries = await readdir(resolve(directory, relative), { withFileTypes: true })
    for (const entry of entries.sort((a, b) => a.name.localeCompare(b.name))) {
      const name = relative ? `${relative}/${entry.name}` : entry.name
      if (entry.isDirectory()) await collect(name)
      else if (entry.isFile() && !['checksums.json', 'signature.json'].includes(name)) {
        files[name] = createHash('sha256').update(await readFile(resolve(directory, name))).digest('hex')
      } else if (!entry.isFile()) throw new Error(`Unsupported plugin entry: ${name}`)
    }
  }
  await collect()
  await writeFile(resolve(directory, 'checksums.json'), `${JSON.stringify({ algorithm: 'sha256', files }, null, 2)}\n`)
  await rm(archive, { force: true })
  const entries = [...Object.keys(files).sort(), 'checksums.json']
  execFileSync('jar', ['--create', '--no-manifest', '--file', archive,
    ...entries.flatMap(name => ['-C', directory, name])], { stdio: 'inherit' })
  const packaged = execFileSync('jar', ['--list', '--file', archive], { encoding: 'utf8' }).trim().split(/\r?\n/)
  if (packaged.length !== entries.length || packaged.some(name => !entries.includes(name))) {
    throw new Error('Plugin archive entries do not match checksums.json')
  }
}
const output = resolve(root, 'dist', 'dbx-plugin')
const staticDir = resolve(root, 'dist', 'dbx-ui')
if (!existsSync(staticDir)) throw new Error('请先运行 vite build --mode dbx')
await rm(output, { recursive: true, force: true })
await mkdir(resolve(output, 'ui'), { recursive: true })
await cp(staticDir, resolve(output, 'ui'), { recursive: true })
await cp(resolve(root, 'plugin', 'manifest.json'), resolve(output, 'manifest.json'))
await cp(resolve(root, 'plugin', 'dbx-plugin.toml'), resolve(output, 'dbx-plugin.toml'))
await cp(resolve(root, 'plugin', 'assets'), resolve(output, 'assets'), { recursive: true })
const archive = resolve(root, 'dist', 'cdc-console.dbxp')
await packagePlugin(output, archive)
console.log(`DBX plugin written to ${archive}`)

const probe = resolve(root, 'dist', 'dbx-sse-probe')
await rm(probe, { recursive: true, force: true })
await mkdir(resolve(probe, 'ui'), { recursive: true })
await cp(resolve(root, 'plugin', 'probe', 'index.html'), resolve(probe, 'ui', 'index.html'))
await cp(resolve(root, 'plugin', 'probe', 'manifest.json'), resolve(probe, 'manifest.json'))
await cp(resolve(root, 'plugin', 'assets'), resolve(probe, 'assets'), { recursive: true })
const probeArchive = resolve(root, 'dist', 'cdc-sse-probe.dbxp')
await packagePlugin(probe, probeArchive)
console.log(`DBX SSE probe written to ${probeArchive}`)
