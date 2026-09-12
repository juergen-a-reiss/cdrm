<!--
  Required Notice: Copyright Dr. Juergen A. Reiss
  Licensed under the terms in the LICENSE file at the repository root.
-->

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError } from '../api/http'
import { workloadsApi } from '../api/workloads'
import type { WorkloadDeploymentOverviewResponse } from '../api/types'
import LiveStatusCell from '../components/LiveStatusCell.vue'
import { formatDeploymentStatus, gitOpsStatusDisplay, kubernetesStatusDisplay } from '../utils/releaseHistoryStatus'

const props = defineProps<{ name: string }>()

const router = useRouter()
const overview = ref<WorkloadDeploymentOverviewResponse | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)

async function load() {
  loading.value = true
  error.value = null
  try {
    // The route only carries the workload's name (see router/index.ts), so resolve it to
    // an id before hitting the UUID-keyed deployment-overview endpoint.
    const workloads = await workloadsApi.list()
    const workload = workloads.find((w) => w.name === props.name)
    if (!workload) {
      error.value = `Workload "${props.name}" not found`
      overview.value = null
      return
    }
    overview.value = await workloadsApi.deploymentOverview(workload.id)
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.status}: ${e.message}` : 'Failed to load workload'
  } finally {
    loading.value = false
  }
}

watch(() => props.name, load, { immediate: true })

// Jumps to the Releases view, pre-filtered to this workload's own head releases —
// without re-applying the same filters by hand there.
function viewReleases() {
  if (!overview.value) return
  router.push({ name: 'releases', query: { workloadId: overview.value.workloadId, headOnly: 'true' } })
}

// Same, further narrowed to only the ones that haven't finished deploying yet — the
// cases interesting enough to want to check on from here specifically.
function viewPendingReleases() {
  if (!overview.value) return
  router.push({
    name: 'releases',
    query: { workloadId: overview.value.workloadId, headOnly: 'true', pendingOnly: 'true' },
  })
}

const headers = [
  { title: 'Stage', key: 'stageName' },
  { title: 'Namespace', key: 'namespace' },
  { title: 'Last release', key: 'lastRelease', sortable: false },
  { title: 'Status', key: 'status', sortable: false },
  { title: 'Live status', key: 'liveStatus', sortable: false },
]
</script>

<template>
  <v-alert v-if="error" type="error" :text="error" class="mb-4" />
  <v-progress-linear v-if="loading" indeterminate class="mb-4" />

  <template v-if="overview">
    <h1 class="text-h5 mb-1">{{ overview.workloadName }}</h1>
    <div class="d-flex align-center flex-wrap ga-4 mb-4">
      <div class="text-body-2 text-medium-emphasis">
        Product: {{ overview.productName }} — Pipeline: {{ overview.pipeline }}
      </div>
      <v-btn variant="tonal" color="primary" size="small" prepend-icon="mdi-tag-outline" @click="viewReleases">
        View releases
      </v-btn>
      <v-btn
        variant="tonal"
        color="primary"
        size="small"
        prepend-icon="mdi-rocket-launch-outline"
        @click="viewPendingReleases"
      >
        View pending releases
      </v-btn>
    </div>

    <div v-if="overview.stages.length === 0" class="text-body-2 text-medium-emphasis">
      This workload is not linked to any stage yet.
    </div>

    <v-data-table v-else :headers="headers" :items="overview.stages" item-value="stageId" density="comfortable">
      <template #item.namespace="{ item }">{{ item.namespace ?? '—' }}</template>
      <template #item.lastRelease="{ item }">
        <div v-if="item.latestRelease">
          <div>{{ item.latestRelease.image }}</div>
          <div class="text-caption text-medium-emphasis">
            {{ item.latestRelease.action }} — {{ formatDeploymentStatus(item.latestRelease) }}
          </div>
        </div>
        <span v-else>—</span>
      </template>
      <template #item.status="{ item }">
        <template v-if="item.latestRelease">
          <v-chip
            v-if="gitOpsStatusDisplay(item.latestRelease.gitOpsStatus)"
            :color="gitOpsStatusDisplay(item.latestRelease.gitOpsStatus)!.color"
            size="small"
            variant="flat"
            class="mr-1"
          >
            {{ gitOpsStatusDisplay(item.latestRelease.gitOpsStatus)!.label }}
          </v-chip>
          <v-chip
            v-if="kubernetesStatusDisplay(item.latestRelease.kubernetesStatus)"
            :color="kubernetesStatusDisplay(item.latestRelease.kubernetesStatus)!.color"
            size="small"
            variant="flat"
          >
            {{ kubernetesStatusDisplay(item.latestRelease.kubernetesStatus)!.label }}
          </v-chip>
        </template>
        <span v-else>—</span>
      </template>
      <template #item.liveStatus="{ item }">
        <LiveStatusCell v-if="overview.kubernetes" :workload-id="overview.workloadId" :stage-id="item.stageId" :active="true" />
        <span v-else>—</span>
      </template>
    </v-data-table>
  </template>
</template>
