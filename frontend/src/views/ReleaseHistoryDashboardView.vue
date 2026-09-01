<!--
  Required Notice: Copyright Dr. Juergen A. Reiss
  Licensed under the terms in the LICENSE file at the repository root.
-->

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { DataTableHeader } from 'vuetify/lib/components/VDataTable/types.js'
import { useResourceList } from '../composables/useResourceList'
import { useProductFilter } from '../composables/useProductFilter'
import { useStageFilter } from '../composables/useStageFilter'
import { useWorkloadFilter } from '../composables/useWorkloadFilter'
import ProductFilterBar from '../components/ProductFilterBar.vue'
import StageFilterBar from '../components/StageFilterBar.vue'
import WorkloadFilterBar from '../components/WorkloadFilterBar.vue'
import ResourceTable from '../components/ResourceTable.vue'
import ReleaseHistoryChart from '../components/ReleaseHistoryChart.vue'
import type { ChartSeries } from '../components/ReleaseHistoryChart.vue'
import { releasesApi } from '../api/releases'
import type { ReleaseHistoryAction, ReleaseHistoryOverviewEntry } from '../api/types'
import { RELEASE_HISTORY_ACTIONS, RELEASE_HISTORY_ACTION_COLORS, RELEASE_HISTORY_ACTION_LABELS } from '../utils/releaseHistoryAction'
import { colorForKey } from '../utils/categoricalPalette'
import { formatDateTime } from '../utils/formatDateTime'
import { formatDeploymentStatus } from '../utils/releaseHistoryStatus'

type GroupBy = 'action' | 'product' | 'workload'

const { items: entries, loading, error } = useResourceList(releasesApi.historyOverview)
const { matches: matchesProduct } = useProductFilter()
const { matches: matchesStage } = useStageFilter()
const { selectedWorkloadIds, matches: matchesWorkload } = useWorkloadFilter()

const actionOptions = RELEASE_HISTORY_ACTIONS.map((action) => ({ title: RELEASE_HISTORY_ACTION_LABELS[action], value: action }))
const selectedActions = ref<ReleaseHistoryAction[]>([])

const rangeOptions = [
  { title: 'Last month', value: 1 },
  { title: 'Last 3 months', value: 3 },
  { title: 'Last 6 months', value: 6 },
  { title: 'Last 12 months', value: 12 },
  { title: 'All time', value: 0 },
]
const monthsBack = ref(6)

const groupByOptions: { title: string; value: GroupBy }[] = [
  { title: 'Action', value: 'action' },
  { title: 'Product', value: 'product' },
  { title: 'Workload', value: 'workload' },
]
const groupBy = ref<GroupBy>('action')
const chartType = ref<'bar' | 'line'>('bar')

// Soft cap on distinct product/workload series — beyond it the tail folds into
// "Other" rather than generating more hues (a 9th+ color is indistinguishable
// from an existing one under color-vision deficiency).
const MAX_SERIES = 8
const OTHER_KEY = '__other__'

function monthKey(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`
}

function monthsAgo(n: number): string {
  const d = new Date()
  d.setDate(1)
  d.setMonth(d.getMonth() - n)
  return monthKey(d)
}

function monthLabel(month: string): string {
  const [year, m] = month.split('-')
  const date = new Date(Number(year), Number(m) - 1, 1)
  return date.toLocaleDateString(undefined, { month: 'short', year: '2-digit' })
}

function entryMonth(entry: ReleaseHistoryOverviewEntry): string {
  return monthKey(new Date(entry.timestamp))
}

const cutoffMonth = computed(() => (monthsBack.value === 0 ? null : monthsAgo(monthsBack.value - 1)))

const filteredEntries = computed(() =>
  entries.value.filter((entry) => {
    if (!matchesProduct(entry.productId)) return false
    if (entry.workloadId === null ? selectedWorkloadIds.value.length > 0 : !matchesWorkload(entry.workloadId)) return false
    if (!matchesStage(entry.stage.id)) return false
    if (selectedActions.value.length > 0 && !selectedActions.value.includes(entry.action)) return false
    if (cutoffMonth.value !== null && entryMonth(entry) < cutoffMonth.value) return false
    return true
  }),
)

// The month axis is built from the selected range (zero-filled), not just months
// that happen to have data — otherwise a quiet month reads as "missing" rather
// than "zero", which misstates the trend.
const chartMonths = computed<string[]>(() => {
  if (monthsBack.value > 0) {
    return Array.from({ length: monthsBack.value }, (_, i) => monthsAgo(monthsBack.value - 1 - i))
  }
  return [...new Set(filteredEntries.value.map(entryMonth))].sort()
})

const chartLabels = computed(() => chartMonths.value.map(monthLabel))

function entityId(entry: ReleaseHistoryOverviewEntry): string {
  return (groupBy.value === 'product' ? entry.productId : entry.workloadId) ?? 'unknown'
}

function entityName(entry: ReleaseHistoryOverviewEntry): string {
  return groupBy.value === 'product' ? entry.productName : entry.workloadName
}

// Ranked by volume so the entities that actually matter keep their own series;
// the rest fold into "Other" rather than each grabbing a thinning-out hue.
const activeSeries = computed<ChartSeries[]>(() => {
  if (groupBy.value === 'action') {
    return RELEASE_HISTORY_ACTIONS.map((action) => ({
      key: action,
      label: RELEASE_HISTORY_ACTION_LABELS[action],
      color: RELEASE_HISTORY_ACTION_COLORS[action],
    }))
  }

  const totals = new Map<string, { label: string; total: number }>()
  for (const entry of filteredEntries.value) {
    const key = entityId(entry)
    const existing = totals.get(key)
    if (existing) existing.total++
    else totals.set(key, { label: entityName(entry), total: 1 })
  }

  const ranked = [...totals.entries()].sort((a, b) => b[1].total - a[1].total)
  const kept = ranked.slice(0, MAX_SERIES - 1)
  const overflow = ranked.slice(MAX_SERIES - 1)

  const series = kept.map(([key, { label }]) => ({ key, label, color: colorForKey(key) }))
  if (overflow.length > 0) {
    series.push({ key: OTHER_KEY, label: 'Other', color: colorForKey(OTHER_KEY) })
  }
  return series
})

const chartValues = computed<Record<string, number[]>>(() => {
  const monthIndex = new Map(chartMonths.value.map((m, i) => [m, i]))
  const seriesKeys = new Set(activeSeries.value.map((s) => s.key))
  const values: Record<string, number[]> = {}
  for (const s of activeSeries.value) values[s.key] = chartMonths.value.map(() => 0)

  for (const entry of filteredEntries.value) {
    const idx = monthIndex.get(entryMonth(entry))
    if (idx === undefined) continue
    const key = groupBy.value === 'action' ? entry.action : entityId(entry)
    const bucket = seriesKeys.has(key) ? key : OTHER_KEY
    if (values[bucket]) values[bucket][idx]++
  }
  return values
})

interface HistoryRow {
  id: string
  timestamp: string
  actionLabel: string
  productName: string
  workloadName: string
  stageName: string
  binaryUrl: string
  deployedDisplay: string
  createdBy: string
}

// Free-text filter over product/workload/stage/binary-URL/etc., independent of the
// ID-based filter bars above — this one works even for a product/workload/stage that's
// since been deleted, since it matches against the name snapshotted onto each row.
const search = ref('')

// Backend already returns entries newest-first; no extra client-side sort needed unless
// the user clicks a column header.
const tableRows = computed<HistoryRow[]>(() =>
  filteredEntries.value.map((entry) => ({
    id: entry.id,
    timestamp: entry.timestamp,
    actionLabel: RELEASE_HISTORY_ACTION_LABELS[entry.action],
    productName: entry.productName,
    workloadName: entry.workloadName,
    stageName: entry.stage.name,
    binaryUrl: entry.binaryUrl,
    deployedDisplay: formatDeploymentStatus(entry),
    createdBy: entry.createdBy,
  })),
)

const historyHeaders: DataTableHeader<HistoryRow>[] = [
  { title: 'Timestamp', key: 'timestamp' },
  { title: 'Action', key: 'actionLabel' },
  { title: 'Product', key: 'productName' },
  { title: 'Workload', key: 'workloadName' },
  { title: 'Stage', key: 'stageName' },
  { title: 'Binary URL', key: 'binaryUrl' },
  { title: 'Deployed', key: 'deployedDisplay' },
  { title: 'By', key: 'createdBy' },
]
</script>

<template>
  <h1 class="text-h5 mb-4">Release History Dashboard</h1>

  <div class="d-flex flex-wrap ga-2 align-center mb-4">
    <ProductFilterBar />
    <StageFilterBar />
    <WorkloadFilterBar />
    <v-select
      v-model="selectedActions"
      :items="actionOptions"
      label="Filter by action"
      multiple
      chips
      closable-chips
      clearable
      hide-details
      width="260"
    />
    <v-select v-model="monthsBack" :items="rangeOptions" label="Range" hide-details width="180" />
  </div>

  <v-card class="mb-6" variant="outlined">
    <v-card-title class="text-subtitle-1 d-flex align-center flex-wrap ga-4">
      <span>Release History Graph</span>
      <v-spacer />
      <v-select
        v-model="groupBy"
        :items="groupByOptions"
        label="Group by"
        density="compact"
        hide-details
        width="200"
      />
      <v-btn-toggle v-model="chartType" mandatory density="compact" color="primary" variant="outlined">
        <v-btn value="bar" icon="mdi-chart-bar" title="Bar chart" />
        <v-btn value="line" icon="mdi-chart-line" title="Line chart" />
      </v-btn-toggle>
    </v-card-title>
    <v-card-text>
      <v-alert v-if="error" type="error" :text="error" class="mb-4" />
      <v-progress-linear v-if="loading" indeterminate class="mb-4" />
      <ReleaseHistoryChart v-else :chart-type="chartType" :labels="chartLabels" :series="activeSeries" :values="chartValues" />
    </v-card-text>
  </v-card>

  <v-card variant="outlined">
    <v-card-title class="text-subtitle-1 d-flex align-center flex-wrap ga-4">
      <span>Release History Details</span>
      <v-spacer />
      <v-text-field
        v-model="search"
        label="Search product, workload, stage…"
        prepend-inner-icon="mdi-magnify"
        density="compact"
        variant="outlined"
        clearable
        hide-details
        width="280"
      />
    </v-card-title>
    <ResourceTable :headers="historyHeaders" :items="tableRows" :loading="loading" :error="null" :search="search">
      <template #item.timestamp="{ item }">{{ formatDateTime(item.timestamp) }}</template>
    </ResourceTable>
  </v-card>
</template>
