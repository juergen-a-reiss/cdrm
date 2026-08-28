<!--
  Required Notice: Copyright Dr. Juergen A. Reiss
  Licensed under the terms in the LICENSE file at the repository root.
-->

<script setup lang="ts">
import { computed } from 'vue'
import { Bar, Line } from 'vue-chartjs'
import {
  BarElement,
  CategoryScale,
  Chart as ChartJS,
  Legend,
  LinearScale,
  LineElement,
  PointElement,
  Tooltip,
} from 'chart.js'

ChartJS.register(CategoryScale, LinearScale, BarElement, LineElement, PointElement, Tooltip, Legend)

export interface ChartSeries {
  key: string
  label: string
  color: string
}

const props = defineProps<{
  chartType: 'bar' | 'line'
  labels: string[]
  series: ChartSeries[]
  // Per-series counts, one entry per label, same order as `labels`.
  values: Record<string, number[]>
}>()

const chartData = computed(() => ({
  labels: props.labels,
  datasets: props.series.map((s) => ({
    label: s.label,
    data: props.values[s.key] ?? props.labels.map(() => 0),
    backgroundColor: props.chartType === 'line' ? `${s.color}33` : s.color,
    borderColor: s.color,
    borderWidth: props.chartType === 'line' ? 2 : 0,
    borderRadius: props.chartType === 'bar' ? 4 : undefined,
    stack: props.chartType === 'bar' ? 'total' : undefined,
    fill: false,
    tension: 0.25,
    pointRadius: props.chartType === 'line' ? 3 : undefined,
    pointBackgroundColor: s.color,
  })),
}))

const chartOptions = computed(() => ({
  responsive: true,
  maintainAspectRatio: false,
  interaction: { mode: 'index' as const, intersect: false },
  scales: {
    x: { stacked: props.chartType === 'bar', grid: { display: false } },
    y: { stacked: props.chartType === 'bar', beginAtZero: true, ticks: { precision: 0 } },
  },
  plugins: {
    legend: { display: props.series.length > 1, position: 'bottom' as const },
  },
}))
</script>

<template>
  <div class="chart-container">
    <p v-if="labels.length === 0" class="text-body-2 text-medium-emphasis pa-4">No data for the selected filters.</p>
    <Bar v-else-if="chartType === 'bar'" :data="chartData" :options="chartOptions" />
    <Line v-else :data="chartData" :options="chartOptions" />
  </div>
</template>

<style scoped>
.chart-container {
  height: 320px;
  position: relative;
}
</style>
