<!--
  Required Notice: Copyright Dr. Juergen A. Reiss
  Licensed under the terms in the LICENSE file at the repository root.
-->

<script setup lang="ts">
import { computed, watch } from 'vue'
import { useProductFilter } from '../composables/useProductFilter'

const { selectedProductIds, products } = useProductFilter()

// Selected IDs are persisted across page loads (see useIdFilter), so a stale one — e.g.
// left over after the database was recreated and products got new IDs — would
// otherwise filter every row out forever with no way to tell why. Drop anything not
// present once the live list has loaded.
watch(products, (list) => {
  const validIds = new Set(list.map((product) => product.id))
  selectedProductIds.value = selectedProductIds.value.filter((id) => validIds.has(id))
})

const productOptions = computed(() =>
  products.value
    .map((product) => ({ title: product.isGroup ? `${product.name} (group)` : product.name, value: product.id }))
    .sort((a, b) => a.title.localeCompare(b.title, undefined, { sensitivity: 'base' })),
)
const isActive = computed(() => selectedProductIds.value.length > 0)
</script>

<template>
  <v-select
    v-model="selectedProductIds"
    :items="productOptions"
    label="Filter by product"
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
