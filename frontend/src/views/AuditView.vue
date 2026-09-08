<!--
  Required Notice: Copyright Dr. Juergen A. Reiss
  Licensed under the terms in the LICENSE file at the repository root.
-->

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import type { DataTableHeader } from 'vuetify/lib/components/VDataTable/types.js'
import ResourceTable from '../components/ResourceTable.vue'
import type { SortByItem } from '../components/ResourceTable.vue'
import { auditApi } from '../api/audit'
import { ApiError } from '../api/http'
import type { AuditAction, AuditEntityType, AuditEntryResponse } from '../api/types'
import { usePersistedRef } from '../composables/usePersistedRef'
import { useUserDisplay } from '../composables/useUserDisplay'
import { formatDateTime } from '../utils/formatDateTime'
import { sortParam } from '../utils/sortParam'

const { displayName, resolve: resolveUserDisplay } = useUserDisplay()

// Everything below comes from the backend already ReBAC-filtered, sorted, and
// paginated (see AuditService/AuditSpecifications) — nothing here fetches the full
// audit_entry table into the browser.

const entityTypeOptions: { title: string; value: AuditEntityType }[] = [
  { title: 'Cluster', value: 'CLUSTER' },
  { title: 'Stage', value: 'STAGE' },
  { title: 'Product', value: 'PRODUCT' },
  { title: 'Workload', value: 'WORKLOAD' },
  { title: 'Config', value: 'CONFIG' },
]
const selectedEntityTypes = usePersistedRef<AuditEntityType[]>('cdrm.audit.entityTypes', [])

const actionOptions: { title: string; value: AuditAction }[] = [
  { title: 'Created', value: 'CREATED' },
  { title: 'Updated', value: 'UPDATED' },
  { title: 'Deleted', value: 'DELETED' },
]
const selectedActions = usePersistedRef<AuditAction[]>('cdrm.audit.actions', [])

// Free-text filter over entity name — sent to the backend (debounced), not matched
// client-side: the table is paginated, so a client-side filter could only ever search
// within whatever page happened to be loaded.
const search = ref('')
const debouncedSearch = ref('')
let searchDebounceTimer: ReturnType<typeof setTimeout> | undefined
watch(search, (value) => {
  clearTimeout(searchDebounceTimer)
  searchDebounceTimer = setTimeout(() => {
    // Vuetify's clearable "x" sets the model to null, not '' — normalize here so
    // debouncedSearch.value.trim() below never sees anything but a string.
    debouncedSearch.value = value ?? ''
  }, 300)
})

interface AuditRow {
  id: string
  entityType: AuditEntityType
  entityName: string
  productName: string | null
  action: AuditAction
  createdAt: string
  createdBy: string
  newState: Record<string, unknown> | null
  changes: Record<string, { old: unknown; new: unknown }> | null
  // Comma list of changed field names, shown directly in the row so "what changed" is
  // visible without expanding — CREATED/DELETED report every field (there's no prior/
  // later state to compare against), UPDATED only the fields that actually differed.
  changedFields: string
}

// Renders a changes/newState value for display — objects/arrays (e.g. a workload's
// linked stages) as compact JSON, everything else as-is; null/undefined as an em dash
// rather than the literal string "null".
function formatValue(value: unknown): string {
  if (value === null || value === undefined) return '—'
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

const sortBy = usePersistedRef<SortByItem[]>('cdrm.audit.sortBy', [{ key: 'createdAt', order: 'desc' }])
const page = ref(1)
const itemsPerPage = usePersistedRef<number>('cdrm.audit.itemsPerPage', 25)
const expanded = ref<string[]>([])

const rows = ref<AuditRow[]>([])
const totalElements = ref(0)
const loading = ref(false)
const error = ref<string | null>(null)

function toRow(entry: AuditEntryResponse): AuditRow {
  return {
    id: entry.id,
    entityType: entry.entityType,
    entityName: entry.entityName,
    productName: entry.productName,
    action: entry.action,
    createdAt: entry.createdAt,
    createdBy: entry.createdBy,
    newState: entry.newState,
    changes: entry.changes,
    changedFields: entry.changes ? Object.keys(entry.changes).join(', ') : '',
  }
}

async function load() {
  loading.value = true
  error.value = null
  try {
    const result = await auditApi.findAll({
      entityTypes: selectedEntityTypes.value.length > 0 ? selectedEntityTypes.value : undefined,
      actions: selectedActions.value.length > 0 ? selectedActions.value : undefined,
      search: debouncedSearch.value.trim() || undefined,
      sort: sortParam(sortBy.value),
      page: page.value - 1,
      size: itemsPerPage.value,
    })
    rows.value = result.content.map(toRow)
    totalElements.value = result.totalElements
    resolveUserDisplay(rows.value.map((row) => row.createdBy))
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.status}: ${e.message}` : 'Failed to load audit log'
  } finally {
    loading.value = false
  }
}

const auditHeaders: DataTableHeader<AuditRow>[] = [
  // Vuetify's built-in expand toggle — a visible chevron, since expand-on-click alone
  // (a whole row is clickable but looks like plain text) isn't an obvious affordance for
  // a table someone's opening for the first time.
  { title: '', key: 'data-table-expand', width: 40 },
  { title: 'When', key: 'createdAt' },
  { title: 'Type', key: 'entityType' },
  { title: 'Name', key: 'entityName' },
  { title: 'Product', key: 'productName', sortable: false },
  { title: 'Action', key: 'action' },
  { title: 'Changed Fields', key: 'changedFields', sortable: false },
  { title: 'By', key: 'createdBy' },
]

const ACTION_COLORS: Record<AuditAction, string> = {
  CREATED: 'success',
  UPDATED: 'info',
  DELETED: 'error',
}

// Any filter/search change invalidates whatever page of the table was showing.
watch([selectedEntityTypes, selectedActions, debouncedSearch], () => {
  page.value = 1
  load()
})
watch(sortBy, load, { deep: true })
watch([page, itemsPerPage], load)

onMounted(load)
</script>

<template>
  <h1 class="text-h5 mb-4">Audit Log</h1>

  <div class="d-flex flex-wrap ga-2 align-center mb-4">
    <v-select
      v-model="selectedEntityTypes"
      :items="entityTypeOptions"
      label="Filter by type"
      multiple
      chips
      closable-chips
      clearable
      hide-details
      width="320"
      class="flex-grow-0"
    />
    <v-select
      v-model="selectedActions"
      :items="actionOptions"
      label="Filter by action"
      multiple
      chips
      closable-chips
      clearable
      hide-details
      width="320"
      class="flex-grow-0"
    />
    <v-text-field
      v-model="search"
      label="Search name…"
      prepend-inner-icon="mdi-magnify"
      density="compact"
      variant="outlined"
      clearable
      hide-details
      width="280"
    />
  </div>

  <v-card variant="outlined">
    <ResourceTable
      :headers="auditHeaders"
      :items="rows"
      :items-length="totalElements"
      :loading="loading"
      :error="error"
      expandable-rows
      v-model:expanded="expanded"
      v-model:sort-by="sortBy"
      v-model:page="page"
      v-model:items-per-page="itemsPerPage"
    >
      <template #item.createdAt="{ item }">{{ formatDateTime(item.createdAt) }}</template>
      <template #item.action="{ item }">
        <v-chip :color="ACTION_COLORS[item.action]" size="small" variant="flat">{{ item.action }}</v-chip>
      </template>
      <template #item.changedFields="{ item }">
        <span class="cdrm-changed-fields" :title="item.changedFields || undefined">{{ item.changedFields || '—' }}</span>
      </template>
      <template #item.createdBy="{ item }">{{ displayName(item.createdBy) }}</template>
      <template #expanded-row="{ item, columns }">
        <tr>
          <td :colspan="columns.length" class="pa-4 bg-surface-light">
            <div class="text-subtitle-2 mb-2">Changes</div>
            <v-table v-if="item.changes && Object.keys(item.changes).length" density="compact" class="mb-4">
              <thead>
                <tr>
                  <th>Field</th>
                  <th>Old value</th>
                  <th>New value</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="[field, change] in Object.entries(item.changes)" :key="field">
                  <td class="font-weight-medium">{{ field }}</td>
                  <td>{{ formatValue(change.old) }}</td>
                  <td>{{ formatValue(change.new) }}</td>
                </tr>
              </tbody>
            </v-table>
            <span v-else class="text-medium-emphasis d-block mb-4">No field changes recorded.</span>

            <div class="text-subtitle-2 mb-2">Full new state</div>
            <v-table v-if="item.newState" density="compact">
              <tbody>
                <tr v-for="[field, value] in Object.entries(item.newState)" :key="field">
                  <td class="font-weight-medium" style="width: 220px">{{ field }}</td>
                  <td>{{ formatValue(value) }}</td>
                </tr>
              </tbody>
            </v-table>
            <span v-else class="text-medium-emphasis">Entity was deleted — nothing to show.</span>
          </td>
        </tr>
      </template>
    </ResourceTable>
  </v-card>
</template>

<style scoped>
.cdrm-changed-fields {
  display: inline-block;
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}
</style>
