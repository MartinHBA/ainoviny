// Optional, separately approved provisioning. Prefer an existing resource and deployment.
param resourceName string
param location string
param deploymentName string = 'gpt-4.1-mini'
param modelName string = 'gpt-4.1-mini'
param modelVersion string = '2025-04-14'
@description('Confirm supported model SKU, region and quota before provisioning.')
param deploymentSku string = 'GlobalStandard'
@minValue(1)
param capacity int = 1
resource account 'Microsoft.CognitiveServices/accounts@2024-10-01' = {
  name: resourceName
  location: location
  sku: { name: 'S0' }
  kind: 'OpenAI'
  properties: {
    customSubDomainName: resourceName
    disableLocalAuth: true
    publicNetworkAccess: 'Enabled'
    // Explicit ACLs are required by the account provisioning API. The Consumption
    // app uses the public endpoint with Entra ID/RBAC; it has no private network.
    networkAcls: {
      defaultAction: 'Allow'
      ipRules: []
      virtualNetworkRules: []
    }
  }
}
resource deployment 'Microsoft.CognitiveServices/accounts/deployments@2024-10-01' = {
  parent: account
  name: deploymentName
  sku: { name: deploymentSku, capacity: capacity }
  properties: { model: { format: 'OpenAI', name: modelName, version: modelVersion }, versionUpgradeOption: 'NoAutoUpgrade' }
}
output endpoint string = account.properties.endpoint
