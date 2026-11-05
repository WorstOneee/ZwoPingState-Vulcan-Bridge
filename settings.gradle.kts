// ZwoPingState + ZwoVulcanBridge — build multi-módulo.
// Gera dois JARs independentes:
//   - ZwoVulcanBridge-<versão>.jar  (ponte Vulcan -> SerpensModPanel + API PingStateProvider)
//   - ZwoPingState-<versão>.jar     (provedor de qualidade de conexão)
// O ZwoPingState compila contra o JAR da ponte (compileOnly) e NÃO embute a interface.

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "papermc"
        }
    }
}

rootProject.name = "ZwoPingState"

include("zwo-vulcan-bridge", "zwo-ping-state")
