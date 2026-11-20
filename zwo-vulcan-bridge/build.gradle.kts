version = "3.2.1"

// Cópia da API pública do ModPanel 3.1.0 (dev.zwo.modpanel.api), só para compilar.
// NÃO vai para o JAR: em tempo de execução as classes vêm do próprio ModPanel.
val sourceSets = the<SourceSetContainer>()
val modpanelApi = sourceSets.create("modpanelApi")

dependencies {
    "modpanelApiCompileOnly"("io.papermc.paper:paper-api:${property("paperApiVersion")}")
    "compileOnly"(modpanelApi.output)
    "testImplementation"(modpanelApi.output)
    // Só para o teste do formato da mensagem ao proxy (Adventure).
    "testImplementation"("io.papermc.paper:paper-api:${property("paperApiVersion")}")
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("ZwoVulcanBridge")
}
