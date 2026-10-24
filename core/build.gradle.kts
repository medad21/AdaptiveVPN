plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(17) }
tasks.test { useJUnitPlatform() }
dependencies { testImplementation(kotlin("test")); testRuntimeOnly("org.junit.platform:junit-platform-launcher") }
