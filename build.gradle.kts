// Root build file. It only declares plugin versions; "apply false" means
// "make this plugin available, but the :app module decides whether to use it".
plugins {
    alias(libs.plugins.android.application) apply false
}
