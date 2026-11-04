<script setup>
defineProps({ card: { type: Object, required: true } })
</script>

<template>
  <div class="bi-text">{{ card.config?.text || '在右侧配置里填写文字' }}</div>
</template>
