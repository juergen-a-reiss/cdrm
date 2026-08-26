● Code review(review · 7 findings)
src/main/kotlin/dev/juergenreiss/cdrm/product/ProductService.kt
●  95 [correctness]    updateStageCrons() builds its existing-row lookup once before the loop, so duplicate stageIds in a single request create duplicate ProductStage rows instead of upserting one.
● 109 [correctness]    delete() removes a Product without deleting its ProductStage rows or checking for Workloads that still reference productId.
src/main/kotlin/dev/juergenreiss/cdrm/stage/StageService.kt
●  65 [correctness]    delete() removes a Stage without checking whether it's still referenced by Release.currentStageId, ReleaseHistory, WorkloadStage, or ProductStage.
src/main/kotlin/dev/juergenreiss/cdrm/workload/WorkloadService.kt
● 112 [correctness]    delete() removes a Workload without checking whether an existing Release still references it via workloadId.
src/main/kotlin/dev/juergenreiss/cdrm/config/SecurityConfig.kt
●  46 [correctness]    The release-promote endpoint is authorized with hasRole("cdrm-productowner") only, excluding cdrm-admin, unlike every other write rule in this file.
src/main/kotlin/dev/juergenreiss/cdrm/release/ReleaseService.kt
● 207 [efficiency]     orderedStagesFor() does a full stage-table scan, sort, and in-memory filter on every call, and it's invoked once per release inside findAll()'s toResponse() mapping — an N+1 query pattern on the release listing endpoint.
● 205 [simplification] The logic to resolve a workload's linked stages in order is independently re-implemented in ReleaseService.orderedStagesFor and in WorkloadService.toResponse instead of sharing a helper.

The review is done — 7 findings, mostly correctness bugs around missing referential-integrity checks and one security-config inconsistency. Top priority:

1. ProductService.updateStageCrons (line 95) — duplicate stageIds in a request create duplicate ProductStage rows, later causing 500s in release creation/promotion.
2. StageService.delete (line 65) — no check for releases still on that stage; deleting it breaks GET /releases.
3. WorkloadService.delete (line 112) — no check for releases referencing that workload; breaks promote.
4. SecurityConfig (line 46) — POST /releases/{id}/promote only allows cdrm-productowner, excluding cdrm-admin unlike every other write rule.
5. ProductService.delete (line 109) — orphans ProductStage rows and dangling Workload.productId.
6. ReleaseService.orderedStagesFor (line 207) — N+1 query pattern on GET /releases.
7. ReleaseService/WorkloadService (line 205) — stage-ordering logic duplicated in two places.