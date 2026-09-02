<!--
  Required Notice: Copyright Dr. Juergen A. Reiss
  Licensed under the terms in the LICENSE file at the repository root.
-->

<script setup lang="ts">
import { computed, watch } from 'vue'
import { useResourceList } from '../composables/useResourceList'
import { usePipelineFilter } from '../composables/usePipelineFilter'
import { stagesApi } from '../api/stages'

const { items: stages } = useResourceList(stagesApi.list)
const { selectedPipelines } = usePipelineFilter()

// Selected pipeline names are persisted across page loads (see useIdFilter), so a stale
// one — e.g. a pipeline that no longer has any stages — would otherwise filter every row
// out forever with no way to tell why. Drop anything not present once the live list has
// loaded.
watch(stages, (list) => {
  const validPipelines = new Set(list.map((stage) => stage.pipeline))
  selectedPipelines.value = selectedPipelines.value.filter((pipeline) => validPipelines.has(pipeline))
})

const pipelineOptions = computed(() =>
  [...new Set(stages.value.map((stage) => stage.pipeline))].sort((a, b) => a.localeCompare(b, undefined, { sensitivity: 'base' })),
)
const isActive = computed(() => selectedPipelines.value.length > 0)
</script>

<template>
  <v-select
    v-model="selectedPipelines"
    :items="pipelineOptions"
    label="Filter by pipeline"
    :color="isActive ? 'primary' : undefined"
    multiple
    chips
    closable-chips
    clearable
    hide-details
    width="320"
    class="filter-select"
  />
</template>

<style scoped>
/* Vuetify's .v-input defaults to flex: 1 1 auto, which is inert on its own but stretches
   the field to fill the row once a parent (e.g. a filter-bar flex container) actually is
   a flex container — override so the explicit width above is respected either way. */
.filter-select {
  flex-grow: 0;
}

/* Selected chips scroll horizontally within the field instead of wrapping onto
   multiple lines, so the select never grows taller as more items are picked. */
.filter-select :deep(.v-field__input) {
  flex-wrap: nowrap;
  overflow-x: auto;
}
</style>
