targetScope = 'resourceGroup'

@description('Globally unique short lowercase name prefix; storage name derives from the resource group ID.')
@minLength(3)
@maxLength(18)
param namePrefix string = 'verejnydohlad'
param location string = 'westeurope'
param staticWebAppLocation string = 'westeurope'
param openAiResourceName string
param openAiResourceGroup string = resourceGroup().name
param openAiSubscriptionId string = subscription().subscriptionId
param openAiEndpoint string
param openAiDeployment string = 'gpt-4.1-mini'
@description('Existing stronger model deployment in the same OpenAI account; provision it separately before enabling automatic publication.')
param openAiEditorDeployment string = 'gpt-4.1'
param autoPublishEnabled bool = false
@description('Production requires azure. mock is strictly for offline development.')
@allowed(['azure'])
param aiMode string = 'azure'
@description('Initial private concept, targeted official-source research and one writer revision before verification.')
param researchFlowEnabled bool = true
@description('Existing Application Insights connection string. No additional monitoring account is created by default.')
@secure()
param applicationInsightsConnectionString string = ''
@description('Optional required monitoring component, using an already existing Log Analytics workspace.')
param createApplicationInsights bool = false
param existingLogAnalyticsWorkspaceId string = ''
@description('Maximum daily concept attempts, including rejected or interrupted drafts.')
@maxValue(6)
@minValue(0)
param maxArticlesPerDay int = 6
@maxValue(3)
@minValue(0)
param maxPublishedArticlesPerDay int = 3
@maxValue(50)
@minValue(1)
param maxPressReleasesPerRun int = 40
@maxValue(100)
@minValue(0)
param maxAiCallsPerDay int = 40
param maxTokensPerArticle int = 2200
param aiRequestTimeout int = 45
param aiInputUsdPerMillion string = '0'
param aiOutputUsdPerMillion string = '0'
param editorInputUsdPerMillion string = '0'
param editorOutputUsdPerMillion string = '0'
param dailySchedule string = '0 0 3 * * *'

var suffix = uniqueString(resourceGroup().id, namePrefix)
var storageName = '${take(replace(toLower(namePrefix), '-', ''), 11)}${suffix}'
var appName = '${namePrefix}-${suffix}'
var blobRole = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', 'ba92f5b4-2d11-453d-a403-e96b0029c9fe')
var tableRole = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '0a9a7e1f-b9d0-4cc4-a60d-0319b160aaa3')
var aiRole = subscriptionResourceId(openAiSubscriptionId, 'Microsoft.Authorization/roleDefinitions', '5e0bd9bd-7b93-4f28-af87-19fc36ad61bd')

resource storage 'Microsoft.Storage/storageAccounts@2023-05-01' = {
  name: storageName
  location: location
  sku: { name: 'Standard_LRS' }
  kind: 'StorageV2'
  properties: {
    minimumTlsVersion: 'TLS1_2'
    supportsHttpsTrafficOnly: true
    allowBlobPublicAccess: false
    // Windows Consumption content share/runtime uses a key; application clients use managed identity.
    allowSharedKeyAccess: true
  }
}
resource blobService 'Microsoft.Storage/storageAccounts/blobServices@2023-05-01' = {
  parent: storage
  name: 'default'
  properties: { deleteRetentionPolicy: { enabled: true, days: 7 } }
}
resource containers 'Microsoft.Storage/storageAccounts/blobServices/containers@2023-05-01' = [for container in ['sources', 'articles', 'images']: {
  parent: blobService
  name: container
  properties: { publicAccess: 'None' }
}]
resource tableService 'Microsoft.Storage/storageAccounts/tableServices@2023-05-01' = {
  parent: storage
  name: 'default'
}
resource tables 'Microsoft.Storage/storageAccounts/tableServices/tables@2023-05-01' = [for table in ['Sources', 'PressReleases', 'Articles']: {
  parent: tableService
  name: table
}]
resource plan 'Microsoft.Web/serverfarms@2023-12-01' = {
  name: '${appName}-plan'
  location: location
  sku: { name: 'Y1', tier: 'Dynamic' }
  properties: { reserved: false }
}
resource staticApp 'Microsoft.Web/staticSites@2023-12-01' = {
  name: '${appName}-web'
  location: staticWebAppLocation
  sku: { name: 'Free', tier: 'Free' }
  properties: { allowConfigFileUpdates: true }
}
resource insights 'Microsoft.Insights/components@2020-02-02' = if (createApplicationInsights) {
  name: '${appName}-insights'
  location: location
  kind: 'web'
  properties: {
    Application_Type: 'web'
    WorkspaceResourceId: existingLogAnalyticsWorkspaceId
    IngestionMode: 'LogAnalytics'
    publicNetworkAccessForIngestion: 'Enabled'
    publicNetworkAccessForQuery: 'Enabled'
  }
}
var storageConnection = 'DefaultEndpointsProtocol=https;AccountName=${storage.name};AccountKey=${storage.listKeys().keys[0].value};EndpointSuffix=${environment().suffixes.storage}'
resource functionApp 'Microsoft.Web/sites@2023-12-01' = {
  name: appName
  location: location
  kind: 'functionapp'
  identity: { type: 'SystemAssigned' }
  properties: {
    serverFarmId: plan.id
    httpsOnly: true
    siteConfig: {
      javaVersion: '21'
      ftpsState: 'Disabled'
      minTlsVersion: '1.2'
      cors: { allowedOrigins: ['https://${staticApp.properties.defaultHostname}'], supportCredentials: false }
      appSettings: [
        { name: 'AzureWebJobsStorage', value: storageConnection }
        { name: 'WEBSITE_CONTENTAZUREFILECONNECTIONSTRING', value: storageConnection }
        { name: 'WEBSITE_CONTENTSHARE', value: toLower(appName) }
        { name: 'WEBSITE_RUN_FROM_PACKAGE', value: '1' }
        { name: 'FUNCTIONS_EXTENSION_VERSION', value: '~4' }
        { name: 'FUNCTIONS_WORKER_RUNTIME', value: 'java' }
        { name: 'STORAGE_ACCOUNT_NAME', value: storage.name }
        { name: 'AI_MODE', value: aiMode }
          { name: 'RESEARCH_FLOW_ENABLED', value: string(researchFlowEnabled) }
        { name: 'AZURE_OPENAI_ENDPOINT', value: openAiEndpoint }
        { name: 'AZURE_OPENAI_DEPLOYMENT', value: openAiDeployment }
        { name: 'AZURE_OPENAI_EDITOR_DEPLOYMENT', value: openAiEditorDeployment }
        { name: 'AUTO_PUBLISH_ENABLED', value: string(autoPublishEnabled) }
        { name: 'DAILY_SCHEDULE', value: dailySchedule }
        { name: 'MAX_ARTICLES_PER_DAY', value: string(maxArticlesPerDay) }
        { name: 'MAX_PUBLISHED_ARTICLES_PER_DAY', value: string(maxPublishedArticlesPerDay) }
        { name: 'MAX_PRESS_RELEASES_PER_RUN', value: string(maxPressReleasesPerRun) }
        { name: 'MAX_AI_CALLS_PER_DAY', value: string(maxAiCallsPerDay) }
        { name: 'MAX_TOKENS_PER_ARTICLE', value: string(maxTokensPerArticle) }
        { name: 'AI_REQUEST_TIMEOUT', value: string(aiRequestTimeout) }
        { name: 'AI_INPUT_USD_PER_MILLION', value: aiInputUsdPerMillion }
        { name: 'AI_OUTPUT_USD_PER_MILLION', value: aiOutputUsdPerMillion }
        { name: 'EDITOR_INPUT_USD_PER_MILLION', value: editorInputUsdPerMillion }
        { name: 'EDITOR_OUTPUT_USD_PER_MILLION', value: editorOutputUsdPerMillion }
        { name: 'ALLOWED_ORIGIN', value: 'https://${staticApp.properties.defaultHostname}' }
        { name: 'APPLICATIONINSIGHTS_CONNECTION_STRING', value: createApplicationInsights ? insights!.properties.ConnectionString : applicationInsightsConnectionString }
      ]
    }
  }
}
resource blobAccess 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(storage.id, functionApp.id, blobRole)
  scope: storage
  properties: { roleDefinitionId: blobRole, principalId: functionApp.identity.principalId, principalType: 'ServicePrincipal' }
}
resource tableAccess 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(storage.id, functionApp.id, tableRole)
  scope: storage
  properties: { roleDefinitionId: tableRole, principalId: functionApp.identity.principalId, principalType: 'ServicePrincipal' }
}
resource existingAi 'Microsoft.CognitiveServices/accounts@2024-10-01' existing = {
  name: openAiResourceName
  scope: resourceGroup(openAiSubscriptionId, openAiResourceGroup)
}
module aiAccess './openai-access.bicep' = {
  name: 'openai-access'
  scope: resourceGroup(openAiSubscriptionId, openAiResourceGroup)
  params: {
    resourceName: existingAi.name
    principalId: functionApp.identity.principalId
    principalResourceId: functionApp.id
    roleId: aiRole
  }
}
output functionAppName string = functionApp.name
output functionApiUrl string = 'https://${functionApp.properties.defaultHostName}/api'
output storageAccountName string = storage.name
output staticWebAppName string = staticApp.name
output staticWebAppUrl string = 'https://${staticApp.properties.defaultHostname}'
