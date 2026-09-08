<!--
  Required Notice: Copyright Dr. Juergen A. Reiss
  Licensed under the terms in the LICENSE file at the repository root.
-->

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { configApi } from '../api/config'
import { ApiError } from '../api/http'
import type { AssignableMenuKey, MenuVisibilityConfig, UserDisplayFormat, UserIdStorageConfig, UserIdStorageMode } from '../api/types'
import { useToast } from '../composables/useToast'
import { useChangeReload } from '../composables/useChangeReload'

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
  { key: 'audit', title: 'Audit' },
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
useChangeReload('dev.juergenreiss.cdrm.config.', load)

const USER_ID_STORAGE_KEY = 'user-id-storage'

const displayFormatOptions: { title: string; value: UserDisplayFormat }[] = [
  { title: 'UUID', value: 'UUID' },
  { title: 'Firstname Lastname, email', value: 'FIRSTNAME_LASTNAME_EMAIL' },
  { title: 'Lastname, Firstname, email', value: 'LASTNAME_FIRSTNAME_EMAIL' },
  { title: 'Email', value: 'EMAIL' },
]

const userIdStorageMode = ref<UserIdStorageMode>('USER_UUID')
const userIdStorageDisplayFormat = ref<UserDisplayFormat>('UUID')
const userIdStorageLoading = ref(false)
const userIdStorageSaving = ref(false)
const userIdStorageError = ref<string | null>(null)

async function loadUserIdStorage() {
  userIdStorageLoading.value = true
  userIdStorageError.value = null
  try {
    const response = await configApi.get(USER_ID_STORAGE_KEY)
    const config = response.value as UserIdStorageConfig
    userIdStorageMode.value = config.mode
    userIdStorageDisplayFormat.value = config.displayFormat
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) {
      // Not configured yet — same default the backend itself falls back to.
      userIdStorageMode.value = 'USER_UUID'
      userIdStorageDisplayFormat.value = 'UUID'
    } else {
      userIdStorageError.value = e instanceof ApiError ? `${e.status}: ${e.message}` : 'Failed to load user ID storage setting'
    }
  } finally {
    userIdStorageLoading.value = false
  }
}

async function saveUserIdStorage() {
  userIdStorageSaving.value = true
  userIdStorageError.value = null
  try {
    const config: UserIdStorageConfig = { mode: userIdStorageMode.value, displayFormat: userIdStorageDisplayFormat.value }
    await configApi.update(USER_ID_STORAGE_KEY, config)
    await showToast('User ID storage setting saved')
  } catch (e) {
    userIdStorageError.value = e instanceof ApiError ? `${e.status}: ${e.message}` : 'Failed to save user ID storage setting'
  } finally {
    userIdStorageSaving.value = false
  }
}

onMounted(loadUserIdStorage)
useChangeReload('dev.juergenreiss.cdrm.config.', loadUserIdStorage)
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

  <v-card class="mt-8" variant="outlined">
    <v-card-title class="text-subtitle-1">User ID Storage</v-card-title>
    <v-card-text>
      <v-alert v-if="userIdStorageError" type="error" :text="userIdStorageError" class="mb-4" />
      <p class="text-body-2 text-medium-emphasis mb-4">
        Controls whether cdrm records who created or last modified each entity (clusters, stages, products,
        workloads, releases, and configuration entries). "User UUID" is the existing behavior. "None" stores no
        identifier at all — for a company that doesn't want to keep even a pseudonymous one; existing history is
        left as-is, only future actions are affected.
      </p>
      <v-radio-group v-model="userIdStorageMode" inline hide-details :disabled="userIdStorageLoading" class="mb-4">
        <v-radio label="User UUID" value="USER_UUID" />
        <v-radio label="None" value="NONE" />
      </v-radio-group>
      <p v-if="userIdStorageMode === 'USER_UUID'" class="text-body-2 text-medium-emphasis mb-2">
        How a user id is displayed in "By" columns throughout the app. Any format other than UUID requires
        capturing each user's first name, last name, and email — captured only from users who act while this
        setting is active, not retroactively.
      </p>
      <v-select
        v-if="userIdStorageMode === 'USER_UUID'"
        v-model="userIdStorageDisplayFormat"
        :items="displayFormatOptions"
        label="Display format"
        :disabled="userIdStorageLoading"
        hide-details
        style="max-width: 360px"
      />
      <div class="d-flex justify-end mt-4">
        <v-btn color="primary" :loading="userIdStorageSaving" :disabled="userIdStorageLoading" @click="saveUserIdStorage">
          Save
        </v-btn>
      </div>
    </v-card-text>
  </v-card>
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
