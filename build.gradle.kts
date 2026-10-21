// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.protobuf) apply false
    // Dependency-update report: ./gradlew dependencyUpdates lists what is outdated
    // across the version catalog. Report-only; it never changes a version by itself.
    alias(libs.plugins.versions)
}

// Repairs corrupted translations contributed via Weblate where the <xliff:g> markup
// was escaped (e.g. "&lt;xliff:g ...&gt;%1$s&lt;/xliff:g&gt;"). When escaped, Android
// renders the markup literally in the UI instead of only the placeholder. This task
// unwraps such occurrences back to their inner placeholder (e.g. "%1$s"). See issue #477.
tasks.register("fixStrings") {
    group = "translation"
    description = "Fixes translated strings where <xliff:g> markup was escaped and shows up literally in the UI (#477)."
    val projectRoot = rootDir
    doLast {
        val escapedXliff = Regex(
            "&lt;\\s*xliff:g\\b.*?&gt;(.*?)&lt;\\s*/\\s*xliff:g\\s*&gt;",
            RegexOption.DOT_MATCHES_ALL,
        )
        var filesChanged = 0
        var replacements = 0
        projectRoot.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" }
            .filter { it.isFile && it.name == "strings.xml" }
            .forEach { file ->
                val original = file.readText()
                var count = 0
                val fixed = escapedXliff.replace(original) { match ->
                    count++
                    match.groupValues[1]
                }
                if (count > 0) {
                    file.writeText(fixed)
                    filesChanged++
                    replacements += count
                    println("fixStrings: fixed $count occurrence(s) in ${file.relativeTo(projectRoot)}")
                }
            }
        println("fixStrings: done. Fixed $replacements occurrence(s) across $filesChanged file(s).")
    }
}

// Store listings are generated from metadata_data/<module-key>.md by the release build,
// which takes line 1
// as the short description and the whole file as the full description. Play and F-Droid cap a
// summary at 80 characters, and nothing else in the build looks at these files, so the format
// is enforced here. Required shape, exactly:
//
//   1  a summary, at most 80 characters
//   2  blank
//   3  Features:
//   4+ one or more "- " bullets
//      blank
//      a connectivity line: "100% offline", "Requires internet",
//      "Internet required only for initial asset downloads", or
//      "Internet only used for: <feature(s)>" - the last only when the app still mostly
//      works without a connection.
tasks.register("checkMetadata") {
    group = "verification"
    description = "Checks every app module has a metadata_data/*.md store listing in the required format."
    val projectRoot = rootDir
    doLast {
        val fixedConnectivity = listOf(
            "100% offline",
            "Requires internet",
            "Internet required only for initial asset downloads",
        )
        val connectivityPrefix = "Internet only used for: "

        // Declared inside the task action rather than as a script-level function so the
        // action does not capture a reference to the build script, which the configuration
        // cache cannot serialize.
        val problemsIn = { lines: List<String> ->
            // summary, blank, "Features:", one bullet, blank, connectivity
            if (lines.size < 6) {
                listOf("has only ${lines.size} line(s); the format needs at least 6")
            } else {
                val problems = mutableListOf<String>()
                val summary = lines[0]
                if (summary.isBlank()) {
                    problems += "line 1 (the short description) is empty"
                } else if (summary.length > 80) {
                    problems += "line 1 is ${summary.length} characters; the summary caps at 80"
                }
                if (lines[1].isNotEmpty()) {
                    problems += "line 2 must be blank, but is \"${lines[1]}\""
                }
                if (lines[2] != "Features:") {
                    problems += "line 3 must be \"Features:\", but is \"${lines[2]}\""
                }

                val connectivity = lines.last()
                if (connectivity in fixedConnectivity) {
                    // fine
                } else if (connectivity.startsWith(connectivityPrefix)) {
                    if (connectivity.removePrefix(connectivityPrefix).isBlank()) {
                        problems += "\"$connectivityPrefix\" must name the feature(s) using the internet"
                    }
                } else {
                    problems += "the last line must be one of " +
                        fixedConnectivity.joinToString(", ") { "\"$it\"" } +
                        " or \"$connectivityPrefix<feature(s)>\", but is \"$connectivity\""
                }
                if (lines[lines.size - 2].isNotEmpty()) {
                    problems += "the last line must be preceded by a blank line"
                }

                val bullets = lines.subList(3, lines.size - 2)
                if (bullets.isEmpty()) problems += "there must be at least one feature bullet"
                bullets.forEachIndexed { index, line ->
                    if (!line.startsWith("- ")) {
                        problems += "line ${index + 4} must be a \"- \" bullet, but is \"$line\""
                    } else if (line.removePrefix("- ").isBlank()) {
                        problems += "line ${index + 4} is an empty feature bullet"
                    }
                }
                problems
            }
        }

        val metadataDir = File(projectRoot, "metadata_data")

        // Module detection by grepping for the bare string
        // "common-conventions-app", which also matches library/map, where it appears only in a
        // comment. Matching the plugin application instead keeps this list to real apps.
        // analysis/ and personal/ hold gitignored experiments with copied app build files;
        // they are never published, so skip them (same exclusions as release-build.sh).
        val appPlugin = Regex("""id\("common-conventions-app"\)""")
        val moduleKeys = projectRoot.walkTopDown()
            .onEnter {
                it.name != "build" && it.name != ".git" &&
                    it.name != "analysis" && it.name != "personal"
            }
            .filter { it.isFile && it.name == "build.gradle.kts" }
            .filter { appPlugin.containsMatchIn(it.readText()) }
            .map { it.parentFile.relativeTo(projectRoot).invariantSeparatorsPath.replace('/', '-') }
            .toSortedSet()

        val problems = mutableListOf<String>()
        for (key in moduleKeys) {
            val file = File(metadataDir, "$key.md")
            if (!file.isFile) {
                problems += "$key.md: missing; every app module needs a store listing"
                continue
            }
            problemsIn(file.readLines()).forEach { problems += "$key.md: $it" }
        }

        val listings = metadataDir.listFiles { f: File -> f.isFile && f.extension == "md" }
            ?.map { it.nameWithoutExtension }
            .orEmpty()
        (listings - moduleKeys).sorted().forEach {
            problems += "$it.md: does not match any app module, so it is never published"
        }

        problems.forEach { println("checkMetadata: $it") }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "checkMetadata: ${problems.size} problem(s) across ${moduleKeys.size} app module(s).",
            )
        }
        println("checkMetadata: ${moduleKeys.size} store listing(s) are correctly formatted.")
    }
}

// Jacoco XML summaries land under build/reports/coverage after unit or instrumented runs,
// while manual runs drop .ec files under build/outputs/coverage-manual. This task only reads
// existing outputs, so it never triggers test execution.
tasks.register("coverageSummary") {
    group = "verification"
    description = "Sums per-module Jacoco report.xml counters " +
        "into a per-module + total table (unit + instrumented + manual .ec inventory)."
    val projectRoot = rootDir
    doLast {
        val reportMarker = "build/reports/coverage/"
        // analysis/ holds gitignored reference-app checkouts, not modules.
        val skipDirs = setOf(".git", "analysis")
        val reports = projectRoot.walkTopDown()
            .onEnter { it.name !in skipDirs }
            .filter { it.isFile && it.name == "report.xml" }
            .filter { it.relativeTo(projectRoot).invariantSeparatorsPath.contains(reportMarker) }
            .toList()
        if (reports.isEmpty()) {
            println("coverageSummary: no coverage data yet, run ./test")
        }
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        factory.isValidating = false
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        val docBuilder = factory.newDocumentBuilder()
        val coveredByModule = mutableMapOf<String, MutableMap<String, LongArray>>()
        val moduleKeyFor = { file: File, marker: String ->
            val rel = file.relativeTo(projectRoot).invariantSeparatorsPath
            val modulePath = rel.substringBefore("/$marker").trim()
            if (modulePath.isEmpty()) "root" else modulePath.replace('/', '-')
        }
        for (report in reports) {
            val key = moduleKeyFor(report, reportMarker)
            val counts = coveredByModule.getOrPut(key) { mutableMapOf() }
            val doc = report.inputStream().use { docBuilder.parse(it) }
            // Only the root <report> element's own counters: getElementsByTagName
            // returns every nested package/class/method counter too, which would
            // multiply-count the same instructions.
            val root = doc.documentElement
            val nodes = root.childNodes
            for (i in 0 until nodes.length) {
                val element = nodes.item(i) as? org.w3c.dom.Element ?: continue
                if (element.tagName != "counter") continue
                val type = element.getAttribute("type")
                if (type != "INSTRUCTION" && type != "BRANCH" && type != "LINE") {
                    if (type != "METHOD") continue
                }
                val slot = counts.getOrPut(type) { longArrayOf(0L, 0L) }
                slot[0] += element.getAttribute("covered").toLongOrNull() ?: 0L
                slot[1] += element.getAttribute("missed").toLongOrNull() ?: 0L
            }
        }
        val pct = { covered: Long, total: Long ->
            if (total > 0) "%.1f".format(covered * 100.0 / total) else "100.0"
        }
        val lineFor = { counts: Map<String, LongArray> ->
            val instr = counts["INSTRUCTION"] ?: longArrayOf(0L, 0L)
            val branch = counts["BRANCH"] ?: longArrayOf(0L, 0L)
            val line = counts["LINE"] ?: longArrayOf(0L, 0L)
            val instrTotal = instr[0] + instr[1]
            val branchTotal = branch[0] + branch[1]
            val lineTotal = line[0] + line[1]
            "instr ${pct(instr[0], instrTotal)}% (${instr[0]}/$instrTotal), " +
                "branch ${pct(branch[0], branchTotal)}%, line ${pct(line[0], lineTotal)}%"
        }
        for (key in coveredByModule.keys.sorted()) {
            println("coverageSummary: $key: ${lineFor(coveredByModule.getValue(key))}")
        }
        if (coveredByModule.isNotEmpty()) {
            val total = mutableMapOf<String, LongArray>()
            for (counts in coveredByModule.values) {
                for ((type, slot) in counts) {
                    val acc = total.getOrPut(type) { longArrayOf(0L, 0L) }
                    acc[0] += slot[0]
                    acc[1] += slot[1]
                }
            }
            println("coverageSummary: TOTAL: ${lineFor(total)}")
        }
        val ecMarker = "build/outputs/coverage-manual/"
        val ecFiles = projectRoot.walkTopDown()
            .onEnter { it.name !in skipDirs }
            .filter { it.isFile && it.extension == "ec" }
            .filter { it.relativeTo(projectRoot).invariantSeparatorsPath.contains(ecMarker) }
            .sortedBy { it.relativeTo(projectRoot).invariantSeparatorsPath }
            .toList()
        if (ecFiles.isEmpty()) {
            println("coverageSummary: no manual .ec files yet")
        } else {
            for (ec in ecFiles) {
                val key = moduleKeyFor(ec, ecMarker)
                val rel = ec.relativeTo(projectRoot).invariantSeparatorsPath
                println("coverageSummary: ec $key: $rel (${ec.length()} bytes)")
            }
        }
        val testedModules = projectRoot.walkTopDown()
            .onEnter { it.name != ".git" && it.name != "build" && it.name != "analysis" }
            .filter { it.isDirectory && (it.name == "test" || it.name == "androidTest") }
            .filter { it.parentFile != null && it.parentFile.name == "src" }
            .map {
                val rel = it.relativeTo(projectRoot).invariantSeparatorsPath
                val modulePath = rel.substringBefore("/src/")
                if (modulePath.isEmpty()) "root" else modulePath.replace('/', '-')
            }
            .toSortedSet()
        val gaps = (testedModules - coveredByModule.keys).sorted()
        if (gaps.isEmpty()) {
            println("coverageSummary: no coverage gaps.")
        } else {
            for (key in gaps) {
                println("coverageSummary: no report for $key (has src/test or src/androidTest)")
            }
        }
    }
}