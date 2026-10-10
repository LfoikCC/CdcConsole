import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { installUi } from './components/ui'
import './styles.css'
import './monitor.css'
import App from './App.vue'
import router from './router'
import { dbxHost } from './hostAdapter'

async function start() {
  await dbxHost.initialize()
  const app = createApp(App).use(createPinia()).use(router)
  installUi(app)
  app.mount('#app')
}

start()
