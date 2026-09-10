plugins { id("org.jetbrains.kotlin.jvm"); `java-library`; `maven-publish` }
kotlin { jvmToolchain(17) }
java { withSourcesJar() }
tasks.register<JavaExec>("contractTest") {
    dependsOn("testClasses")
    classpath=sourceSets["test"].runtimeClasspath
    mainClass.set("com.sirpaul.stablear.core.CoreContractTestKt")
}
tasks.named("check") { dependsOn("contractTest") }
publishing { publications { create<MavenPublication>("core") { from(components["java"]) } } }
