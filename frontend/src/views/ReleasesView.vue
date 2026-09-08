<!--
  Required Notice: Copyright Dr. Juergen A. Reiss
  Licensed under the terms in the LICENSE file at the repository root.
-->

<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
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
import { useUserDisplay } from '../composables/useUserDisplay'
import { useChangeReload } from '../composables/useChangeReload'
import { onChange, type ChangeMessage } from '../composables/useChangeSocket'
import { releasesApi } from '../api/releases'
import { workloadsApi } from '../api/workloads'
import { ApiError } from '../api/http'
import type { ReleaseHistoryEntry, ReleaseResponse } from '../api/types'
import { canManageReleases, canPromoteReleases, canRedeployReleases, canRollbackReleases } from '../auth/roles'
import { formatDateTime } from '../utils/formatDateTime'
import { RELEASE_HISTORY_ACTION_LABELS } from '../utils/releaseHistoryAction'
import { formatDeploymentStatus, gitOpsStatusDisplay, kubernetesStatusDisplay } from '../utils/releaseHistoryStatus'
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

const { displayName, resolve: resolveUserDisplay } = useUserDisplay()

const sortBy = usePersistedRef<SortByItem[]>('cdrm.sort.releases', [{ key: 'createdAt', order: 'desc' }])
const { items, loading, error, reload } = useResourceList(() => releasesApi.list(sortParam(sortBy.value)))
watch(sortBy, reload, { deep: true })
const { items: workloads, reload: reloadWorkloads } = useResourceList(workloadsApi.list)
// Only the name/product/pipeline lookups below depend on this list — a renamed
// workload (or one reassigned to a different product/pipeline) should show up here too,
// not just on the Workloads tab itself.
useChangeReload('dev.juergenreiss.cdrm.workload.', reloadWorkloads)
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
    resolveUserDisplay(historyByRelease.value[id].map((entry) => entry.createdBy))
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

// A release's canPromote/deployment status can change without any action of the
// viewer's own — DeploymentVerificationJob confirms a Kubernetes rollout (or
// DeploymentSchedulerJob retries a pending one) independently, seconds to minutes after
// the request that triggered it returned. Rather than polling for that, the backend
// pushes a "release X changed" signal over the WebSocket the moment it happens (see
// useChangeSocket) — this is the "backend actively pushes" half; the rest of this
// handler is the "frontend just patches its own data model" half your view's sorting
// and filtering already happen client-side against the one (unpaginated) list already
// in memory, so there's no need to refetch the whole thing, only the one row that
// changed.
const RELEASE_HISTORY_CHANGE_TYPE_PREFIX = 'dev.juergenreiss.cdrm.release-history.'

async function handleChange(message: ChangeMessage) {
  if (!message.type.startsWith(RELEASE_HISTORY_CHANGE_TYPE_PREFIX)) return
  const releaseId = message.subject
  const index = items.value.findIndex((release) => release.id === releaseId)
  if (index === -1) {
    // Not currently in the list — a brand new release, or one just becoming visible to
    // this caller. Patching one item in place doesn't help here; the (unpaginated)
    // list itself needs refetching.
    await reload()
  } else {
    try {
      items.value[index] = await releasesApi.get(releaseId)
    } catch {
      // Most likely just deleted, or no longer visible to this caller — drop the stale
      // row via a full reload rather than leaving it showing outdated data.
      await reload()
    }
  }
  if (expanded.value.includes(releaseId)) await loadHistory(releaseId)
}

const unsubscribeChanges = onChange(handleChange)
onUnmounted(unsubscribeChanges)

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

// Only ever called for a row where raw.hasNextStage is true — see the promote button's
// v-if below, which hides it entirely otherwise (a final-stage release has nothing left
// to explain: there's no "reason" to show, so no disabled button either).
function promoteTitle(row: ReleaseRow): string {
  if (row.canPromote) return 'Promote to next stage'
  if (row.raw.deploymentFailed) return `Deployment to this stage failed${row.raw.deploymentError ? ` (${row.raw.deploymentError})` : ''}`
  if (!row.raw.deploymentFinished) return "Deployment to this stage hasn't finished yet"
  return 'You do not have permission to promote at this stage'
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
    <template #item.image="{ item }">
      <span :title="item.raw.commitId ? `Commit: ${item.raw.commitId}` : undefined">{{ item.image }}</span>
    </template>
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
        <v-icon
          v-if="item.raw.deploymentFailed"
          icon="mdi-alert-circle"
          size="small"
          color="error"
          :title="`Deployment to this stage failed${item.raw.deploymentError ? `: ${item.raw.deploymentError}` : ''}`"
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
      <!-- Each action gets its own fixed-width slot, always rendered, with the v-if on
           the v-btn *inside* it rather than on the slot itself — so an action that has
           nothing to do here (no next stage to promote to, or this role/row has no
           permission for it at all) leaves an empty gap instead of shifting every action
           after it sideways. Each button's tooltip is on the slot rather than the v-btn:
           a native `title` attribute never shows on a *disabled* element (Chrome/Firefox
           don't fire hover events on disabled form controls), so a title on the button
           itself is silently swallowed exactly when it's most needed — explaining why
           the disabled action is disabled. No tooltip at all when the button itself is
           absent: there's nothing to explain about a structurally-impossible action. -->
      <span class="cdrm-action-slot" :title="item.raw.hasNextStage ? promoteTitle(item) : undefined">
        <v-btn
          v-if="item.raw.hasNextStage"
          icon="mdi-arrow-up-bold-circle-outline"
          size="small"
          variant="text"
          :disabled="!(canPromoteReleases || item.canPromote)"
          @click.stop="promoteRelease(item.raw)"
        />
      </span>
      <span
        class="cdrm-action-slot"
        :title="
          canRollbackReleases || item.canRollback
            ? item.canRollback
              ? 'Roll back stage to this release'
              : 'Not allowed, or already the head release for this stage'
            : undefined
        "
      >
        <v-btn
          v-if="canRollbackReleases || item.canRollback"
          icon="mdi-history"
          size="small"
          variant="text"
          :disabled="!item.canRollback"
          @click.stop="rollbackRelease(item.raw)"
        />
      </span>
      <span class="cdrm-action-slot" :title="canRedeployReleases || item.canRedeploy ? redeployTitle(item) : undefined">
        <v-btn
          v-if="canRedeployReleases || item.canRedeploy"
          icon="mdi-cloud-upload-outline"
          size="small"
          variant="text"
          :disabled="!item.canRedeploy"
          @click.stop="openRedeploy(item.raw)"
        />
      </span>
      <span
        class="cdrm-action-slot"
        :title="canManageReleases || item.canEdit ? (item.canEdit ? 'Edit release' : 'Not allowed to edit at this stage') : undefined"
      >
        <v-btn
          v-if="canManageReleases || item.canEdit"
          icon="mdi-pencil"
          size="small"
          variant="text"
          :disabled="!item.canEdit"
          @click.stop="openEdit(item.raw)"
        />
      </span>
      <span
        class="cdrm-action-slot"
        :title="canManageReleases || item.canDelete ? (item.canDelete ? 'Delete release' : 'Not allowed to delete at this stage') : undefined"
      >
        <v-btn
          v-if="canManageReleases || item.canDelete"
          icon="mdi-delete"
          size="small"
          variant="text"
          :disabled="!item.canDelete"
          @click.stop="removeRelease(item.raw)"
        />
      </span>
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
                <th>GitOps</th>
                <th>Kubernetes</th>
                <th>By</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="entry in historyByRelease[item.id]" :key="entry.id">
                <td>{{ entry.stage.name }}</td>
                <td>{{ RELEASE_HISTORY_ACTION_LABELS[entry.action] }}</td>
                <td>{{ formatDateTime(entry.timestamp) }}</td>
                <td>{{ formatDeploymentStatus(entry) }}</td>
                <td>
                  <v-chip
                    v-if="gitOpsStatusDisplay(entry.gitOpsStatus)"
                    :color="gitOpsStatusDisplay(entry.gitOpsStatus)!.color"
                    size="small"
                    variant="flat"
                  >
                    {{ gitOpsStatusDisplay(entry.gitOpsStatus)!.label }}
                  </v-chip>
                </td>
                <td>
                  <v-chip
                    v-if="kubernetesStatusDisplay(entry.kubernetesStatus)"
                    :color="kubernetesStatusDisplay(entry.kubernetesStatus)!.color"
                    size="small"
                    variant="flat"
                  >
                    {{ kubernetesStatusDisplay(entry.kubernetesStatus)!.label }}
                  </v-chip>
                </td>
                <td>{{ displayName(entry.createdBy) }}</td>
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

<style scoped>
/* Fixed-width slot per action icon (see the actions column's comment above) — a
   Vuetify "small" icon button is ~32px square; a couple more px keeps the same visual
   gap the old mr-2 (8px) gave between icons, without the gap collapsing when the icon
   inside a slot is absent. */
.cdrm-action-slot {
  display: inline-block;
  width: 40px;
  text-align: center;
  vertical-align: middle;
}
</style>
