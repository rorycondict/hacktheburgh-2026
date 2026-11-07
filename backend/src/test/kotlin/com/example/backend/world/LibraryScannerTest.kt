package com.example.backend.world

import com.example.model.FurnitureKind
import com.example.model.LibraryDto
import com.example.model.RoomKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryScannerTest {
    private val catalog = """
        [versions]
        ser = "1.9.0"

        [libraries]
        kotlin-serialization-json-jvm = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm", version.ref = "ser" }
        okhttp = "com.squareup.okhttp3:okhttp:4.12.0"
        junit = { group = "org.junit.jupiter", name = "junit-jupiter", version = "5.10.0" }
    """.trimIndent()

    @Test
    fun gradleWithCatalog() {
        val cat = LibraryScanner.parseCatalog(catalog, "gradle/libs.versions.toml")
        assertEquals("libs", cat.name)
        assertEquals(3, cat.libraries.size)
        val script = """
            dependencies {
                implementation(project(":shared"))
                compileOnly(libs.kotlin.serialization.json.jvm)
                testImplementation(libs.junit)
                testImplementation(kotlin("test"))
                implementation("com.google.guava:guava:33.0.0-jre") // a comment
                // implementation("commented:out:1.0")
                runtimeOnly group: 'org.slf4j', name: 'slf4j-simple', version: '2.0.9'
            }
        """.trimIndent()
        val libs = LibraryScanner.merge(LibraryScanner.gradle(script, "build.gradle.kts", listOf("gradle/libs.versions.toml" to cat)))
        val byName = libs.associateBy { it.name }
        assertEquals(setOf("kotlinx-serialization-json-jvm", "junit-jupiter", "kotlin-test", "guava", "slf4j-simple"), byName.keys)
        assertEquals("compile-only", byName.getValue("kotlinx-serialization-json-jvm").scope)
        assertEquals("1.9.0", byName.getValue("kotlinx-serialization-json-jvm").version)
        assertEquals("gradle/libs.versions.toml", byName.getValue("junit-jupiter").declaredIn)
        assertEquals("test", byName.getValue("kotlin-test").scope)
        assertEquals("33.0.0-jre", byName.getValue("guava").version)
        assertEquals("runtime", byName.getValue("slf4j-simple").scope)
    }

    @Test
    fun otherEcosystems() {
        val npm = LibraryScanner.npm("""{"dependencies":{"react":"^18.2.0","@types/node":"20"},"devDependencies":{"vitest":"1.0"}}""", "web/package.json")
        assertEquals(listOf("react" to "main", "@types/node" to "main", "vitest" to "dev"), npm.map { it.name to it.scope })
        assertEquals("@types", npm[1].group)

        val pom = LibraryScanner.maven(
            "<project><properties><jv>5.10</jv></properties><dependencies>" +
                "<dependency><groupId>org.junit</groupId><artifactId>junit</artifactId><version>\${jv}</version><scope>test</scope></dependency>" +
                "</dependencies></project>", "pom.xml",
        )
        assertEquals(LibraryDto("junit", "org.junit", "5.10", "Maven", "test", "pom.xml"), pom.single())

        val req = LibraryScanner.requirements("# deps\nrequests==2.31.0\nnumpy>=1.26 ; python_version>'3.9'\n-r other.txt\n", "requirements.txt")
        assertEquals(listOf("requests" to "2.31.0", "numpy" to ">=1.26"), req.map { it.name to it.version })

        val py = LibraryScanner.pyproject("[project]\nname = \"x\"\ndependencies = [\n  \"httpx>=0.27\",\n  \"rich\",\n]\n", "pyproject.toml")
        assertEquals(listOf("httpx", "rich"), py.map { it.name })

        val pipfile = LibraryScanner.pipfile("[packages]\nflask = \"*\"\n\n[dev-packages]\npytest = \">=7\"\n", "Pipfile")
        assertEquals(listOf(Triple("flask", "", "main"), Triple("pytest", ">=7", "dev")), pipfile.map { Triple(it.name, it.version, it.scope) })

        val setup = LibraryScanner.setupPy("setup(\n    install_requires=[\n        'click>=8',\n    ],\n)\n", "setup.py")
        assertEquals(listOf("click"), setup.map { it.name })

        val cargo = LibraryScanner.cargo(
            "[package]\nname = \"me\"\n\n[dependencies]\nserde = { version = \"1\", features = [\"derive\"] }\nlocal = { path = \"../local\" }\n\n" +
                "[dev-dependencies]\ntempfile = \"3\"\n\n[dependencies.tokio]\nversion = \"1.35\"\n", "Cargo.toml",
        )
        assertEquals(listOf(Triple("serde", "1", "main"), Triple("tempfile", "3", "dev"), Triple("tokio", "1.35", "main")), cargo.map { Triple(it.name, it.version, it.scope) })

        val go = LibraryScanner.goMod("module x\n\nrequire (\n\tgithub.com/spf13/cobra v1.8.0\n\tgolang.org/x/sys v0.15.0 // indirect\n)\n", "go.mod")
        assertEquals(listOf("main", "indirect"), go.map { it.scope })
        assertEquals("github.com/spf13", go[0].group)
    }

    @Test
    fun cAndCppManifests() {
        val cmake = LibraryScanner.cmake(
            """
            cmake_minimum_required(VERSION 3.20)
            find_package(Boost 1.82 REQUIRED COMPONENTS filesystem)
            find_package(Threads REQUIRED)   # a toolchain part, not a library
            include(FetchContent)
            FetchContent_Declare(
              fmt
              GIT_REPOSITORY https://github.com/fmtlib/fmt.git
              GIT_TAG 10.2.1
            )
            CPMAddPackage("gh:nlohmann/json@3.11.3")
            CPMAddPackage(NAME spdlog GITHUB_REPOSITORY gabime/spdlog VERSION 1.13.0)
            pkg_check_modules(GTK REQUIRED gtk+-3.0>=3.24)
            target_link_libraries(app PRIVATE fmt::fmt)
            """.trimIndent(), "CMakeLists.txt",
        )
        assertEquals(
            listOf("Boost" to "1.82", "fmt" to "10.2.1", "json" to "3.11.3", "spdlog" to "1.13.0", "gtk+-3.0" to "3.24"),
            cmake.map { it.name to it.version },
        )
        assertEquals("github.com/fmtlib", cmake[1].group)
        assertEquals("nlohmann", cmake[2].group)
        assertEquals("github.com/gabime", cmake[3].group)

        val vcpkg = LibraryScanner.vcpkg("""{"name":"app","dependencies":["zlib",{"name":"openssl","version>=":"3.0.0"}]}""", "vcpkg.json")
        assertEquals(listOf("zlib" to "", "openssl" to "3.0.0"), vcpkg.map { it.name to it.version })

        val conan = LibraryScanner.conan("[requires]\nzlib/1.3\nboost/1.84.0@\n\n[tool_requires]\ncmake/3.28.1\n\n[generators]\nCMakeDeps\n", "conanfile.txt")
        assertEquals(
            listOf(Triple("zlib", "1.3", "main"), Triple("boost", "1.84.0", "main"), Triple("cmake", "3.28.1", "build")),
            conan.map { Triple(it.name, it.version, it.scope) },
        )
        val conanPy = LibraryScanner.conan(
            "class App(ConanFile):\n    def requirements(self):\n        self.requires(\"fmt/10.2.1\")\n        self.test_requires(\"gtest/1.14.0\")\n", "conanfile.py",
        )
        assertEquals(listOf("fmt" to "main", "gtest" to "test"), conanPy.map { it.name to it.scope })

        val meson = LibraryScanner.meson("glib = dependency('glib-2.0', version: '>=2.70')\nthr = dependency('threads')\n", "meson.build")
        assertEquals(listOf("glib-2.0" to ">=2.70"), meson.map { it.name to it.version })
    }

    @Test
    fun moreEcosystems() {
        val nuget = LibraryScanner.nuget(
            """<Project><ItemGroup>
                 <PackageReference Include="Newtonsoft.Json" Version="13.0.3" />
                 <PackageReference Include="xunit"><Version>2.6.0</Version></PackageReference>
               </ItemGroup></Project>""", "App/App.csproj",
        )
        assertEquals(listOf(Triple("Newtonsoft.Json", "13.0.3", "main"), Triple("xunit", "2.6.0", "test")), nuget.map { Triple(it.name, it.version, it.scope) })

        val gems = LibraryScanner.ruby("source 'https://rubygems.org'\ngem 'rails', '~> 7.1'\ngroup :test do\n  gem 'rspec', require: false\nend\n", "Gemfile")
        assertEquals(listOf(Triple("rails", "~> 7.1", "main"), Triple("rspec", "", "test")), gems.map { Triple(it.name, it.version, it.scope) })

        val composer = LibraryScanner.composer("""{"require":{"php":">=8.1","monolog/monolog":"^3.0"},"require-dev":{"phpunit/phpunit":"^10"}}""", "composer.json")
        assertEquals(listOf("monolog/monolog" to "main", "phpunit/phpunit" to "dev"), composer.map { it.name to it.scope })

        val pub = LibraryScanner.pubspec("name: app\ndependencies:\n  flutter:\n    sdk: flutter\n  http: ^1.1.0\ndev_dependencies:\n  lints: ^3.0.0\n", "pubspec.yaml")
        assertEquals(listOf("http" to "main", "lints" to "dev"), pub.map { it.name to it.scope })

        val swift = LibraryScanner.swift(
            ".package(url: \"https://github.com/apple/swift-argument-parser.git\", from: \"1.3.0\"),\n.package(path: \"../Local\")", "Package.swift",
        )
        assertEquals(listOf("swift-argument-parser" to "1.3.0"), swift.map { it.name to it.version })

        val sbt = LibraryScanner.sbt(
            "libraryDependencies += \"org.typelevel\" %% \"cats-core\" % \"2.10.0\"\nlibraryDependencies += \"org.scalatest\" %% \"scalatest\" % \"3.2.17\" % Test\n", "build.sbt",
        )
        assertEquals(listOf("cats-core" to "main", "scalatest" to "test"), sbt.map { it.name to it.scope })

        val mix = LibraryScanner.mix("defp deps do\n  [\n    {:phoenix, \"~> 1.7\"},\n    {:credo, \"~> 1.7\", only: [:dev, :test]}\n  ]\nend\n", "mix.exs")
        assertEquals(listOf("phoenix" to "main", "credo" to "dev"), mix.map { it.name to it.scope })
    }

    @Test
    fun submodulesVendoredAndLines() {
        val subs = LibraryScanner.gitSubmodules("[submodule \"ext/imgui\"]\n\tpath = ext/imgui\n\turl = https://github.com/ocornut/imgui.git\n", ".gitmodules")
        assertEquals("imgui", subs.single().first.name)
        assertEquals("ext/imgui", subs.single().second)
        assertEquals(1, subs.single().first.line)

        val paths = listOf("third_party/zlib/README", "third_party/zlib/zlib.h", "third_party/stb/stb_image.h", "third_party/notes.txt", "src/main.c")
        val vendored = LibraryScanner.vendored(paths, emptySet(), isGo = false)
        assertEquals(
            listOf("zlib" to "third_party/zlib/README", "stb" to "third_party/stb/stb_image.h"),
            vendored.map { it.second.name to it.second.declaredIn },
        )

        val text = "[dependencies]\nserde = \"1\"\ntokio = \"1.35\"\n"
        val lines = LibraryScanner.withLines(LibraryScanner.cargo(text, "Cargo.toml"), text)
        assertEquals(listOf(2, 3), lines.map { it.line })
    }

    @Test
    fun booksPackIntoBookcases() {
        val few = (0 until 5).map { LibraryDto("lib$it", "g$it", "1", "Maven", "main", "build.gradle.kts") }
        assertEquals(1, LibraryShelves.shelve(few, 12).size, "a few books share one case")
        val many = (0 until 100).map { LibraryDto("lib$it", "group${it % 20}", "1", "Maven", "main", "build.gradle.kts") }
        val cases = LibraryShelves.shelve(many, 12)
        assertTrue(cases.size <= 12)
        assertTrue(cases.all { it.libraries.size <= LibraryDto.BOOKS_PER_CASE })
        assertEquals(100, cases.sumOf { it.libraries.size }, "every library is a book in some case")
        val huge = (0 until 1000).map { LibraryDto("lib$it", "", "1", "npm", "main", "package.json") }
        val full = LibraryShelves.shelve(huge, 12)
        assertEquals(12, full.size)
        assertEquals(1000, full.sumOf { it.libraries.size })
    }

    @Test
    fun layoutHasDependencyLibraryRoom() {
        val files = listOf(FileInfo("build.gradle.kts", 20, "Kotlin Script", 0), FileInfo("src/Main.kt", 30, "Kotlin", 0))
        val libs = (0 until 60).map { LibraryDto("lib$it", "g${it % 4}", "1.$it", "Maven", "main", "build.gradle.kts") }
        val w = WorldLayout.build(files, "", generatedAtMillis = 1, libraries = libs)
        val room = w.rooms.single { it.kind == RoomKind.DEPENDENCIES }
        assertEquals(WorldLayout.LIBRARIES_ID, room.id)
        assertEquals(1, room.level)
        val cases = w.furniture.filter { it.roomId == room.id && it.libraries.isNotEmpty() }
        assertTrue(cases.isNotEmpty() && cases.all { it.kind == FurnitureKind.BOOKCASE && it.w == 2 && it.path.isEmpty() && !it.decor })
        assertEquals(60, cases.sumOf { it.libraries.size })
        assertEquals(libs.size, w.libraries.size)
        // No libraries, no room.
        assertTrue(WorldLayout.build(files, "", generatedAtMillis = 1).rooms.none { it.kind == RoomKind.DEPENDENCIES })
    }
}
