<!--
  Required Notice: Copyright Dr. Juergen A. Reiss
  Licensed under the terms in the LICENSE file at the repository root.
-->

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import type { DataTableHeader } from 'vuetify/lib/components/VDataTable/types.js'
import { useProductFilter } from '../composables/useProductFilter'
import { useStageFilter } from '../composables/useStageFilter'
import { useWorkloadFilter } from '../composables/useWorkloadFilter'
import { usePipelineFilter } from '../composables/usePipelineFilter'
import { usePersistedRef } from '../composables/usePersistedRef'
import ProductFilterBar from '../components/ProductFilterBar.vue'
import StageFilterBar from '../components/StageFilterBar.vue'
import WorkloadFilterBar from '../components/WorkloadFilterBar.vue'
import PipelineFilterBar from '../components/PipelineFilterBar.vue'
import ResourceTable from '../components/ResourceTable.vue'
import type { SortByItem } from '../components/ResourceTable.vue'
import ReleaseHistoryChart from '../components/ReleaseHistoryChart.vue'
import type { ChartSeries } from '../components/ReleaseHistoryChart.vue'
import { releasesApi } from '../api/releases'
import { ApiError } from '../api/http'
import type { ReleaseHistoryAction, ReleaseHistoryFilterParams, ReleaseHistoryGroupBy, ReleaseHistoryOverviewEntry, ReleaseHistorySummaryEntry } from '../api/types'
import { RELEASE_HISTORY_ACTIONS, RELEASE_HISTORY_ACTION_COLORS, RELEASE_HISTORY_ACTION_LABELS } from '../utils/releaseHistoryAction'
import { colorForKey } from '../utils/categoricalPalette'
import { formatDateTime } from '../utils/formatDateTime'
import { formatDeploymentStatus } from '../utils/releaseHistoryStatus'
import { sortParam } from '../utils/sortParam'

// Everything below — the table's rows, its total count, and the chart's aggregated
// counts — comes from the backend already filtered, sorted, and (for the table)
// paginated. Nothing here fetches the full release_history table into the browser;
// see ReleaseService.historyOverview()/historySummary() and the Specification/
// Criteria-API queries behind them.

// Only the selected-ids state is used here — filtering itself now happens in the
// backend (the "sort"/filter query params), and each *FilterBar component already
// prunes its own stale selection against its own live list independently.
const { selectedProductIds } = useProductFilter()
const { selectedStageIds } = useStageFilter()
const { selectedWorkloadIds } = useWorkloadFilter()
const { selectedPipelines } = usePipelineFilter()

const actionOptions = RELEASE_HISTORY_ACTIONS.map((action) => ({ title: RELEASE_HISTORY_ACTION_LABELS[action], value: action }))
const selectedActions = usePersistedRef<ReleaseHistoryAction[]>('cdrm.releaseHistory.actions', [])

const rangeOptions = [
  { title: 'Last month', value: 1 },
  { title: 'Last 3 months', value: 3 },
  { title: 'Last 6 months', value: 6 },
  { title: 'Last 12 months', value: 12 },
  { title: 'All time', value: 0 },
]
const monthsBack = usePersistedRef<number>('cdrm.releaseHistory.monthsBack', 6)

const groupByOptions: { title: string; value: ReleaseHistoryGroupBy }[] = [
  { title: 'Action', value: 'ACTION' },
  { title: 'Product', value: 'PRODUCT' },
  { title: 'Workload', value: 'WORKLOAD' },
  { title: 'Stage', value: 'STAGE' },
]
const groupBy = usePersistedRef<ReleaseHistoryGroupBy>('cdrm.releaseHistory.groupBy', 'ACTION')
const chartType = ref<'bar' | 'line'>('line')

// Free-text filter over product/workload/stage/image — sent to the backend (debounced,
// see below), not matched client-side, now that the table is paginated: a client-side
// filter could only ever search within whatever page happened to be loaded.
const search = ref('')
const debouncedSearch = ref('')
let searchDebounceTimer: ReturnType<typeof setTimeout> | undefined
watch(search, (value) => {
  clearTimeout(searchDebounceTimer)
  searchDebounceTimer = setTimeout(() => {
    debouncedSearch.value = value
  }, 300)
})

function currentFilterParams(): ReleaseHistoryFilterParams {
  return {
    productIds: selectedProductIds.value.length > 0 ? selectedProductIds.value : undefined,
    workloadIds: selectedWorkloadIds.value.length > 0 ? selectedWorkloadIds.value : undefined,
    stageIds: selectedStageIds.value.length > 0 ? selectedStageIds.value : undefined,
    pipelines: selectedPipelines.value.length > 0 ? selectedPipelines.value : undefined,
    actions: selectedActions.value.length > 0 ? selectedActions.value : undefined,
    monthsBack: monthsBack.value,
    search: debouncedSearch.value.trim() || undefined,
  }
}

// Soft cap on distinct product/workload/stage series — beyond it the tail folds into
// "Other" rather than generating more hues (a 9th+ color is indistinguishable from an
// existing one under color-vision deficiency).
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

// Every YYYY-MM from startMonth to endMonth inclusive, so a quiet month in the middle
// of the span still gets its own zero-filled slot instead of being skipped.
function monthRange(startMonth: string, endMonth: string): string[] {
  const [startYear, startM] = startMonth.split('-').map(Number)
  const [endYear, endM] = endMonth.split('-').map(Number)
  const months: string[] = []
  let year = startYear
  let month = startM
  while (year < endYear || (year === endYear && month <= endM)) {
    months.push(`${year}-${String(month).padStart(2, '0')}`)
    month++
    if (month > 12) {
      month = 1
      year++
    }
  }
  return months
}

function monthLabel(month: string): string {
  const [year, m] = month.split('-')
  const date = new Date(Number(year), Number(m) - 1, 1)
  return date.toLocaleDateString(undefined, { month: 'short', year: '2-digit' })
}

const summaryEntries = ref<ReleaseHistorySummaryEntry[]>([])
const chartLoading = ref(false)
const chartError = ref<string | null>(null)

async function loadSummary() {
  chartLoading.value = true
  chartError.value = null
  try {
    summaryEntries.value = await releasesApi.historySummary({ ...currentFilterParams(), groupBy: groupBy.value })
  } catch (e) {
    chartError.value = e instanceof ApiError ? `${e.status}: ${e.message}` : 'Failed to load chart data'
  } finally {
    chartLoading.value = false
  }
}

// The month axis is built from the selected range (zero-filled), not just months that
// happen to have data — otherwise a quiet month reads as "missing" rather than "zero",
// which misstates the trend. For "All time" there's no fixed range to enumerate, so it
// spans the earliest to the latest month present in the (already fully-filtered, just
// not paginated) summary instead — still filling in any quiet month in between.
const chartMonths = computed<string[]>(() => {
  if (monthsBack.value > 0) {
    return Array.from({ length: monthsBack.value }, (_, i) => monthsAgo(monthsBack.value - 1 - i))
  }
  const months = summaryEntries.value.map((entry) => entry.month)
  if (months.length === 0) return []
  const sorted = [...new Set(months)].sort()
  return monthRange(sorted[0], sorted[sorted.length - 1])
})

const chartLabels = computed(() => chartMonths.value.map(monthLabel))

// Ranked by volume so the entities that actually matter keep their own series; the
// rest fold into "Other" rather than each grabbing a thinning-out hue.
const activeSeries = computed<ChartSeries[]>(() => {
  if (groupBy.value === 'ACTION') {
    return RELEASE_HISTORY_ACTIONS.map((action) => ({
      key: action,
      label: RELEASE_HISTORY_ACTION_LABELS[action],
      color: RELEASE_HISTORY_ACTION_COLORS[action],
    }))
  }

  const totals = new Map<string, number>()
  for (const entry of summaryEntries.value) {
    totals.set(entry.key, (totals.get(entry.key) ?? 0) + entry.count)
  }

  const ranked = [...totals.entries()].sort((a, b) => b[1] - a[1])
  const kept = ranked.slice(0, MAX_SERIES - 1)
  const overflow = ranked.slice(MAX_SERIES - 1)

  const series = kept.map(([key]) => ({ key, label: key, color: colorForKey(key) }))
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

  for (const entry of summaryEntries.value) {
    const idx = monthIndex.get(entry.month)
    if (idx === undefined) continue
    const bucket = seriesKeys.has(entry.key) ? entry.key : OTHER_KEY
    if (values[bucket]) values[bucket][idx] += entry.count
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
  image: string
  deployedDisplay: string
  createdBy: string
}

const sortBy = usePersistedRef<SortByItem[]>('cdrm.releaseHistory.sortBy', [{ key: 'timestamp', order: 'desc' }])
const page = ref(1)
const itemsPerPage = usePersistedRef<number>('cdrm.releaseHistory.itemsPerPage', 25)

const rawEntries = ref<ReleaseHistoryOverviewEntry[]>([])
const totalElements = ref(0)
const tableLoading = ref(false)
const tableError = ref<string | null>(null)

async function loadTable() {
  tableLoading.value = true
  tableError.value = null
  try {
    const result = await releasesApi.historyOverview({
      ...currentFilterParams(),
      sort: sortParam(sortBy.value),
      page: page.value - 1,
      size: itemsPerPage.value,
    })
    rawEntries.value = result.content
    totalElements.value = result.totalElements
  } catch (e) {
    tableError.value = e instanceof ApiError ? `${e.status}: ${e.message}` : 'Failed to load history'
  } finally {
    tableLoading.value = false
  }
}

const tableRows = computed<HistoryRow[]>(() =>
  rawEntries.value.map((entry) => ({
    id: entry.id,
    timestamp: entry.timestamp,
    actionLabel: RELEASE_HISTORY_ACTION_LABELS[entry.action],
    productName: entry.productName,
    workloadName: entry.workloadName,
    stageName: entry.stage.name,
    image: entry.image,
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
  { title: 'Image', key: 'image' },
  { title: 'Deployed', key: 'deployedDisplay' },
  { title: 'By', key: 'createdBy' },
]

// Any filter/search/range change affects both the table and the chart, and invalidates
// whatever page of the table was showing.
watch(
  [selectedProductIds, selectedWorkloadIds, selectedStageIds, selectedPipelines, selectedActions, monthsBack, debouncedSearch],
  () => {
    page.value = 1
    loadTable()
    loadSummary()
  },
  { deep: true },
)

// Sorting and pagination only affect the table.
watch(sortBy, loadTable, { deep: true })
watch([page, itemsPerPage], loadTable)

// Group-by only affects the chart.
watch(groupBy, loadSummary)

onMounted(() => {
  loadTable()
  loadSummary()
})
</script>

<template>
  <h1 class="text-h5 mb-4">Release History Dashboard</h1>

  <div class="d-flex flex-wrap ga-2 align-center mb-4">
    <PipelineFilterBar />
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
      width="320"
      class="flex-grow-0"
    />
    <v-select v-model="monthsBack" :items="rangeOptions" label="Range" hide-details width="320" class="flex-grow-0" />
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
        width="320"
        class="flex-grow-0"
      />
      <v-btn-toggle v-model="chartType" mandatory density="compact" color="primary" variant="outlined">
        <v-btn value="bar" icon="mdi-chart-bar" title="Bar chart" />
        <v-btn value="line" icon="mdi-chart-line" title="Line chart" />
      </v-btn-toggle>
    </v-card-title>
    <v-card-text>
      <v-alert v-if="chartError" type="error" :text="chartError" class="mb-4" />
      <v-progress-linear v-if="chartLoading" indeterminate class="mb-4" />
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
    <ResourceTable
      :headers="historyHeaders"
      :items="tableRows"
      :items-length="totalElements"
      :loading="tableLoading"
      :error="tableError"
      v-model:sort-by="sortBy"
      v-model:page="page"
      v-model:items-per-page="itemsPerPage"
    >
      <template #item.timestamp="{ item }">{{ formatDateTime(item.timestamp) }}</template>
    </ResourceTable>
  </v-card>
</template>
