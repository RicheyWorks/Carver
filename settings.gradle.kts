rootProject.name = "carver"

// Composite build: Carver is the fourth engine of the ecosystem — the read planner that
// decides HOW to read what SmokeHouse preserves, CSRBT orders, and SuperBeefSort feeds.
// Including SmokeHouse's build transitively includes SuperBeefSort and CSRBT (nested
// composites); Gradle substitutes all three published coordinates with the live sibling
// sources — no publish step, always builds against reality.
includeBuild("../SmokeHouse")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
