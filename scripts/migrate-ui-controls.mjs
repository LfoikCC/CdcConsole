import { readdir, readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'

// Mechanical migration: preserve template bindings and business handlers.
const root = resolve(import.meta.dirname, '../frontend/src')
async function migrate(directory) {
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = resolve(directory, entry.name)
    if (entry.isDirectory() && entry.name !== 'ui') await migrate(path)
    if (!entry.isFile() || !entry.name.endsWith('.vue') || entry.name === 'App.vue') continue
    const original = await readFile(path, 'utf8')
    const relative = directory.endsWith('views') ? '../components/ui/feedback' : './ui/feedback'
    let result = original.replace(/(<\/?)(el-[a-z-]+)/g, (_, prefix, name) => `${prefix}${name === 'el-dialog' ? 'cdc-modal' : name.replace(/^el-/, 'cdc-')}`)
      .replace(/import \{ (ElMessage(?:, ElMessageBox)?) \} from 'element-plus'/g, (_, names) => `import { ${names.replaceAll('ElMessageBox', 'CdcMessageBox').replaceAll('ElMessage', 'CdcMessage')} } from '${relative}'`)
      .replaceAll('ElMessageBox', 'CdcMessageBox').replaceAll('ElMessage', 'CdcMessage')
      .replace(/<style[^>]*>[\s\S]*?<\/style>/g, '')
    if (result !== original) await writeFile(path, result)
  }
}
await migrate(root)
