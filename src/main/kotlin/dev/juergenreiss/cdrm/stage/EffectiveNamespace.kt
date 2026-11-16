// Copyright 2026 Dr. Juergen A. Reiss
// SPDX-License-Identifier: Apache-2.0

package dev.juergenreiss.cdrm.stage

import dev.juergenreiss.cdrm.workload.Workload

// The namespace a workload's Kubernetes resources actually live in at this stage —
// stage.namespacePrefix (only needed when several stages share one cluster/context and
// would otherwise collide) prepended to the workload's own kubernetesNameSpace. Null
// when the workload has no configured namespace; the caller decides what that means
// (usually "not deployable/checkable here").
fun Stage.effectiveNamespaceFor(workload: Workload): String? =
    workload.kubernetesNameSpace?.let { (namespacePrefix ?: "") + it }
