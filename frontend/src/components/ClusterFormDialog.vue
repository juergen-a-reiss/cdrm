<!--
  Required Notice: Copyright Dr. Juergen A. Reiss
  Licensed under the terms in the LICENSE file at the repository root.
-->

<script setup lang="ts">
import { ref, watch } from 'vue'
import { ApiError } from '../api/http'
import { clustersApi } from '../api/clusters'
import type { ClusterResponse, ClusterType } from '../api/types'

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

const name = ref('')
const description = ref('')
const clusterType = ref<ClusterType>('K8S')
const url = ref('')
const k8sNamespaces = ref<string[]>([])
const saving = ref(false)
const error = ref<string | null>(null)

function sortAscending(namespaces: string[]): string[] {
  return [...namespaces].sort((a, b) => a.localeCompare(b))
}

function parseNamespaces(value: string | null): string[] {
  return sortAscending(
    (value ?? '')
      .split(',')
      .map((ns) => ns.trim())
      .filter((ns) => ns.length > 0),
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
      k8sNamespaces.value = parseNamespaces(cluster?.k8sNamespaces ?? null)
      error.value = null
    }
  },
  { immediate: true },
)

watch(clusterType, (type) => {
  if (type !== 'K8S') {
    k8sNamespaces.value = []
  }
})

function addNamespace() {
  k8sNamespaces.value.push('')
}

function removeNamespace(index: number) {
  k8sNamespaces.value.splice(index, 1)
}

function close() {
  emit('update:modelValue', false)
}

async function save() {
  saving.value = true
  error.value = null
  try {
    const namespaces = sortAscending(k8sNamespaces.value.map((ns) => ns.trim()).filter((ns) => ns.length > 0))
    const request = {
      name: name.value,
      description: description.value || null,
      clusterType: clusterType.value,
      url: url.value,
      k8sNamespaces: namespaces.length > 0 ? namespaces.join(',') : null,
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
  <v-dialog :model-value="modelValue" max-width="480" @update:model-value="emit('update:modelValue', $event)">
    <v-card :title="cluster ? 'Edit cluster' : 'Add cluster'">
      <v-card-text>
        <v-alert v-if="error" type="error" :text="error" class="mb-4" />
        <v-text-field v-model="name" label="Name" required autofocus />
        <v-select v-model="clusterType" :items="typeOptions" label="Type" required />
        <v-text-field v-model="url" label="URL" placeholder="https://cluster.example.com" required />
        <v-textarea v-model="description" label="Description" />

        <template v-if="clusterType === 'K8S'">
          <div class="d-flex align-center justify-space-between mt-4 mb-1">
            <span class="text-body-2 text-medium-emphasis">Kubernetes Namespaces</span>
            <v-btn size="small" variant="text" prepend-icon="mdi-plus" @click="addNamespace">Add</v-btn>
          </div>
          <div v-if="k8sNamespaces.length === 0" class="text-body-2 text-medium-emphasis mb-2">
            No namespaces configured
          </div>
          <div v-for="(_, index) in k8sNamespaces" :key="index" class="d-flex align-center ga-2 mb-2">
            <v-text-field
              v-model="k8sNamespaces[index]"
              density="compact"
              hide-details
              placeholder="namespace"
            />
            <v-icon icon="mdi-delete" size="small" @click="removeNamespace(index)" />
          </div>
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
