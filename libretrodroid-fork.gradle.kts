// lemuroid-sync: LibretroDroid comes from the sybenx fork, which adds what the link cable needs. The fork
// follows upstream releases, and libretrodroid-fork.version names the build to use (the CI picks the one
// that matches the version upstream asks for in deps.kt).
val libretroDroidFork = rootProject.file("libretrodroid-fork.version").readText().trim()

allprojects {
    configurations.all {
        resolutionStrategy.dependencySubstitution {
            substitute(module("com.github.Swordfish90:LibretroDroid"))
                .using(module("com.github.sybenx:LibretroDroid:$libretroDroidFork"))
                .because("Lemuroid Sync needs the link cable additions")
        }
    }
}
