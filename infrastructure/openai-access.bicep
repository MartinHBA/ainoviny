param resourceName string
param principalId string
@description('Resource ID of the Function App; available before its managed identity is created.')
param principalResourceId string
param roleId string
resource account 'Microsoft.CognitiveServices/accounts@2024-10-01' existing = { name: resourceName }
resource access 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  // Seed the assignment name with the known app resource ID so what-if can
  // identify this resource before the identity principalId exists.
  name: guid(account.id, principalResourceId, roleId)
  scope: account
  properties: { principalId: principalId, principalType: 'ServicePrincipal', roleDefinitionId: roleId }
}
