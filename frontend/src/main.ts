// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

import { createApp } from 'vue'
import App from './App.vue'
import vuetify from './plugins/vuetify'
import { router } from './router'
import { handleLoginCallback, handleSilentRenewCallback, restoreSession } from './auth/authService'

async function bootstrap() {
  const path = window.location.pathname

  if (path === '/auth/silent-renew') {
    await handleSilentRenewCallback()
    return
  }

  if (path === '/auth/callback') {
    await handleLoginCallback()
  } else {
    await restoreSession()
  }

  createApp(App).use(vuetify).use(router).mount('#app')
}

bootstrap()
