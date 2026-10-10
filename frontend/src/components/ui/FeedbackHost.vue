<script setup>
import { feedback, finishConfirmation } from './feedback'
import { CdcModal, CdcButton } from './index'
</script>
<template>
  <CdcModal :model-value="!!feedback.confirmation" :title="feedback.confirmation?.title" @close="finishConfirmation(false)">
    <p class="confirmation-message">{{ feedback.confirmation?.message }}</p>
    <template #footer><CdcButton @click="finishConfirmation(false)">取消</CdcButton><CdcButton type="primary" @click="finishConfirmation(true)">确认</CdcButton></template>
  </CdcModal>
  <div class="toast-stack" aria-live="polite"><div v-for="item in feedback.notices" :key="item.id" :class="['toast', item.type]" :role="item.type==='error'?'alert':'status'">{{ item.message }}</div></div>
</template>
