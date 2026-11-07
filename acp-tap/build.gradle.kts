import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Plain JVM app: no dependencies beyond kotlin-stdlib. Runs on the IDE's JBR (21+).
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjdk-release=21")
    }
}

dependencies {
    implementation(kotlin("stdlib"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

val runtimeClasspath = configurations.runtimeClasspath

tasks.jar {
    archiveFileName.set("acp-tap.jar")
    manifest {
        attributes("Main-Class" to "com.example.tap.MainKt")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(runtimeClasspath.map { cp -> cp.map { if (it.isDirectory) it else zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**", "module-info.class")
    }
}

// Consumed by the root project's prepareSandbox tasks.
val tapJar = configurations.create("tapJar") {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add("tapJar", tasks.jar)
}
