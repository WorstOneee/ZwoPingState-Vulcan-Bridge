version = "1.1.0"

dependencies {
    // Compila contra o JAR da ponte (interface PingStateProvider). compileOnly:
    // a interface NÃO é embutida no JAR do ZwoPingState; em tempo de execução ela
    // vem do próprio ZwoVulcanBridge instalado no servidor.
    compileOnly(project(":zwo-vulcan-bridge"))
    testImplementation(project(":zwo-vulcan-bridge"))
    testImplementation("io.papermc.paper:paper-api:${property("paperApiVersion")}")
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("ZwoPingState")
}
