// Qwen3-0.6B task model (src/main/assets/models, git-ignored). Install-time AI pack: Google Play installs it with the
// app, and ModelManager reads it from the app's assets as before.
plugins {
    alias(libs.plugins.android.ai.pack)
}

aiPack {
    packName = "task_model"
    dynamicDelivery {
        deliveryType = "install-time"
    }
}
