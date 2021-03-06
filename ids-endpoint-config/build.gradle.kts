@Suppress("UNCHECKED_CAST")
val libraryVersions = rootProject.extra.get("libraryVersions") as Map<String, String>

dependencies {
    providedByBundle(project(":ids-api")) { isTransitive = false }

    osgiCore("org.osgi", "osgi.cmpn", libraryVersions["osgiCompendium"])

    testImplementation("junit", "junit", libraryVersions["junit4"])
    testImplementation("org.mockito", "mockito-core", libraryVersions["mockito"])
}
