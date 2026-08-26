<!--
  Required Notice: Copyright Dr. Juergen A. Reiss
  Licensed under the terms in the LICENSE file at the repository root.
-->

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { DataTableHeader } from 'vuetify/lib/components/VDataTable/types.js'
import ResourceTable from '../components/ResourceTable.vue'
import ClusterFormDialog from '../components/ClusterFormDialog.vue'
import { useResourceList } from '../composables/useResourceList'
import { clustersApi } from '../api/clusters'
import { ApiError } from '../api/http'
import type { ClusterResponse } from '../api/types'
import { isAdmin } from '../auth/roles'

const { items, loading, error, reload } = useResourceList(clustersApi.list)

const TYPE_LABELS: Record<string, string> = {
  K8S: 'Kubernetes',
  PROXMOX: 'Proxmox',
}

const headers = computed<DataTableHeader<ClusterResponse>[]>(() => {
  const base: DataTableHeader<ClusterResponse>[] = [
    { title: 'Name', key: 'name' },
    { title: 'Type', key: 'clusterType', value: (item) => TYPE_LABELS[item.clusterType] },
    { title: 'URL', key: 'url' },
    { title: 'Description', key: 'description' },
  ]
  if (isAdmin.value) {
    base.push({ title: 'Actions', key: 'actions', sortable: false, width: 120 })
  }
  return base
})

const dialogOpen = ref(false)
const editingCluster = ref<ClusterResponse | null>(null)
const deleteError = ref<string | null>(null)

function openCreate() {
  editingCluster.value = null
  dialogOpen.value = true
}

function openEdit(cluster: ClusterResponse) {
  editingCluster.value = cluster
  dialogOpen.value = true
}

async function removeCluster(cluster: ClusterResponse) {
  if (!confirm(`Delete cluster "${cluster.name}"?`)) {
    return
  }
  deleteError.value = null
  try {
    await clustersApi.remove(cluster.id)
    await reload()
  } catch (e) {
    deleteError.value = e instanceof ApiError ? `${e.status}: ${e.message}` : 'Failed to delete cluster'
  }
}
</script>

<template>
  <v-alert v-if="deleteError" type="error" :text="deleteError" class="mb-4" />
  <ResourceTable :headers="headers" :items="items" :loading="loading" :error="error">
    <template v-if="isAdmin" #top>
      <v-toolbar flat>
        <v-toolbar-title>Clusters</v-toolbar-title>
        <v-spacer />
        <v-btn color="primary" prepend-icon="mdi-plus" @click="openCreate">Add cluster</v-btn>
      </v-toolbar>
    </template>
    <template v-if="isAdmin" #item.actions="{ item }">
      <v-icon icon="mdi-pencil" size="small" class="mr-2" @click="openEdit(item)" />
      <v-icon icon="mdi-delete" size="small" @click="removeCluster(item)" />
    </template>
  </ResourceTable>

  <ClusterFormDialog v-model="dialogOpen" :cluster="editingCluster" @saved="reload" />
</template>
