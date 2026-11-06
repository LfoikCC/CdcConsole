<script setup>
import { ref } from 'vue'
import { FileSpreadsheet, UploadCloud } from 'lucide-vue-next'
import { CdcMessage } from '../ui/feedback'

const props = defineProps({
  maxMb: { type: Number, default: 20 },
  fileName: { type: String, default: '' }
})
const emit = defineEmits(['file'])
const dragging = ref(false)

function accept(file) {
  if (!file) return
  if (!/\.(xlsx|xls)$/i.test(file.name)) {
    CdcMessage.warning('只支持 .xlsx 或 .xls 文件')
    return
  }
  if (file.size > props.maxMb * 1024 * 1024) {
    CdcMessage.warning(`文件不能超过 ${props.maxMb}MB`)
    return
  }
  emit('file', file)
}

function onPick(event) {
  accept(event.target.files?.[0])
  event.target.value = ''
}

function onDrop(event) {
  dragging.value = false
  accept(event.dataTransfer?.files?.[0])
}
</script>

<template>
  <div class="excel-uploader" :class="{ dragging }"
       @dragover.prevent="dragging = true" @dragleave.prevent="dragging = false" @drop.prevent="onDrop">
    <input id="excel-file" type="file" accept=".xlsx,.xls" hidden @change="onPick"/>
    <label for="excel-file" class="eu-body">
      <span class="eu-icon"><FileSpreadsheet v-if="fileName" :size="26"/><UploadCloud v-else :size="26"/></span>
      <strong>{{ fileName || '点击或拖拽文件到此处' }}</strong>
      <small>{{ fileName ? '重新选择会替换当前文件' : `支持 .xlsx / .xls，最大 ${maxMb}MB` }}</small>
    </label>
  </div>
</template>

<style scoped>
.excel-uploader {
  border: 1px dashed var(--border);
  border-radius: 10px;
  background: var(--card);
  transition: border-color .15s, background .15s;
}
.excel-uploader.dragging { border-color: var(--primary); background: var(--accent); }
.eu-body { display: flex; flex-direction: column; align-items: center; gap: 4px; padding: 26px 16px; cursor: pointer; text-align: center; }
.eu-icon { color: var(--muted-fg); }
.eu-body strong { font-size: 13px; }
.eu-body small { color: var(--muted-fg); font-size: 11.5px; }
</style>
