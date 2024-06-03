// use an integer for version numbers
version = 7


cloudstream {
    language = "zh"
    // All of these properties are optional, you can safely remove them
    // description = "Lorem Ipsum"
    authors = listOf("wander-specter")
    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "AnimeMovie",
        "Anime",
    )
    iconUrl = "https://2rk.cc/logo.png"
}