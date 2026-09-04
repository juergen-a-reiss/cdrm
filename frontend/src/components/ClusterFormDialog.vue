<!--
  Required Notice: Copyright Dr. Juergen A. Reiss
  Licensed under the terms in the LICENSE file at the repository root.
-->

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ApiError } from '../api/http'
import { clustersApi } from '../api/clusters'
import type { ClusterResponse, ClusterType, K8sGitopsConfig } from '../api/types'

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
  fileExpression: string
  yamlExpression: string
  // Empty means "use the cluster-wide branch" (gitBranch below).
  gitBranch: string
}

const name = ref('')
const description = ref('')
const clusterType = ref<ClusterType>('K8S')
const url = ref('')
const namespaceRows = ref<NamespaceRow[]>([])
const gitOpsEnabled = ref(false)
const gitRepo = ref('')
const gitBranch = ref('main')
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
        fileExpression: g?.fileExpression ?? '',
        yamlExpression: g?.yamlExpression ?? '',
        gitBranch: g?.gitBranch ?? '',
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
      selectedNamespaceIndex.value = null
      error.value = null
    }
  },
  { immediate: true },
)

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
  namespaceRows.value.push({ namespace: '', gitOps: false, fileExpression: '', yamlExpression: '', gitBranch: '' })
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
                  fileExpression: row.fileExpression.trim() || null,
                  yamlExpression: row.yamlExpression.trim() || null,
                  gitBranch: row.gitBranch.trim() || null,
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
</script>

<template>
  <v-dialog :model-value="modelValue" max-width="820" @update:model-value="emit('update:modelValue', $event)">
    <v-card :title="cluster ? 'Edit cluster' : 'Add cluster'">
      <v-card-text>
        <v-alert v-if="error" type="error" :text="error" class="mb-4" />
        <v-text-field v-model="name" label="Name" required autofocus />
        <v-select v-model="clusterType" :items="typeOptions" label="Type" required />
        <v-text-field v-model="url" label="URL" placeholder="https://cluster.example.com" required />
        <v-textarea v-model="description" label="Description" />

        <template v-if="clusterType === 'K8S'">
          <v-switch v-model="gitOpsEnabled" label="Managed by GitOps" color="primary" density="compact" hide-details class="mt-2" />
          <v-text-field
            v-if="gitOpsEnabled"
            v-model="gitRepo"
            label="Git repository"
            placeholder="git@github.com:org/gitops-repo.git"
            required
            class="mt-2"
          />
          <v-text-field
            v-if="gitOpsEnabled"
            v-model="gitBranch"
            label="Git branch"
            placeholder="main"
            hint="Default branch to commit to — a namespace can override this below."
            persistent-hint
            required
            class="mt-2"
          />

          <v-divider class="my-4" />
          <div class="d-flex align-center justify-space-between mb-1">
            <span class="text-body-2 text-medium-emphasis">Kubernetes Namespaces</span>
            <v-btn size="small" variant="text" prepend-icon="mdi-plus" @click="addNamespace">Add namespace</v-btn>
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
            <div class="d-flex align-center ga-2 mt-4">
              <v-text-field v-model="selectedRow.namespace" label="Namespace name" density="compact" hide-details placeholder="namespace" />
              <v-btn
                size="small"
                variant="text"
                color="error"
                prepend-icon="mdi-delete"
                @click="removeNamespace(selectedNamespaceIndex!)"
              >
                Remove
              </v-btn>
            </div>
            <v-checkbox
              v-if="gitOpsEnabled"
              v-model="selectedRow.gitOps"
              label="Managed by GitOps"
              density="compact"
              hide-details
              class="mt-2"
            />
            <template v-if="gitOpsEnabled && selectedRow.gitOps">
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
              <v-text-field
                v-model="selectedRow.gitBranch"
                label="Branch override"
                :placeholder="`defaults to '${gitBranch}'`"
                hint="Leave blank to use the cluster-wide branch above."
                persistent-hint
                class="mt-2"
              />
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
</template>
