allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

val newBuildDir: Directory =
    rootProject.layout.buildDirectory
        .dir("../../build")
        .get()
rootProject.layout.buildDirectory.value(newBuildDir)

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)
}
// Several plugins still compile against API 34/35, while
// flutter_plugin_android_lifecycle requires API 36.
// Register this before evaluationDependsOn, which evaluates :app immediately.
subprojects {
    afterEvaluate {
        val android = extensions.findByName("android") ?: return@afterEvaluate
        val setter = android.javaClass.methods.firstOrNull { method ->
            (method.name == "setCompileSdk" || method.name == "setCompileSdkVersion") &&
                method.parameterCount == 1 &&
                (method.parameterTypes[0] == Int::class.javaPrimitiveType ||
                    method.parameterTypes[0] == Integer::class.java)
        }
        setter?.invoke(android, 36)
    }
}
subprojects {
    project.evaluationDependsOn(":app")
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
