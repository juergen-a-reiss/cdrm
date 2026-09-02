<!--
  Required Notice: Copyright Dr. Juergen A. Reiss
  Licensed under the terms in the LICENSE file at the repository root.
-->

<script setup lang="ts" generic="T extends { id: string }">
import { computed, useSlots } from 'vue'
import type { DataTableHeader } from 'vuetify/lib/components/VDataTable/types.js'

export interface SortByItem {
  key: string
  order?: 'asc' | 'desc'
}

const props = defineProps<{
  headers: DataTableHeader<T>[]
  items: T[]
  loading: boolean
  error: string | null
  // Opt-in: expand-on-click changes row styling/behavior in Vuetify, so this
  // must stay off unless a consumer actually wants it.
  expandableRows?: boolean
  expanded?: string[]
  // Sorting is driven entirely by the consumer (v-model:sort-by): the backend does the
  // actual sorting (see the API's `sort` query param), `items` arrives already sorted,
  // and this only reflects that state in the header arrows / persists it (see
  // usePersistedRef) the same way filters already are. Since the table never re-sorts
  // on its own, it's a v-data-table-server underneath, not a plain v-data-table.
  sortBy?: SortByItem[]
  // Pagination is opt-in and off by default: omit page/itemsPerPage (the default for
  // every table except release history) and every row in `items` is shown, footer
  // hidden — the right choice for a table that will never hold enough rows to need
  // paging. Pass both (1-indexed, matching Vuetify's own convention), plus itemsLength
  // (the TRUE total row count — items.length is just the current page once paginated),
  // to opt into real server-driven paging instead.
  page?: number
  itemsPerPage?: number
  itemsLength?: number
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
  'update:sortBy': [value: SortByItem[]]
  'update:page': [value: number]
  'update:itemsPerPage': [value: number]
}>()

const slots = useSlots()
// Forward any per-column `item.<key>` slot the consumer provides, so custom
// cell rendering isn't limited to a hardcoded list of column names.
const itemSlotNames = computed(() => Object.keys(slots).filter((name) => name.startsWith('item.')))
</script>

<template>
  <v-alert v-if="error" type="error" :text="error" class="mb-4" />
  <v-data-table-server
    :headers="headers"
    :items="items"
    :items-length="itemsLength ?? items.length"
    :loading="loading"
    item-value="id"
    :page="page ?? 1"
    :items-per-page="itemsPerPage ?? -1"
    :hide-default-footer="page === undefined"
    :class="{ 'cdrm-expandable-rows': expandableRows }"
    :expand-on-click="expandableRows"
    expand-strategy="single"
    :expanded="expanded"
    @update:expanded="emit('update:expanded', $event)"
    :sort-by="sortBy"
    @update:sort-by="emit('update:sortBy', $event as SortByItem[])"
    @update:page="emit('update:page', $event)"
    @update:items-per-page="emit('update:itemsPerPage', $event)"
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
  </v-data-table-server>
</template>

<style scoped>
.cdrm-expandable-rows :deep(tbody tr) {
  cursor: pointer;
}
</style>
