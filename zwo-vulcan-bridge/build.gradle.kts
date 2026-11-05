version = "3.0.0"

// Cópia da API pública do ModPanel 3.1.0 (dev.zwo.modpanel.api), só para compilar.
// NÃO vai para o JAR: em tempo de execução as classes vêm do próprio ModPanel.
val sourceSets = the<SourceSetContainer>()
val modpanelApi = sourceSets.create("modpanelApi")

dependencies {
    "modpanelApiCompileOnly"("io.papermc.paper:paper-api:${property("paperApiVersion")}")
    "compileOnly"(modpanelApi.output)
    "testImplementation"(modpanelApi.output)
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("ZwoVulcanBridge")
}
