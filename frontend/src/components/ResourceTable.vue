<!--
  Required Notice: Copyright Dr. Juergen A. Reiss
  Licensed under the terms in the LICENSE file at the repository root.
-->

<script setup lang="ts" generic="T extends { id: string }">
import { computed, useSlots } from 'vue'
import type { DataTableHeader } from 'vuetify/lib/components/VDataTable/types.js'

const props = defineProps<{
  headers: DataTableHeader<T>[]
  items: T[]
  loading: boolean
  error: string | null
  // Opt-in: expand-on-click changes row styling/behavior in Vuetify, so this
  // must stay off unless a consumer actually wants it.
  expandableRows?: boolean
  expanded?: string[]
}>()

defineSlots<
  {
    top?: (props: Record<string, never>) => unknown
    'expanded-row'?: (props: { item: T; columns: unknown[] }) => unknown
  } & {
    [key: `item.${string}`]: ((props: { item: T }) => unknown) | undefined
  }
>()

const emit = defineEmits<{
  'update:expanded': [value: string[]]
}>()

const slots = useSlots()
// Forward any per-column `item.<key>` slot the consumer provides, so custom
// cell rendering isn't limited to a hardcoded list of column names.
const itemSlotNames = computed(() => Object.keys(slots).filter((name) => name.startsWith('item.')))
</script>

<template>
  <v-alert v-if="error" type="error" :text="error" class="mb-4" />
  <v-data-table
    :headers="headers"
    :items="items"
    :loading="loading"
    item-value="id"
    :class="{ 'cdrm-expandable-rows': expandableRows }"
    :expand-on-click="expandableRows"
    expand-strategy="single"
    :expanded="expanded"
    @update:expanded="emit('update:expanded', $event)"
  >
    <template v-if="$slots.top" #top>
      <slot name="top" />
    </template>
    <template v-for="slotName in itemSlotNames" :key="slotName" #[slotName]="slotProps">
      <slot :name="(slotName as `item.${string}`)" v-bind="slotProps as { item: T }" />
    </template>
    <template v-if="$slots['expanded-row']" #expanded-row="{ item, columns }">
      <slot name="expanded-row" :item="item" :columns="columns" />
    </template>
  </v-data-table>
</template>

<style scoped>
.cdrm-expandable-rows :deep(tbody tr) {
  cursor: pointer;
}
</style>
