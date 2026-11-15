plugins {
    alias(kei.plugins.multisrc)
}

dependencies {
    api(project(":lib:megaplayextractor"))
    api(project(":lib:playlistutils"))
}

yuzono {
    baseVersionCode = 11
    libVersion = "16"
}
