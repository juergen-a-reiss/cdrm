<!--
  Required Notice: Copyright Dr. Juergen A. Reiss
  Licensed under the terms in the LICENSE file at the repository root.
-->

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ApiError } from '../api/http'
import { clustersApi } from '../api/clusters'
import { gitOpsApi } from '../api/gitops'
import { stagesApi } from '../api/stages'
import { workloadsApi } from '../api/workloads'
import type { ClusterResponse, ClusterType, GitOpsNamespaceMode, K8sGitopsConfig, StageResponse, WorkloadResponse } from '../api/types'

const props = defineProps<{
  modelValue: boolean
  cluster: ClusterResponse | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: []
}>()

const typeOptions: { title: string; value: ClusterType }[] = [
  { title: 'Kubernetes', value: 'K8S' },
  { title: 'Proxmox', value: 'PROXMOX' },
]

interface NamespaceRow {
  namespace: string
  gitOps: boolean
  mode: GitOpsNamespaceMode
  fileExpression: string
  yamlExpression: string
  // Empty means "use the cluster-wide branch" (gitBranch below).
  gitBranch: string
  // Empty means "use the cluster-wide repo" (gitRepo below).
  gitRepo: string
  // Only used (and required) when mode is 'TEMPLATE'.
  templateScript: string
}

const modeOptions: { title: string; value: GitOpsNamespaceMode }[] = [
  { title: 'Simple (file + YAML key)', value: 'SIMPLE' },
  { title: 'Advanced (JS template)', value: 'TEMPLATE' },
]

// Local, per-browser convenience only, keyed by cluster id — remembers which namespace
// was last selected, and which workload/stage/release-binary was last tried in the Test
// dialog for each namespace, so reopening this dialog (or Test within it) for the same
// cluster starts back where the user left off instead of blank every time. Never sent to
// the backend. A brand new, not-yet-saved cluster has no id yet, so nothing is
// remembered for it until after the first save.
const STORAGE_KEY = 'cdrm.clusterFormDialog.v1'

interface StoredTestSelection {
  workloadId: string
  stageId: string
  releaseBinary: string
}

interface StoredClusterState {
  lastNamespace?: string
  testSelections?: Record<string, StoredTestSelection>
}

function loadAllStoredState(): Record<string, StoredClusterState> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? JSON.parse(raw) : {}
  } catch {
    return {}
  }
}

function getStoredState(clusterId: string | null | undefined): StoredClusterState {
  if (!clusterId) return {}
  return loadAllStoredState()[clusterId] ?? {}
}

function updateStoredState(clusterId: string | null | undefined, patch: Partial<StoredClusterState>) {
  if (!clusterId) return
  try {
    const all = loadAllStoredState()
    all[clusterId] = { ...all[clusterId], ...patch }
    localStorage.setItem(STORAGE_KEY, JSON.stringify(all))
  } catch {
    // Best-effort only (private browsing, full storage quota, ...) — this convenience
    // silently doesn't persist, nothing else depends on it.
  }
}

const name = ref('')
const description = ref('')
const clusterType = ref<ClusterType>('K8S')
const url = ref('')
const namespaceRows = ref<NamespaceRow[]>([])
const gitOpsEnabled = ref(false)
const gitRepo = ref('')
const gitBranch = ref('main')
// The only git repos cdrm is configured to authenticate with (see GitOpsProperties on
// the backend) — a plain text field here could reference a repo cdrm has no credentials
// for at all, so both this and each namespace's own override below are a dropdown over
// this list rather than free text.
const repositoryOptions = ref<string[]>([])
onMounted(async () => {
  try {
    repositoryOptions.value = await gitOpsApi.repositories()
  } catch {
    // Leave it empty — the selects below just show no options; save() still validates
    // a value was picked for a GitOps-managed cluster/namespace.
  }
})

// Keeps an already-saved value selectable even if it's no longer in
// repositoryOptions (a repo removed from cdrm.gitops.repositories after a cluster/
// namespace already referenced it) — otherwise the select would silently show empty
// for an existing cluster instead of the value it actually still has.
function selectableOptions(current: string): string[] {
  return current && !repositoryOptions.value.includes(current) ? [...repositoryOptions.value, current] : repositoryOptions.value
}
// Which namespace's GitOps settings are currently shown below the picker — editing one
// namespace at a time instead of expanding every row inline is what keeps this usable
// once a cluster has more than a handful of namespaces.
const selectedNamespaceIndex = ref<number | null>(null)
const saving = ref(false)
const error = ref<string | null>(null)

const selectedRow = computed<NamespaceRow | null>(() =>
  selectedNamespaceIndex.value !== null ? (namespaceRows.value[selectedNamespaceIndex.value] ?? null) : null,
)
const namespaceSelectItems = computed(() =>
  namespaceRows.value.map((row, index) => ({ title: row.namespace || '(unnamed)', value: index })),
)

function sortRowsAscending(rows: NamespaceRow[]): NamespaceRow[] {
  return [...rows].sort((a, b) => a.namespace.localeCompare(b.namespace))
}

function parseNamespaceRows(k8sNamespaces: string | null, gitOpsConfig: K8sGitopsConfig | null): NamespaceRow[] {
  const namespaces = (k8sNamespaces ?? '')
    .split(',')
    .map((ns) => ns.trim())
    .filter((ns) => ns.length > 0)
  return sortRowsAscending(
    namespaces.map((namespace) => {
      const g = gitOpsConfig?.namespaces[namespace]
      return {
        namespace,
        gitOps: g?.useGitOps ?? false,
        mode: g?.mode ?? 'SIMPLE',
        fileExpression: g?.fileExpression ?? '',
        yamlExpression: g?.yamlExpression ?? '',
        gitBranch: g?.gitBranch ?? '',
        gitRepo: g?.gitRepo ?? '',
        templateScript: g?.templateScript ?? '',
      }
    }),
  )
}

watch(
  () => [props.modelValue, props.cluster] as const,
  ([open, cluster]) => {
    if (open) {
      name.value = cluster?.name ?? ''
      description.value = cluster?.description ?? ''
      clusterType.value = cluster?.clusterType ?? 'K8S'
      url.value = cluster?.url ?? ''
      namespaceRows.value = parseNamespaceRows(cluster?.k8sNamespaces ?? null, cluster?.k8sGitOpsConfig ?? null)
      gitOpsEnabled.value = cluster?.k8sGitOpsConfig?.useGitOps ?? false
      gitRepo.value = cluster?.k8sGitOpsConfig?.gitRepo ?? ''
      gitBranch.value = cluster?.k8sGitOpsConfig?.gitBranch ?? 'main'
      const lastNamespace = getStoredState(cluster?.id).lastNamespace
      const restoredIndex = lastNamespace ? namespaceRows.value.findIndex((row) => row.namespace === lastNamespace) : -1
      selectedNamespaceIndex.value = restoredIndex >= 0 ? restoredIndex : null
      error.value = null
    }
  },
  { immediate: true },
)

// Persists whenever the user picks a (named) namespace — restored above next time this
// same cluster is opened.
watch(selectedNamespaceIndex, (index) => {
  const namespace = index !== null ? namespaceRows.value[index]?.namespace : null
  if (namespace) {
    updateStoredState(props.cluster?.id, { lastNamespace: namespace })
  }
})

watch(clusterType, (type) => {
  if (type !== 'K8S') {
    namespaceRows.value = []
    gitOpsEnabled.value = false
    gitRepo.value = ''
    gitBranch.value = 'main'
    selectedNamespaceIndex.value = null
  }
})

function addNamespace() {
  namespaceRows.value.push({
    namespace: '',
    gitOps: false,
    mode: 'SIMPLE',
    fileExpression: '',
    yamlExpression: '',
    gitBranch: '',
    gitRepo: '',
    templateScript: '',
  })
  selectedNamespaceIndex.value = namespaceRows.value.length - 1
}

function removeNamespace(index: number) {
  namespaceRows.value.splice(index, 1)
  if (selectedNamespaceIndex.value === index) {
    selectedNamespaceIndex.value = null
  } else if (selectedNamespaceIndex.value !== null && selectedNamespaceIndex.value > index) {
    selectedNamespaceIndex.value -= 1
  }
}

function close() {
  emit('update:modelValue', false)
}

async function save() {
  if (gitOpsEnabled.value && !gitRepo.value.trim()) {
    error.value = 'Git repository is required when managed by GitOps'
    return
  }
  if (gitOpsEnabled.value && !gitBranch.value.trim()) {
    error.value = 'Git branch is required when managed by GitOps'
    return
  }
  const templateRowMissingScript = namespaceRows.value.find(
    (row) => gitOpsEnabled.value && row.gitOps && row.mode === 'TEMPLATE' && !row.templateScript.trim(),
  )
  if (templateRowMissingScript) {
    error.value = `Template script is required for namespace '${templateRowMissingScript.namespace || '(unnamed)'}'`
    return
  }
  saving.value = true
  error.value = null
  try {
    const rows = sortRowsAscending(
      namespaceRows.value.map((row) => ({ ...row, namespace: row.namespace.trim() })).filter((row) => row.namespace.length > 0),
    )
    const k8sGitOpsConfig: K8sGitopsConfig | null = gitOpsEnabled.value
      ? {
          useGitOps: true,
          gitRepo: gitRepo.value.trim(),
          gitBranch: gitBranch.value.trim(),
          namespaces: Object.fromEntries(
            rows
              .filter((row) => row.gitOps)
              .map((row) => [
                row.namespace,
                {
                  namespace: row.namespace,
                  useGitOps: true,
                  mode: row.mode,
                  fileExpression: row.fileExpression.trim() || null,
                  yamlExpression: row.yamlExpression.trim() || null,
                  gitBranch: row.gitBranch.trim() || null,
                  gitRepo: row.gitRepo.trim() || null,
                  templateScript: row.templateScript.trim() || null,
                },
              ]),
          ),
        }
      : null
    const request = {
      name: name.value,
      description: description.value || null,
      clusterType: clusterType.value,
      url: url.value,
      k8sNamespaces: rows.length > 0 ? rows.map((row) => row.namespace).join(',') : null,
      k8sGitOpsConfig,
    }
    if (props.cluster) {
      await clustersApi.update(props.cluster.id, request)
    } else {
      await clustersApi.create(request)
    }
    emit('saved')
    close()
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.status}: ${e.message}` : 'Failed to save cluster'
  } finally {
    saving.value = false
  }
}

// "Test" runs a TEMPLATE-mode namespace's script server-side without saving it first —
// selectedRow.templateScript below is the same ref the editor textarea is bound to, so
// this always uses whatever is currently typed, not what's saved on the cluster. A
// (workload, stage) pair stands in for a real deploy, supplying the rest of the context
// variables (product/namespace/stage name) the script can reference.
const testDialogOpen = ref(false)
const testWorkloads = ref<WorkloadResponse[]>([])
const testStages = ref<StageResponse[]>([])
const testOptionsLoading = ref(false)
const testWorkloadId = ref<string | null>(null)
const testStageId = ref<string | null>(null)
const testReleaseBinary = ref('test:latest')
const testRunning = ref(false)
const testError = ref<string | null>(null)
const testResult = ref<string | null>(null)

// A namespace's own stage (and pipeline) isn't stored directly — it's implied by two
// things: which stages are actually linked to this cluster (a namespace only means
// anything in the context of the cluster it's configured on — StageResponse.clusters),
// and, among those, which stage's namespacePrefix the namespace string starts with
// (namespace = stage.namespacePrefix + workload.kubernetesNameSpace, see
// GitOpsResolver.findNamespace on the backend). Longest matching prefix wins (e.g.
// "paris-prod-" over a shorter/blank one) and ties are all kept, though in practice a
// pipeline's stage prefixes don't collide — so on a cluster shared by several stages
// (e.g. one "minikube" cluster hosting every stage) this resolves to exactly the one
// stage that could ever deploy to this namespace, not every stage sharing its pipeline
// (paris-qa and paris-production share the "paris" pipeline, but only
// paris-production's prefix actually matches "paris-prod-website").
//
// The cluster-linkage filter also covers the opposite setup — one dedicated cluster per
// stage, so namespacePrefix is never set at all (every namespace is just the bare
// workload namespace, identical across stages). There, every stage's prefix is blank and
// trivially "matches" any namespace, so prefix alone can't tell them apart — but at most
// one of them is ever linked to *this* cluster, and that's the one that resolves.
const testMatchingStages = computed<StageResponse[]>(() => {
  const namespace = selectedRow.value?.namespace
  if (!namespace) return []
  const clusterId = props.cluster?.id
  const candidates = clusterId ? testStages.value.filter((s) => s.clusters.some((c) => c.id === clusterId)) : testStages.value
  let bestLength = -1
  let matches: StageResponse[] = []
  for (const stage of candidates) {
    const prefix = stage.namespacePrefix ?? ''
    if (!namespace.startsWith(prefix)) continue
    if (prefix.length > bestLength) {
      bestLength = prefix.length
      matches = [stage]
    } else if (prefix.length === bestLength) {
      matches.push(stage)
    }
  }
  return matches
})
const testPipeline = computed<string | null>(() => testMatchingStages.value[0]?.pipeline ?? null)
const testStageOptions = computed(() => testMatchingStages.value.map((s) => ({ title: s.name, value: s.id })))

// Only once a stage is picked do we know this namespace's exact prefix, and so which
// workload(s) — its own kubernetesNameSpace, prefixed by that stage's namespacePrefix —
// would actually reconstruct this exact namespace (same formula as above). Pipeline
// alone isn't enough to narrow this down: two products in the same pipeline can use
// different base Kubernetes namespaces.
const testWorkloadOptions = computed(() => {
  const namespace = selectedRow.value?.namespace
  const stage = testMatchingStages.value.find((s) => s.id === testStageId.value)
  if (!namespace || !stage) return []
  const baseNamespace = namespace.slice((stage.namespacePrefix ?? '').length)
  return testWorkloads.value
    .filter((w) => w.pipeline === stage.pipeline && w.kubernetesNameSpace === baseNamespace)
    .map((w) => ({ title: w.name, value: w.id }))
})
// Same precedence GitOpsResolver.resolve() uses on the backend: a namespace's own repo
// override, falling back to the cluster-wide default.
const effectiveRepositoryUrl = computed(() => (selectedRow.value?.gitRepo || gitRepo.value).trim())

// A manual stage change (the rare case where more than one stage ties for the longest
// prefix match) can leave a previously chosen workload no longer valid for the new
// stage — drop it rather than silently keep testing against the wrong namespace.
watch(testStageId, () => {
  if (!testWorkloadOptions.value.some((o) => o.value === testWorkloadId.value)) {
    testWorkloadId.value = null
  }
})

async function openTestDialog() {
  testDialogOpen.value = true
  testError.value = null
  testResult.value = null
  if (testWorkloads.value.length === 0 || testStages.value.length === 0) {
    testOptionsLoading.value = true
    try {
      const [workloads, stages] = await Promise.all([workloadsApi.list(), stagesApi.list()])
      testWorkloads.value = workloads
      testStages.value = stages
    } catch {
      testError.value = 'Failed to load workloads/stages'
    } finally {
      testOptionsLoading.value = false
    }
  }
  restoreTestSelection()
}

// Remembers the last workload/stage/release-binary tried per namespace (see
// STORAGE_KEY above) — reopening Test for the same namespace starts back where the user
// left off instead of blank selects every time. A stored id no longer valid (data
// changed, or the namespace only ever had one real option anyway) falls back to the
// single stage that actually matches this namespace, if there is exactly one.
function restoreTestSelection() {
  const namespace = selectedRow.value?.namespace
  const stored = namespace ? getStoredState(props.cluster?.id).testSelections?.[namespace] : undefined

  const validStage = testMatchingStages.value.find((s) => s.id === stored?.stageId)
  testStageId.value = validStage?.id ?? (testMatchingStages.value.length === 1 ? testMatchingStages.value[0].id : null)

  const validWorkload = testWorkloadOptions.value.find((o) => o.value === stored?.workloadId)
  testWorkloadId.value = validWorkload?.value ?? null

  if (stored?.releaseBinary) testReleaseBinary.value = stored.releaseBinary
}

watch([testWorkloadId, testStageId, testReleaseBinary], ([workloadId, stageId, releaseBinary]) => {
  const namespace = selectedRow.value?.namespace
  if (!namespace || !workloadId || !stageId) return
  const state = getStoredState(props.cluster?.id)
  updateStoredState(props.cluster?.id, {
    testSelections: { ...state.testSelections, [namespace]: { workloadId, stageId, releaseBinary } },
  })
})

async function runTest() {
  if (!selectedRow.value) return
  if (!testWorkloadId.value || !testStageId.value) {
    testError.value = 'Select a workload and a stage'
    return
  }
  if (!effectiveRepositoryUrl.value) {
    testError.value = 'No git repository configured — set one above first'
    return
  }
  testRunning.value = true
  testError.value = null
  testResult.value = null
  try {
    const response = await gitOpsApi.testTemplate({
      script: selectedRow.value.templateScript,
      repositoryUrl: effectiveRepositoryUrl.value,
      clusterName: name.value,
      workloadId: testWorkloadId.value,
      stageId: testStageId.value,
      releaseBinary: testReleaseBinary.value,
    })
    testResult.value = JSON.stringify(response, null, 2)
  } catch (e) {
    testError.value = e instanceof ApiError ? `${e.status}: ${e.message}` : 'Test failed'
  } finally {
    testRunning.value = false
  }
}
</script>

<template>
  <v-dialog :model-value="modelValue" max-width="1100" @update:model-value="emit('update:modelValue', $event)">
    <v-card :title="cluster ? 'Edit cluster' : 'Add cluster'">
      <v-card-text>
        <v-alert v-if="error" type="error" :text="error" class="mb-4" />
        <v-text-field v-model="name" label="Name" required autofocus />
        <v-select v-model="clusterType" :items="typeOptions" label="Type" required />
        <v-text-field v-model="url" label="URL" placeholder="https://cluster.example.com" required />
        <v-textarea v-model="description" label="Description" rows="2" auto-grow />

        <template v-if="clusterType === 'K8S'">
          <v-switch v-model="gitOpsEnabled" label="Managed by GitOps" color="primary" density="compact" hide-details class="mt-2" />
          <div v-if="gitOpsEnabled" class="d-flex ga-4 mt-2">
            <v-select
              v-model="gitRepo"
              :items="selectableOptions(gitRepo)"
              label="Git repository"
              hint="Only repos cdrm is configured to authenticate with (see cdrm.gitops.repositories) show up here."
              persistent-hint
              required
              style="flex: 2"
            />
            <v-text-field
              v-model="gitBranch"
              label="Git branch"
              placeholder="main"
              hint="Default branch to commit to — a namespace can override this below."
              persistent-hint
              required
              style="flex: 1"
            />
          </div>

          <v-divider class="my-4" />
          <div class="d-flex align-center justify-space-between mb-1">
            <span class="text-body-2 text-medium-emphasis">Kubernetes Namespaces</span>
            <div class="d-flex ga-2">
              <v-btn size="small" variant="text" prepend-icon="mdi-plus" @click="addNamespace">Add namespace</v-btn>
              <v-btn
                size="small"
                variant="text"
                color="error"
                prepend-icon="mdi-delete"
                :disabled="selectedNamespaceIndex === null"
                @click="removeNamespace(selectedNamespaceIndex!)"
              >
                Remove
              </v-btn>
            </div>
          </div>
          <v-select
            v-model="selectedNamespaceIndex"
            :items="namespaceSelectItems"
            label="Namespace"
            :disabled="namespaceRows.length === 0"
            hint="Pick a namespace to view or edit its settings."
            persistent-hint
          />

          <div v-if="!selectedRow" class="text-body-2 text-medium-emphasis mt-4">
            {{ namespaceRows.length === 0 ? 'No namespaces configured yet.' : 'Select a namespace above to edit it.' }}
          </div>
          <template v-else>
            <!-- Kubernetes namespaces aren't renamed once created — only a brand new
                 (still-unnamed) row gets an editable name; the picker above already
                 shows an existing one's name, so no need to repeat it here. -->
            <v-text-field
              v-if="!selectedRow.namespace"
              v-model="selectedRow.namespace"
              label="Namespace name"
              density="compact"
              hide-details
              placeholder="namespace"
              class="mt-4"
              autofocus
            />
            <v-checkbox
              v-if="gitOpsEnabled"
              v-model="selectedRow.gitOps"
              label="Managed by GitOps"
              density="compact"
              hide-details
              class="mt-2"
            />
            <template v-if="gitOpsEnabled && selectedRow.gitOps">
              <v-btn-toggle v-model="selectedRow.mode" mandatory density="compact" color="primary" variant="outlined" class="mt-2">
                <v-btn v-for="option in modeOptions" :key="option.value" :value="option.value">{{ option.title }}</v-btn>
              </v-btn-toggle>
              <div class="text-caption text-medium-emphasis mt-1">
                Only the mode selected above is used for this namespace — the other mode's fields below are ignored, even if
                still filled in.
              </div>

              <template v-if="selectedRow.mode === 'SIMPLE'">
                <v-text-field
                  v-model="selectedRow.fileExpression"
                  label="File path"
                  placeholder="environments/{namespace}/{workload}.yaml"
                  class="mt-2"
                />
                <v-text-field
                  v-model="selectedRow.yamlExpression"
                  label="YAML key path"
                  placeholder="spec.template.spec.containers[0].image"
                  class="mt-2"
                />
              </template>
              <template v-else>
                <div class="d-flex align-center justify-space-between mt-2">
                  <span class="text-body-2 text-medium-emphasis">Template script (JavaScript)</span>
                  <v-btn size="small" variant="tonal" color="primary" prepend-icon="mdi-play" @click="openTestDialog">Test</v-btn>
                </div>
                <v-textarea
                  v-model="selectedRow.templateScript"
                  placeholder="return [{ gitBranch: 'main', filePath: `environments/${namespace}/${workloadName}.yaml`, yamlKeyPath: 'spec.template.spec.containers[0].image', value: releaseBinary }]"
                  hint="Available: gitRepoName, clusterName, namespace, productName, stageName, workloadName, releaseBinary, targetStage. Must return an array of {gitBranch, filePath, yamlKeyPath, value} objects — all four required, all strings."
                  persistent-hint
                  rows="8"
                  style="font-family: monospace"
                />
              </template>

              <div class="d-flex ga-4 mt-2">
                <v-select
                  v-model="selectedRow.gitRepo"
                  :items="selectableOptions(selectedRow.gitRepo)"
                  label="Repo override"
                  :placeholder="`defaults to '${gitRepo}'`"
                  hint="Leave blank to use the cluster-wide repo above. Applies to both modes above."
                  persistent-hint
                  clearable
                  style="flex: 2"
                />
                <v-text-field
                  v-if="selectedRow.mode === 'SIMPLE'"
                  v-model="selectedRow.gitBranch"
                  label="Branch override"
                  :placeholder="`defaults to '${gitBranch}'`"
                  hint="Leave blank to use the cluster-wide branch above. Not used in Advanced mode — the template returns its own branch per edit."
                  persistent-hint
                  style="flex: 1"
                />
              </div>
            </template>
          </template>
        </template>
      </v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn @click="close">Cancel</v-btn>
        <v-btn color="primary" :loading="saving" @click="save">Save</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>

  <v-dialog v-model="testDialogOpen" max-width="960">
    <v-card :title="`Test template script — ${selectedRow?.namespace || '(unnamed)'}`">
      <v-card-text>
        <v-alert v-if="testError" type="error" :text="testError" class="mb-4" />
        <div class="d-flex flex-wrap ga-2">
          <v-select
            v-model="testStageId"
            :items="testStageOptions"
            :loading="testOptionsLoading"
            label="Stage"
            style="min-width: 220px; flex: 1"
            hide-details
          />
          <v-select
            v-model="testWorkloadId"
            :items="testWorkloadOptions"
            :loading="testOptionsLoading"
            :disabled="!testStageId"
            label="Workload"
            style="min-width: 220px; flex: 1"
            hide-details
          />
          <v-text-field
            v-model="testReleaseBinary"
            label="Release binary (test value)"
            style="min-width: 220px; flex: 1"
            hide-details
          />
        </div>
        <div class="text-caption text-medium-emphasis mt-1">
          Repository: {{ effectiveRepositoryUrl || '(none configured)' }} — this namespace can only ever be reached via a
          stage in the <strong>{{ testPipeline || '(unknown)' }}</strong> pipeline, so the stage (and, once picked, the
          workload) options above are narrowed down to exactly that.
        </div>

        <v-textarea
          v-if="selectedRow"
          v-model="selectedRow.templateScript"
          label="Template script (JavaScript)"
          rows="14"
          style="font-family: monospace"
          hint="Same buffer as the editor behind this dialog — editing here edits it there too."
          persistent-hint
          class="mt-4"
        />

        <v-btn color="primary" :loading="testRunning" prepend-icon="mdi-play" class="mt-4" @click="runTest">Run test</v-btn>

        <v-textarea
          v-if="testResult"
          :model-value="testResult"
          label="Result"
          readonly
          rows="14"
          style="font-family: monospace"
          class="mt-4"
        />
      </v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn @click="testDialogOpen = false">Close</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
