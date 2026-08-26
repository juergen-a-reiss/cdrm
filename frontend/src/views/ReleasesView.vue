<!--
  Required Notice: Copyright Dr. Juergen A. Reiss
  Licensed under the terms in the LICENSE file at the repository root.
-->

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { DataTableHeader } from 'vuetify/lib/components/VDataTable/types.js'
import ResourceTable from '../components/ResourceTable.vue'
import ReleaseFormDialog from '../components/ReleaseFormDialog.vue'
import ProductFilterBar from '../components/ProductFilterBar.vue'
import StageFilterBar from '../components/StageFilterBar.vue'
import WorkloadFilterBar from '../components/WorkloadFilterBar.vue'
import { useResourceList } from '../composables/useResourceList'
import { useProductFilter } from '../composables/useProductFilter'
import { useStageFilter } from '../composables/useStageFilter'
import { useWorkloadFilter } from '../composables/useWorkloadFilter'
import { releasesApi } from '../api/releases'
import { workloadsApi } from '../api/workloads'
import { ApiError } from '../api/http'
import type { ReleaseHistoryEntry, ReleaseResponse } from '../api/types'
import { canManageReleases, canPromoteReleases } from '../auth/roles'
import { formatDateTime } from '../utils/formatDateTime'

interface ReleaseRow {
  id: string
  binaryUrl: string
  description: string | null
  workloadName: string
  currentStageName: string
  lastDeployedAtDisplay: string
  canPromote: boolean
  raw: ReleaseResponse
}

const { items, loading, error, reload } = useResourceList(releasesApi.list)
const { items: workloads } = useResourceList(workloadsApi.list)
const { matches: matchesProduct } = useProductFilter()
const { matches: matchesStage } = useStageFilter()
const { matches: matchesWorkload } = useWorkloadFilter()

const workloadNameById = computed(() => new Map(workloads.value.map((workload) => [workload.id, workload.name])))
const workloadProductIdById = computed(() => new Map(workloads.value.map((workload) => [workload.id, workload.productId])))

const rows = computed<ReleaseRow[]>(() =>
  items.value
    .filter((release) => {
      const productId = workloadProductIdById.value.get(release.workloadId)
      return (
        (productId === undefined || matchesProduct(productId)) &&
        matchesStage(release.currentStage.id) &&
        matchesWorkload(release.workloadId)
      )
    })
    .map((release) => ({
      id: release.id,
      binaryUrl: release.binaryUrl,
      description: release.description,
      workloadName: workloadNameById.value.get(release.workloadId) ?? release.workloadId,
      currentStageName: release.currentStage.name,
      lastDeployedAtDisplay: release.lastDeployedAt ? formatDateTime(release.lastDeployedAt) : 'Pending',
      canPromote: release.canPromote,
      raw: release,
    })),
)

const showActions = computed(() => canManageReleases.value || canPromoteReleases.value)

const headers = computed<DataTableHeader<ReleaseRow>[]>(() => {
  const base: DataTableHeader<ReleaseRow>[] = [
    { title: 'Binary URL', key: 'binaryUrl' },
    { title: 'Workload', key: 'workloadName' },
    { title: 'Current Stage', key: 'currentStageName' },
    { title: 'Last Deployed', key: 'lastDeployedAtDisplay' },
    { title: 'Description', key: 'description' },
  ]
  if (showActions.value) {
    base.push({ title: 'Actions', key: 'actions', sortable: false, width: 160 })
  }
  return base
})

const dialogOpen = ref(false)
const editingRelease = ref<ReleaseResponse | null>(null)
const actionError = ref<string | null>(null)

const expanded = ref<string[]>([])
const historyByRelease = ref<Record<string, ReleaseHistoryEntry[]>>({})
const historyLoading = ref<Record<string, boolean>>({})
const historyError = ref<Record<string, string | null>>({})

watch(expanded, async (ids) => {
  for (const id of ids) {
    if (historyByRelease.value[id] || historyLoading.value[id]) continue
    historyLoading.value[id] = true
    try {
      historyByRelease.value[id] = await releasesApi.history(id)
    } catch (e) {
      historyError.value[id] = e instanceof ApiError ? `${e.status}: ${e.message}` : 'Failed to load history'
    } finally {
      historyLoading.value[id] = false
    }
  }
})

function openCreate() {
  editingRelease.value = null
  dialogOpen.value = true
}

function openEdit(release: ReleaseResponse) {
  editingRelease.value = release
  dialogOpen.value = true
}

async function removeRelease(release: ReleaseResponse) {
  if (!confirm(`Delete release "${release.binaryUrl}"?`)) {
    return
  }
  actionError.value = null
  try {
    await releasesApi.remove(release.id)
    await reload()
  } catch (e) {
    actionError.value = e instanceof ApiError ? `${e.status}: ${e.message}` : 'Failed to delete release'
  }
}

async function promoteRelease(release: ReleaseResponse) {
  actionError.value = null
  try {
    await releasesApi.promote(release.id)
    // Stage may have changed; force the next expand to re-fetch instead of showing stale history.
    delete historyByRelease.value[release.id]
    await reload()
  } catch (e) {
    actionError.value = e instanceof ApiError ? `${e.status}: ${e.message}` : 'Failed to promote release'
  }
}
</script>

<template>
  <v-alert v-if="actionError" type="error" :text="actionError" class="mb-4" />
  <div class="d-flex flex-wrap ga-2">
    <ProductFilterBar />
    <StageFilterBar />
    <WorkloadFilterBar />
  </div>
  <ResourceTable
    :headers="headers"
    :items="rows"
    :loading="loading"
    :error="error"
    expandable-rows
    v-model:expanded="expanded"
  >
    <template v-if="canManageReleases" #top>
      <v-toolbar flat>
        <v-toolbar-title>Releases</v-toolbar-title>
        <v-spacer />
        <v-btn color="primary" prepend-icon="mdi-plus" @click.stop="openCreate">Add release</v-btn>
      </v-toolbar>
    </template>
    <template v-if="showActions" #item.actions="{ item }">
      <v-btn
        v-if="canPromoteReleases"
        icon="mdi-arrow-up-bold-circle-outline"
        size="small"
        variant="text"
        class="mr-2"
        :disabled="!item.canPromote"
        :title="item.canPromote ? 'Promote to next stage' : 'Already at the final stage'"
        @click.stop="promoteRelease(item.raw)"
      />
      <v-icon v-if="canManageReleases" icon="mdi-pencil" size="small" class="mr-2" @click.stop="openEdit(item.raw)" />
      <v-icon v-if="canManageReleases" icon="mdi-delete" size="small" @click.stop="removeRelease(item.raw)" />
    </template>
    <template #expanded-row="{ item, columns }">
      <tr>
        <td :colspan="columns.length" class="pa-4 bg-surface-light">
          <v-alert v-if="historyError[item.id]" type="error" :text="historyError[item.id] ?? undefined" class="mb-2" />
          <v-progress-linear v-if="historyLoading[item.id]" indeterminate class="mb-2" />
          <v-table v-else-if="historyByRelease[item.id]?.length" density="compact">
            <thead>
              <tr>
                <th>Stage</th>
                <th>Promoted</th>
                <th>Deployed</th>
                <th>By</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="entry in historyByRelease[item.id]" :key="entry.id">
                <td>{{ entry.stage.name }}</td>
                <td>{{ formatDateTime(entry.timestamp) }}</td>
                <td>{{ entry.deployedAt ? formatDateTime(entry.deployedAt) : 'Pending' }}</td>
                <td>{{ entry.createdBy }}</td>
              </tr>
            </tbody>
          </v-table>
          <p v-else-if="!historyLoading[item.id]">No history yet.</p>
        </td>
      </tr>
    </template>
  </ResourceTable>

  <ReleaseFormDialog v-model="dialogOpen" :release="editingRelease" @saved="reload" />
</template>
