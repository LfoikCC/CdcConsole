<script setup>
import { computed } from 'vue'
const props=defineProps({type:String,label:String,showName:Boolean})
const brands={MYSQL:['MySQL','mysql-small.png'],SQLSERVER:['SQL Server','sqlserver-icon.png'],STARROCKS:['StarRocks','starrocks-icon.svg'],STARROCKS_STREAM_LOAD:['StarRocks','starrocks-icon.svg'],KAFKA:['Kafka','kafka-icon.svg']}
const brand=computed(()=>brands[props.type])
const name=computed(()=>props.label||brand.value?.[0]||props.type||'未知类型')
const description=computed(()=>props.label&&brand.value&&props.label!==brand.value[0]?`${brand.value[0]} · ${props.label}`:name.value)
</script>
<template>
  <span class="db-brand" :title="description" :aria-label="description" :role="brand && !label && !showName ? 'img' : undefined">
    <span v-if="brand" class="brand-icon" aria-hidden="true">
      <span v-if="type === 'KAFKA'" class="brand-icon-kafka" />
      <img v-else :src="'/brands/'+brand[1]" alt="" width="24" height="24" />
    </span>
    <span v-if="label || showName || !brand" class="db-name">{{name}}</span>
  </span>
</template>
<style scoped>
.db-brand{display:inline-flex;align-items:center;gap:8px;vertical-align:middle;min-width:0;line-height:1.4}
.db-name{font-size:11.5px}
.brand-icon{display:inline-flex;align-items:center;justify-content:center;width:24px;height:24px;flex:0 0 24px}
.brand-icon img{display:block;width:24px;height:24px;object-fit:contain}
.brand-icon-kafka{display:block;width:24px;height:24px;background:var(--foreground);mask:url('/brands/kafka-icon.svg') center/contain no-repeat}
</style>
