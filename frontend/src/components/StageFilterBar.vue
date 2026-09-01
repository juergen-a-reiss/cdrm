<!--
  Required Notice: Copyright Dr. Juergen A. Reiss
  Licensed under the terms in the LICENSE file at the repository root.
-->

<script setup lang="ts">
import { computed, watch } from 'vue'
import { useResourceList } from '../composables/useResourceList'
import { useStageFilter } from '../composables/useStageFilter'
import { stagesApi } from '../api/stages'

const { items: stages } = useResourceList(stagesApi.list)
const { selectedStageIds } = useStageFilter()

// Selected IDs are persisted across page loads (see useIdFilter), so a stale one — e.g.
// left over after the database was recreated and stages got new IDs — would otherwise
// filter every row out forever with no way to tell why. Drop anything not present once
// the live list has loaded.
watch(stages, (list) => {
  const validIds = new Set(list.map((stage) => stage.id))
  selectedStageIds.value = selectedStageIds.value.filter((id) => validIds.has(id))
})

const stageOptions = computed(() =>
  stages.value
    .map((stage) => ({ title: stage.name, value: stage.id }))
    .sort((a, b) => a.title.localeCompare(b.title, undefined, { sensitivity: 'base' })),
)
const isActive = computed(() => selectedStageIds.value.length > 0)
</script>

<template>
  <v-select
    v-model="selectedStageIds"
    :items="stageOptions"
    label="Filter by stage"
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
/* Selected chips scroll horizontally within the field instead of wrapping onto
   multiple lines, so the select never grows taller as more items are picked. */
.filter-select :deep(.v-field__input) {
  flex-wrap: nowrap;
  overflow-x: auto;
}
</style>
