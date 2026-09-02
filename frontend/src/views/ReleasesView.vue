<!--
  Required Notice: Copyright Dr. Juergen A. Reiss
  Licensed under the terms in the LICENSE file at the repository root.
-->

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { DataTableHeader } from 'vuetify/lib/components/VDataTable/types.js'
import ResourceTable from '../components/ResourceTable.vue'
import type { SortByItem } from '../components/ResourceTable.vue'
import ReleaseFormDialog from '../components/ReleaseFormDialog.vue'
import ReleaseRedeployDialog from '../components/ReleaseRedeployDialog.vue'
import ProductFilterBar from '../components/ProductFilterBar.vue'
import StageFilterBar from '../components/StageFilterBar.vue'
import WorkloadFilterBar from '../components/WorkloadFilterBar.vue'
import PipelineFilterBar from '../components/PipelineFilterBar.vue'
import { useResourceList } from '../composables/useResourceList'
import { useProductFilter } from '../composables/useProductFilter'
import { useStageFilter } from '../composables/useStageFilter'
import { useWorkloadFilter } from '../composables/useWorkloadFilter'
import { usePipelineFilter } from '../composables/usePipelineFilter'
import { usePersistedRef } from '../composables/usePersistedRef'
import { useToast } from '../composables/useToast'
import { releasesApi } from '../api/releases'
import { workloadsApi } from '../api/workloads'
import { ApiError } from '../api/http'
import type { ReleaseHistoryEntry, ReleaseResponse } from '../api/types'
import { canManageReleases, canPromoteReleases, canRedeployReleases, canRollbackReleases } from '../auth/roles'
import { formatDateTime } from '../utils/formatDateTime'
import { RELEASE_HISTORY_ACTION_LABELS } from '../utils/releaseHistoryAction'
import { formatDeploymentStatus } from '../utils/releaseHistoryStatus'
import { sortParam } from '../utils/sortParam'

interface ReleaseRow {
  id: string
  image: string
  description: string | null
  workloadName: string
  currentStageName: string
  isHead: boolean
  // Raw ISO timestamp (or null) so the "Last Deployed" column sorts chronologically —
  // the header renders it via a `value` function (see headers below), not this field
  // directly.
  lastDeployedAt: string | null
  canPromote: boolean
  canRollback: boolean
  canRedeploy: boolean
  canEdit: boolean
  canDelete: boolean
  raw: ReleaseResponse
}

const sortBy = usePersistedRef<SortByItem[]>('cdrm.sort.releases', [{ key: 'createdAt', order: 'desc' }])
const { items, loading, error, reload } = useResourceList(() => releasesApi.list(sortParam(sortBy.value)))
watch(sortBy, reload, { deep: true })
const { items: workloads } = useResourceList(workloadsApi.list)
const { matches: matchesProduct } = useProductFilter()
const { matches: matchesStage } = useStageFilter()
const { matches: matchesWorkload } = useWorkloadFilter()
const { matches: matchesPipeline } = usePipelineFilter()
const { showToast } = useToast()
const headOnly = ref(false)

const workloadNameById = computed(() => new Map(workloads.value.map((workload) => [workload.id, workload.name])))
const workloadProductIdById = computed(() => new Map(workloads.value.map((workload) => [workload.id, workload.productId])))
const workloadPipelineById = computed(() => new Map(workloads.value.map((workload) => [workload.id, workload.pipeline])))

const rows = computed<ReleaseRow[]>(() =>
  items.value
    .filter((release) => {
      const productId = workloadProductIdById.value.get(release.workloadId)
      const pipeline = workloadPipelineById.value.get(release.workloadId)
      return (
        (productId === undefined || matchesProduct(productId)) &&
        matchesStage(release.currentStage.id) &&
        matchesWorkload(release.workloadId) &&
        (pipeline === undefined || matchesPipeline(pipeline)) &&
        (!headOnly.value || !release.canRollback)
      )
    })
    .map((release) => ({
      id: release.id,
      image: release.image,
      description: release.description,
      workloadName: workloadNameById.value.get(release.workloadId) ?? release.workloadId,
      currentStageName: release.currentStage.name,
      isHead: !release.canRollback,
      lastDeployedAt: release.lastDeployedAt,
      canPromote: release.canPromote,
      canRollback: release.canRollback,
      canRedeploy: release.redeployableStages.length > 0,
      canEdit: release.canEdit,
      canDelete: release.canDelete,
      raw: release,
    })),
)

// The static role checks decide whether a caller can act on releases in general; a
// caller's cdrm-release-actions claim (see backend RebacContext) can additionally grant
// a specific row's action to a role with no baseline permission (e.g. cdrm-manager) —
// so the column, and each icon within it, also shows when any row's own computed flag
// says so, even without the matching role.
const showActions = computed(
  () =>
    canManageReleases.value ||
    canPromoteReleases.value ||
    canRollbackReleases.value ||
    canRedeployReleases.value ||
    rows.value.some((row) => row.canPromote || row.canRollback || row.canRedeploy || row.canEdit || row.canDelete),
)

const headers = computed<DataTableHeader<ReleaseRow>[]>(() => {
  const base: DataTableHeader<ReleaseRow>[] = [
    { title: 'Image', key: 'image' },
    { title: 'Workload', key: 'workloadName' },
    { title: 'Current Stage', key: 'currentStageName' },
    { title: 'Last Deployed', key: 'lastDeployedAt', value: (item) => (item.lastDeployedAt ? formatDateTime(item.lastDeployedAt) : 'Pending') },
    { title: 'Description', key: 'description' },
  ]
  if (showActions.value) {
    base.push({ title: 'Actions', key: 'actions', sortable: false, width: 240 })
  }
  return base
})

const dialogOpen = ref(false)
const editingRelease = ref<ReleaseResponse | null>(null)
const redeployDialogOpen = ref(false)
const redeployingRelease = ref<ReleaseResponse | null>(null)
const actionError = ref<string | null>(null)

const expanded = ref<string[]>([])
const historyByRelease = ref<Record<string, ReleaseHistoryEntry[]>>({})
const historyLoading = ref<Record<string, boolean>>({})
const historyError = ref<Record<string, string | null>>({})

// A row's history can go stale without any action of the viewer's own — the background
// scheduler job deploys pending entries independently — so every expand re-fetches
// instead of trusting a previous fetch's cache.
async function loadHistory(id: string) {
  if (historyLoading.value[id]) return
  historyLoading.value[id] = true
  historyError.value[id] = null
  try {
    historyByRelease.value[id] = await releasesApi.history(id)
  } catch (e) {
    historyError.value[id] = e instanceof ApiError ? `${e.status}: ${e.message}` : 'Failed to load history'
  } finally {
    historyLoading.value[id] = false
  }
}

watch(expanded, async (ids) => {
  for (const id of ids) {
    await loadHistory(id)
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
  if (!confirm(`Delete release "${release.image}"?`)) {
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
    const result = await releasesApi.promote(release.id)
    if (result.deployError) {
      showToast(`Deploy failed (${result.deployError}) — it will be retried automatically once the cluster is reachable again.`)
    }
    // A new history entry was recorded; refresh it now if it's currently visible.
    if (expanded.value.includes(release.id)) await loadHistory(release.id)
    await reload()
  } catch (e) {
    actionError.value = e instanceof ApiError ? `${e.status}: ${e.message}` : 'Failed to promote release'
  }
}

async function rollbackRelease(release: ReleaseResponse) {
  if (!confirm(`Roll back stage "${release.currentStage.name}" to this release?`)) {
    return
  }
  actionError.value = null
  try {
    await releasesApi.rollback(release.id)
    // A new history entry was recorded; refresh it now if it's currently visible.
    if (expanded.value.includes(release.id)) await loadHistory(release.id)
    await reload()
  } catch (e) {
    actionError.value = e instanceof ApiError ? `${e.status}: ${e.message}` : 'Failed to roll back release'
  }
}

function redeployTitle(row: ReleaseRow): string {
  if (!row.canRedeploy) return 'No eligible target stage'
  return row.isHead ? 'Redeploy to the current or an earlier stage' : 'Redeploy to an earlier stage'
}

function openRedeploy(release: ReleaseResponse) {
  redeployingRelease.value = release
  redeployDialogOpen.value = true
}

async function onRedeployed() {
  // A new history entry was recorded; refresh it now if it's currently visible.
  if (redeployingRelease.value && expanded.value.includes(redeployingRelease.value.id)) {
    await loadHistory(redeployingRelease.value.id)
  }
  await reload()
}
</script>

<template>
  <v-alert v-if="actionError" type="error" :text="actionError" class="mb-4" />
  <div class="d-flex flex-wrap ga-2 align-center mb-4">
    <PipelineFilterBar />
    <ProductFilterBar />
    <StageFilterBar />
    <WorkloadFilterBar />
    <v-checkbox
      v-model="headOnly"
      label="Head releases only"
      :color="headOnly ? 'primary' : undefined"
      density="compact"
      hide-details
      class="flex-grow-0"
    />
  </div>
  <ResourceTable
    :headers="headers"
    :items="rows"
    :loading="loading"
    :error="error"
    expandable-rows
    v-model:expanded="expanded"
    v-model:sort-by="sortBy"
  >
    <template #item.currentStageName="{ item }">
      <span class="d-flex align-center ga-1">
        {{ item.currentStageName }}
        <v-icon
          v-if="item.isHead"
          icon="mdi-crown"
          size="small"
          color="amber-darken-2"
          title="Head release for this stage"
        />
      </span>
    </template>
    <template v-if="canManageReleases" #top>
      <v-toolbar flat>
        <v-toolbar-title>Releases</v-toolbar-title>
        <v-spacer />
        <v-btn color="primary" prepend-icon="mdi-plus" @click.stop="openCreate">Add release</v-btn>
      </v-toolbar>
    </template>
    <template v-if="showActions" #item.actions="{ item }">
      <v-btn
        v-if="canPromoteReleases || item.canPromote"
        icon="mdi-arrow-up-bold-circle-outline"
        size="small"
        variant="text"
        class="mr-2"
        :disabled="!item.canPromote"
        :title="item.canPromote ? 'Promote to next stage' : 'Not allowed, or already at the final stage'"
        @click.stop="promoteRelease(item.raw)"
      />
      <v-btn
        v-if="canRollbackReleases || item.canRollback"
        icon="mdi-history"
        size="small"
        variant="text"
        class="mr-2"
        :disabled="!item.canRollback"
        :title="item.canRollback ? 'Roll back stage to this release' : 'Not allowed, or already the head release for this stage'"
        @click.stop="rollbackRelease(item.raw)"
      />
      <v-btn
        v-if="canRedeployReleases || item.canRedeploy"
        icon="mdi-cloud-upload-outline"
        size="small"
        variant="text"
        class="mr-2"
        :disabled="!item.canRedeploy"
        :title="redeployTitle(item)"
        @click.stop="openRedeploy(item.raw)"
      />
      <v-btn
        v-if="canManageReleases || item.canEdit"
        icon="mdi-pencil"
        size="small"
        variant="text"
        class="mr-2"
        :disabled="!item.canEdit"
        :title="item.canEdit ? 'Edit release' : 'Not allowed to edit at this stage'"
        @click.stop="openEdit(item.raw)"
      />
      <v-btn
        v-if="canManageReleases || item.canDelete"
        icon="mdi-delete"
        size="small"
        variant="text"
        :disabled="!item.canDelete"
        :title="item.canDelete ? 'Delete release' : 'Not allowed to delete at this stage'"
        @click.stop="removeRelease(item.raw)"
      />
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
                <th>Action</th>
                <th>Promoted</th>
                <th>Deployed</th>
                <th>By</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="entry in historyByRelease[item.id]" :key="entry.id">
                <td>{{ entry.stage.name }}</td>
                <td>{{ RELEASE_HISTORY_ACTION_LABELS[entry.action] }}</td>
                <td>{{ formatDateTime(entry.timestamp) }}</td>
                <td>{{ formatDeploymentStatus(entry) }}</td>
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
  <ReleaseRedeployDialog v-model="redeployDialogOpen" :release="redeployingRelease" @saved="onRedeployed" />
</template>
