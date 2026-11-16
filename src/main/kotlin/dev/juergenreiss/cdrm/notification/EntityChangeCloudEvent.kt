// Copyright 2026 Dr. Juergen A. Reiss
// SPDX-License-Identifier: Apache-2.0

package dev.juergenreiss.cdrm.notification

// CloudEvents 1.0 structured-mode envelope (https://cloudevents.io) — the JSON shape
// EntityChangeKafkaPublisher sends as a Kafka record's value, per the CloudEvents Kafka
// Protocol Binding's structured content mode. Same shape as ReleaseHistoryCloudEvent, but
// with `data` left as Any so it can carry whichever Response DTO (Cluster/Stage/Product/
// Workload/Config) the occurrence being reported actually produced.
data class EntityChangeCloudEvent(
    val specversion: String = "1.0",
    val id: String,
    val source: String,
    val type: String,
    val time: String,
    val datacontenttype: String = "application/json",
    val subject: String,
    val data: Any,
)
