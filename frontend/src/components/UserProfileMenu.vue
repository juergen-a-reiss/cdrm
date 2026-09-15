<!--
  Required Notice: Copyright Dr. Juergen A. Reiss
  Licensed under the terms in the LICENSE file at the repository root.
-->

<script setup lang="ts">
import { computed } from 'vue'
import { userProfile } from '../auth/userProfile'
import { currentRoles } from '../auth/roles'
import { supportedTimezones, useDateTimePreferences } from '../composables/useDateTimePreferences'
import { formatDateTime } from '../utils/formatDateTime'

// Matches the README's "RBAC Role Based Access Control" section — kept here rather than
// fetched from anywhere so this still works the instant the token is decoded, no request
// round trip needed just to explain what a role means.
const ROLE_DESCRIPTIONS: Record<string, string> = {
  'cdrm-devops': 'Can manage clusters, stages, and workloads. Exempt from every ReBAC restriction below — sees and can act on everything regardless of cdrm-products/cdrm-workloads/cdrm-release-actions.',
  'cdrm-productowner': 'Can manage products and the full release lifecycle (create, promote, roll back, redeploy, delete). Can view everything else.',
  'cdrm-developer': 'Can create releases and deploy them to the first stage. Can create and edit workloads. Can view everything else.',
  'cdrm-manager': 'Can view data. No create/edit/deploy permission unless separately granted by cdrm-release-actions below.',
}

const isDevops = computed(() => currentRoles.value.includes('cdrm-devops'))

const dateTimePrefs = useDateTimePreferences()
const timezoneOptions = supportedTimezones()
// Recomputes (and so re-renders) whenever dateTimePrefs changes, since formatDateTime()
// reads it internally — a live "here's what that looks like" as the viewer edits their
// own timezone/format choice below, not a ticking clock.
const dateTimePreview = computed(() => formatDateTime(new Date().toISOString()))
</script>

<template>
  <v-menu location="bottom end">
    <template #activator="{ props }">
      <v-btn v-bind="props" variant="text" class="text-none mr-2" append-icon="mdi-chevron-down">
        {{ userProfile.username ?? 'Account' }}
      </v-btn>
    </template>

    <v-card min-width="420" max-width="520" class="pa-2">
      <v-card-text>
        <div class="text-subtitle-2 mb-2">Identity</div>
        <v-table density="compact" class="mb-4">
          <tbody>
            <tr>
              <td class="font-weight-medium" style="width: 120px">Username</td>
              <td>{{ userProfile.username ?? '—' }}</td>
            </tr>
            <tr>
              <td class="font-weight-medium">First name</td>
              <td>{{ userProfile.firstName ?? '—' }}</td>
            </tr>
            <tr>
              <td class="font-weight-medium">Last name</td>
              <td>{{ userProfile.lastName ?? '—' }}</td>
            </tr>
            <tr>
              <td class="font-weight-medium">Email</td>
              <td>{{ userProfile.email ?? '—' }}</td>
            </tr>
          </tbody>
        </v-table>

        <div class="text-subtitle-2 mb-2">Date &amp; Time Display</div>
        <!-- v-menu closes on any click inside it by default (close-on-content-click) —
             never mattered while the rest of this card was read-only display, but would
             close the whole menu on every single radio/field click here. Stopping
             propagation keeps that default for the rest of the card while this section
             stays open through as many changes as the user wants to make. -->
        <div class="mb-4" @click.stop>
          <div class="text-body-2 font-weight-medium mb-1">Timezone</div>
          <v-radio-group v-model="dateTimePrefs.timezoneMode" density="compact" hide-details inline class="mb-1">
            <v-radio label="Browser default" value="browser" />
            <v-radio label="Select a timezone" value="custom" />
          </v-radio-group>
          <v-autocomplete
            v-if="dateTimePrefs.timezoneMode === 'custom'"
            v-model="dateTimePrefs.timezone"
            :items="timezoneOptions"
            density="compact"
            variant="outlined"
            hide-details
            class="mb-2"
          />

          <div class="text-body-2 font-weight-medium mb-1 mt-2">Format</div>
          <v-radio-group v-model="dateTimePrefs.formatMode" density="compact" hide-details inline class="mb-1">
            <v-radio label="Browser default" value="browser" />
            <v-radio label="Enter a format" value="custom" />
          </v-radio-group>
          <v-text-field
            v-if="dateTimePrefs.formatMode === 'custom'"
            v-model="dateTimePrefs.format"
            density="compact"
            variant="outlined"
            hide-details
            class="mb-1"
          />
          <p v-if="dateTimePrefs.formatMode === 'custom'" class="text-caption text-medium-emphasis mb-0">
            Tokens: YYYY, YY, MM, DD, HH (24h), hh (12h), mm, ss, SSS (ms), TZ (timezone). Anything else is copied
            through as-is.
          </p>

          <v-alert type="info" variant="tonal" density="compact" class="mt-2">
            Preview: {{ dateTimePreview }}
          </v-alert>
        </div>

        <div class="text-subtitle-2 mb-2">Roles</div>
        <div v-if="userProfile.roles.length" class="d-flex flex-wrap ga-1 mb-2">
          <v-chip v-for="role in userProfile.roles" :key="role" size="small" variant="flat" color="primary">
            {{ role }}
          </v-chip>
        </div>
        <p v-else class="text-body-2 text-medium-emphasis mb-2">No cdrm-* role assigned.</p>
        <ul class="text-body-2 text-medium-emphasis mb-4 pl-4">
          <li v-for="role in userProfile.roles" :key="role" class="mb-1">
            <strong>{{ role }}</strong> — {{ ROLE_DESCRIPTIONS[role] ?? 'No description available for this role.' }}
          </li>
        </ul>

        <div class="text-subtitle-2 mb-2">Access restrictions (ReBAC)</div>
        <v-alert v-if="isDevops" type="info" variant="tonal" density="compact" class="mb-3">
          You have the cdrm-devops role — none of the restrictions below apply to you, regardless of what they're
          set to. They're still shown here for reference.
        </v-alert>

        <div class="mb-3">
          <div class="mb-1"><strong>cdrm-products</strong>: {{ userProfile.allowedProducts?.join(', ') ?? '(not set)' }}</div>
          <p class="text-body-2 text-medium-emphasis mb-0">
            <template v-if="userProfile.allowedProducts">
              You can only see and manage these products — this also restricts which of their workloads, releases,
              and audit log entries you can see.
            </template>
            <template v-else> Not set — no restriction: you can see every product. </template>
          </p>
        </div>

        <div class="mb-3">
          <div class="mb-1"><strong>cdrm-workloads</strong>: {{ userProfile.allowedWorkloads?.join(', ') ?? '(not set)' }}</div>
          <p class="text-body-2 text-medium-emphasis mb-0">
            <template v-if="userProfile.allowedWorkloads">
              You can only see and manage these workloads (still limited by cdrm-products above, if also set) —
              this also restricts which releases and audit log entries you can see.
            </template>
            <template v-else> Not set — no restriction: you can see every workload within whatever products you're allowed to see. </template>
          </p>
        </div>

        <div>
          <div class="mb-1">
            <strong>cdrm-release-actions</strong>: {{ userProfile.releaseActionsRaw?.join('; ') ?? '(not set)' }}
          </div>
          <template v-if="userProfile.releaseActions && userProfile.releaseActions.length">
            <p class="text-body-2 text-medium-emphasis mb-1">
              Grants (or restricts) these specific actions at these stages — overriding your role's own default for
              exactly these (action, stage) pairs; every other stage falls back to your role's default:
            </p>
            <ul class="text-body-2 text-medium-emphasis pl-4 mb-0">
              <li v-for="grant in userProfile.releaseActions" :key="grant.action">
                <strong>{{ grant.action }}</strong> — allowed at: {{ grant.stages.join(', ') || '(no stages listed)' }}
              </li>
            </ul>
          </template>
          <p v-else class="text-body-2 text-medium-emphasis mb-0">
            Not set — no override: your role's own default permissions apply for promote/rollback/redeploy/delete/edit.
          </p>
        </div>
      </v-card-text>
    </v-card>
  </v-menu>
</template>
