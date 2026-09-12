<!--
  Required Notice: Copyright Dr. Juergen A. Reiss
  Licensed under the terms in the LICENSE file at the repository root.
-->

<script setup lang="ts" generic="T extends { id: string }">
import { computed, useAttrs, useSlots } from 'vue'
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
  // Opt-in, like expandableRows: a consumer only gets row clicks (e.g. to navigate to a
  // detail view) if it actually listens for this event.
  'click:row': [item: T]
}>()

const slots = useSlots()
// Forward any per-column `item.<key>` slot the consumer provides, so custom
// cell rendering isn't limited to a hardcoded list of column names.
const itemSlotNames = computed(() => Object.keys(slots).filter((name) => name.startsWith('item.')))

const attrs = useAttrs()
// Whether a consumer is actually listening for click:row — Vue exposes a bound
// `@click:row` as the `onClick:row` attr — so rows only look clickable (cursor) when
// something will actually happen on click, the same opt-in spirit as expandableRows.
const hasRowClickListener = computed(() => attrs['onClick:row'] !== undefined)
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
    :class="{ 'cdrm-expandable-rows': expandableRows, 'cdrm-clickable-rows': hasRowClickListener }"
    :expand-on-click="expandableRows"
    expand-strategy="single"
    :expanded="expanded"
    @update:expanded="emit('update:expanded', $event)"
    :sort-by="sortBy"
    @update:sort-by="emit('update:sortBy', $event as SortByItem[])"
    @update:page="emit('update:page', $event)"
    @update:items-per-page="emit('update:itemsPerPage', $event)"
    @click:row="(_e: unknown, { item }: { item: T }) => emit('click:row', item)"
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
.cdrm-expandable-rows :deep(tbody tr),
.cdrm-clickable-rows :deep(tbody tr) {
  cursor: pointer;
}

/* Vuetify's .v-table__wrapper already scrolls horizontally (overflow: auto) — it just
   never needs to, because cells wrap their text instead of overflowing. Forcing cells to
   stay on one line lets a narrow (tablet-width) viewport scroll the table sideways
   instead of squeezing every column into unreadable multi-line cells. */
:deep(.v-table__wrapper th),
:deep(.v-table__wrapper td) {
  white-space: nowrap;
}

/* On phone-width screens, a sideways-scrolling table is harder to use than a narrower
   one — so let headers/cells wrap there instead, trading the single-line layout above
   for one that fits (or comes closer to fitting) without scrolling. */
@media (max-width: 600px) {
  :deep(.v-table__wrapper th),
  :deep(.v-table__wrapper td) {
    white-space: normal;
  }
}
</style>
