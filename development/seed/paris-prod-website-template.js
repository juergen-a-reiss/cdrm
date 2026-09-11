// GitOps "template" mode example — see K8sNamespaceGitopsConfig.templateScript
// (dev.juergenreiss.cdrm.gitops.GitOpsTemplateEngine). Wired to the paris-prod-website
// namespace in data.yaml as an alternative to the SIMPLE fileExpression/yamlExpression
// config the other namespaces here use.
//
// Available context variables (all strings): gitRepoName, clusterName, namespace,
// productName, stageName, workloadName, releaseBinary, targetStage.
//
// Must return an array of {gitBranch, filePath, yamlKeyPath, value} objects, one per
// edit to make (all four required, all strings). Edits sharing a filePath are applied
// together in one load/modify/write pass; edits sharing a gitBranch land in one commit
// regardless of how many files they touch.
//
// This example edits the workload's own manifest twice (the image, plus a descriptive
// label — two edits, one file) and also appends to a namespace-wide release log (a
// different file, same commit) so every deploy leaves a record of what shipped and to
// what product. Every edit's value is always written as a YAML string — a yamlKeyPath
// must target a string-typed field (like an image tag or a label), never a field
// Kubernetes expects to be numeric or boolean (e.g. spec.replicas as int32), or the
// resulting manifest fails to apply.
var manifestFile = 'environments/' + namespace + '/' + workloadName + '.yaml';
var releaseLogFile = 'environments/' + namespace + '/release-log.yaml';

return [
    { gitBranch: targetStage, filePath: manifestFile, yamlKeyPath: 'spec.template.spec.containers[0].image', value: releaseBinary },
    { gitBranch: targetStage, filePath: manifestFile, yamlKeyPath: 'metadata.labels.app', value: workloadName + '--' + productName },
    { gitBranch: targetStage, filePath: releaseLogFile, yamlKeyPath: 'lastRelease', value: productName + '/' + workloadName + ' -> ' + releaseBinary },
];
