// Optional, separately approved model deployment in an existing OpenAI account. No new account.
param resourceName string
param deploymentName string = 'gpt-4.1'
param modelName string = 'gpt-4.1'
param modelVersion string = '2025-04-14'
param deploymentSku string = 'GlobalStandard'
@minValue(1)
param capacity int = 100
@description('Optional rate-limit correction for the existing writer deployment. Capacity allocates TPM quota; this is not a provisioned-throughput SKU.')
param configureWriterCapacity bool = false
param writerDeploymentName string = 'gpt-4.1-mini'
param writerModelName string = 'gpt-4.1-mini'
param writerModelVersion string = '2025-04-14'
param writerRaiPolicyName string = 'Microsoft.DefaultV2'
@minValue(1)
param writerCapacity int = 300
resource account 'Microsoft.CognitiveServices/accounts@2024-10-01' existing = {
  name: resourceName
}
resource deployment 'Microsoft.CognitiveServices/accounts/deployments@2024-10-01' = {
  parent: account
  // Cognitive Services rejects concurrent writes to sibling deployments in one account.
  dependsOn: [writerDeployment]
  name: deploymentName
  sku: { name: deploymentSku, capacity: capacity }
  properties: { model: { format: 'OpenAI', name: modelName, version: modelVersion }, versionUpgradeOption: 'NoAutoUpgrade' }
}
output editorDeploymentName string = deployment.name
resource writerDeployment 'Microsoft.CognitiveServices/accounts/deployments@2024-10-01' = if (configureWriterCapacity) {
  parent: account
  name: writerDeploymentName
  sku: { name: 'GlobalStandard', capacity: writerCapacity }
  properties: { model: { format: 'OpenAI', name: writerModelName, version: writerModelVersion }, versionUpgradeOption: 'NoAutoUpgrade', raiPolicyName: writerRaiPolicyName }
}
