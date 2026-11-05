import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

val paperApiVersion: String by project
val junitVersion: String by project

subprojects {
    apply(plugin = "java")

    group = "dev.zwo"

    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    dependencies {
        "compileOnly"("io.papermc.paper:paper-api:$paperApiVersion")
        "testImplementation"(platform("org.junit:junit-bom:$junitVersion"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
        options.compilerArgs.addAll(listOf("-Xlint:unchecked", "-Xlint:deprecation"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events(TestLogEvent.PASSED, TestLogEvent.SKIPPED, TestLogEvent.FAILED)
            exceptionFormat = TestExceptionFormat.FULL
        }
    }

    tasks.named<ProcessResources>("processResources") {
        val props = mapOf("version" to project.version.toString())
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") { expand(props) }
    }
}

// Copia os dois JARs finais para build/release, para facilitar a entrega.
tasks.register<Copy>("release") {
    group = "build"
    description = "Compila, testa e copia os JARs finais para build/release."
    dependsOn(subprojects.map { it.tasks.named("build") })
    from(subprojects.map { it.layout.buildDirectory.dir("libs") })
    include("*.jar")
    into(layout.buildDirectory.dir("release"))
}
