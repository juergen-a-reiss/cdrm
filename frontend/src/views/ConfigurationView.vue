<!--
  Required Notice: Copyright Dr. Juergen A. Reiss
  Licensed under the terms in the LICENSE file at the repository root.
-->

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { configApi } from '../api/config'
import { ApiError } from '../api/http'
import type { AssignableMenuKey, MenuVisibilityConfig } from '../api/types'
import { useToast } from '../composables/useToast'

const MENU_VISIBILITY_KEY = 'menu-visibility'

// Fixed lists rather than fetched: these are the roles cdrm knows about and the menu
// entries a role can be whitelisted for (matching the backend's MenuEntries.ASSIGNABLE
// and App.vue's navItems) — "Configuration" itself is intentionally not offered here,
// since it's always visible to cdrm-devops regardless of this config.
const ROLES = ['cdrm-devops', 'cdrm-productowner', 'cdrm-developer', 'cdrm-manager']
const MENU_ITEMS: { key: AssignableMenuKey; title: string }[] = [
  { key: 'clusters', title: 'Clusters' },
  { key: 'stages', title: 'Stages' },
  { key: 'products', title: 'Products' },
  { key: 'workloads', title: 'Workloads' },
  { key: 'releases', title: 'Releases' },
  { key: 'release-history', title: 'Release History' },
]

// checked[role][menuKey] — reactive so v-checkbox bindings below can mutate it directly.
const checked = reactive<Record<string, Record<AssignableMenuKey, boolean>>>(
  Object.fromEntries(ROLES.map((role) => [role, Object.fromEntries(MENU_ITEMS.map((item) => [item.key, false]))])) as Record<
    string,
    Record<AssignableMenuKey, boolean>
  >,
)

const loading = ref(false)
const saving = ref(false)
const error = ref<string | null>(null)
const { showToast } = useToast()

async function load() {
  loading.value = true
  error.value = null
  try {
    const response = await configApi.get(MENU_VISIBILITY_KEY)
    const whitelist = response.value as MenuVisibilityConfig
    for (const role of ROLES) {
      const allowed = new Set(whitelist[role] ?? [])
      for (const item of MENU_ITEMS) {
        checked[role][item.key] = allowed.has(item.key)
      }
    }
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.status}: ${e.message}` : 'Failed to load menu visibility'
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  error.value = null
  try {
    const whitelist: MenuVisibilityConfig = Object.fromEntries(
      ROLES.map((role) => [role, MENU_ITEMS.filter((item) => checked[role][item.key]).map((item) => item.key)]),
    )
    await configApi.update(MENU_VISIBILITY_KEY, whitelist)
    await showToast('Menu visibility saved')
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.status}: ${e.message}` : 'Failed to save menu visibility'
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <v-toolbar flat>
    <v-toolbar-title>Configuration</v-toolbar-title>
  </v-toolbar>

  <v-alert v-if="error" type="error" :text="error" class="mb-4" />

  <p class="text-body-2 text-medium-emphasis mb-4">
    Choose which menu entries each role sees in the navigation drawer. cdrm-devops always sees Configuration itself.
  </p>

  <v-table v-if="!loading">
    <thead>
      <tr>
        <th>Role</th>
        <th v-for="item in MENU_ITEMS" :key="item.key" class="text-left">{{ item.title }}</th>
      </tr>
    </thead>
    <tbody>
      <tr v-for="role in ROLES" :key="role">
        <td>{{ role }}</td>
        <td v-for="item in MENU_ITEMS" :key="item.key" class="text-left">
          <v-checkbox-btn v-model="checked[role][item.key]" />
        </td>
      </tr>
    </tbody>
  </v-table>

  <div class="d-flex justify-end mt-4">
    <v-btn color="primary" :loading="saving" :disabled="loading" @click="save">Save</v-btn>
  </div>
</template>

<style scoped>
/* Same reasoning as ResourceTable: keep header/role labels on one line so a narrow
   (tablet-width) viewport scrolls the grid sideways (Vuetify's .v-table__wrapper
   already supports that) instead of wrapping them and misaligning the checkbox columns. */
:deep(.v-table__wrapper th),
:deep(.v-table__wrapper td) {
  white-space: nowrap;
}

/* On phone-width screens, let the role names and menu-entry titles wrap instead —
   a narrower grid beats one you have to scroll sideways to use. */
@media (max-width: 600px) {
  :deep(.v-table__wrapper th),
  :deep(.v-table__wrapper td) {
    white-space: normal;
  }
}
</style>
