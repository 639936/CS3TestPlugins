dependencies {
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
}
version = 2

cloudstream {
    description = "VlxxAV"
    authors = listOf("vs69")

    /**
    * Status int as one of the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta-only
    **/
    status = 1

    tvTypes = listOf("NSFW")

    language = "vi"

    iconUrl = "https://cdn-icons-png.flaticon.com/512/9484/9484251.png"
}