plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

/**
 * The verification suites are plain `main()` functions rather than JUnit
 * tests, so they can also be run by hand with `kotlin -cp ...`. Wire them
 * into `check` so CI runs them on every push.
 */
val verifyHiScoa by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Compares the Kotlin HiSCoA encoder against captdriver's C output"
    mainClass.set("dev.jenil.capt.HiScoaVectorTestKt")
    classpath = sourceSets["test"].runtimeClasspath
    args(layout.projectDirectory.file("src/test/resources/vectors.txt").asFile.absolutePath)
}

val verifyJobSequence by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs the CAPT job state machine against a scripted fake printer"
    mainClass.set("dev.jenil.capt.JobSequenceTestKt")
    classpath = sourceSets["test"].runtimeClasspath
}

tasks.named("check") {
    dependsOn(verifyHiScoa, verifyJobSequence)
}
