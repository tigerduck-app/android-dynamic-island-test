// Root build file. Declares plugin VERSIONS for the whole build.
// `apply false` = put the plugin on the build classpath but don't activate it
// here; the root project is a container, not an Android app.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
