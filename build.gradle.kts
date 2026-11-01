plugins {
	java
	id("org.springframework.boot") version "4.1.2"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.kamerzing"
version = "0.1.0-SNAPSHOT"

java {
	toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.2.0")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

springBoot { buildInfo() }

tasks.withType<JavaCompile> { options.encoding = "UTF-8" }
tasks.withType<Test> {
	maxHeapSize = "384m"
	maxParallelForks = 1
}

tasks.test {
	description = "Runs backend HTTP and security tests without Docker, Node.js or an LLM."
	useJUnitPlatform { excludeTags("integration") }
}

val integrationTest = tasks.register<Test>("integrationTest") {
	group = "verification"
	description = "Checks the application and Flyway on isolated PostgreSQL; requires Docker."
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath
	useJUnitPlatform { includeTags("integration") }
	shouldRunAfter(tasks.test)
}

fun Exec.npmCommand(vararg arguments: String) {
	workingDir("web")
	if (System.getProperty("os.name").startsWith("Windows")) {
		commandLine("cmd", "/c", "npm.cmd", *arguments)
	} else commandLine("npm", *arguments)
}

val webInstall = tasks.register<Exec>("webInstall") {
	inputs.files("web/package.json", "web/package-lock.json")
	outputs.dir("web/node_modules")
	npmCommand("ci", "--no-fund", "--no-audit")
}

val webBuild = tasks.register<Exec>("webBuild") {
	dependsOn(webInstall)
	inputs.files(fileTree("web/src"), "web/index.html", "web/tsconfig.json", "web/vite.config.ts", "web/package-lock.json")
	outputs.dir("web/dist")
	npmCommand("run", "build")
}

val webTest = tasks.register<Exec>("webTest") {
	group = "verification"
	dependsOn(webInstall)
	npmCommand("test")
}

tasks.check { dependsOn(integrationTest, webTest) }

// Packaging includes the UI; compilation, backend tests and bootRun remain independent of npm.
tasks.bootJar {
	dependsOn(webBuild)
	archiveFileName = "payment-intelligence-platform.jar"
	from("web/dist") { into("BOOT-INF/classes/static") }
}

tasks.bootRun { maxHeapSize = "384m" }
tasks.jar { enabled = false }
