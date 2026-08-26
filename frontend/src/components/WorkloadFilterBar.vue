<!--
  Required Notice: Copyright Dr. Juergen A. Reiss
  Licensed under the terms in the LICENSE file at the repository root.
-->

<script setup lang="ts">
import { computed } from 'vue'
import { useResourceList } from '../composables/useResourceList'
import { useWorkloadFilter } from '../composables/useWorkloadFilter'
import { workloadsApi } from '../api/workloads'

const { items: workloads } = useResourceList(workloadsApi.list)
const { selectedWorkloadIds, clear } = useWorkloadFilter()

const workloadOptions = computed(() =>
  workloads.value
    .map((workload) => ({ title: workload.name, value: workload.id }))
    .sort((a, b) => a.title.localeCompare(b.title, undefined, { sensitivity: 'base' })),
)
const isActive = computed(() => selectedWorkloadIds.value.length > 0)
</script>

<template>
  <div class="d-flex align-center ga-2 flex-wrap mb-4">
    <v-select
      v-model="selectedWorkloadIds"
      :items="workloadOptions"
      label="Filter by workload"
      :color="isActive ? 'primary' : undefined"
      multiple
      chips
      closable-chips
      clearable
      hide-details
      width="320"
      class="filter-select"
    />
    <v-btn v-if="isActive" variant="text" size="small" prepend-icon="mdi-filter-remove" @click="clear">
      Clear filter
    </v-btn>
  </div>
</template>

<style scoped>
/* Selected chips scroll horizontally within the field instead of wrapping onto
   multiple lines, so the select never grows taller as more items are picked. */
.filter-select :deep(.v-field__input) {
  flex-wrap: nowrap;
  overflow-x: auto;
}
</style>
