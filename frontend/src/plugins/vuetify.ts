// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

import 'vuetify/styles'
import '@mdi/font/css/materialdesignicons.css'
import '../styles/brand.css'
import { createVuetify } from 'vuetify'

export const THEME_STORAGE_KEY = 'cdrm-theme'

const vuetify = createVuetify({
  icons: {
    defaultSet: 'mdi',
  },
})

// Applied here (module load, before mount) rather than in a component, so
// the correct theme is active for the very first paint — no light-then-
// dark flash. A stored choice wins; otherwise fall back to the OS/browser
// preference.
const stored = localStorage.getItem(THEME_STORAGE_KEY)
const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches
vuetify.theme.change(stored === 'light' || stored === 'dark' ? stored : prefersDark ? 'dark' : 'light')

export default vuetify
