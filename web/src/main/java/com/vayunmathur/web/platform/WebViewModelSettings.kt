package com.vayunmathur.web.platform

// ---- Browser settings ----

fun WebViewModel.updateCacheMode(mode: CacheMode) {
    cacheMode = mode
    persistPrefs()
}

fun WebViewModel.updateSearchEngine(engine: SearchEngine) {
    // Dev-gated engine can never be selected on release (belt-and-braces:
    // the picker already hides it there).
    searchEngine = SearchEngine.effective(engine)
    persistPrefs()
}

fun WebViewModel.updateJsEnabled(enabled: Boolean) {
    jsEnabled = enabled
    persistPrefs()
}

fun WebViewModel.updateBlockThirdParty(block: Boolean) {
    blockThirdPartyCookies = block
    persistPrefs()
}

fun WebViewModel.updateDesktopMode(enabled: Boolean) {
    desktopMode = enabled
    persistPrefs()
}

fun WebViewModel.updateSearchBarAtBottom(atBottom: Boolean) {
    searchBarAtBottom = atBottom
    persistPrefs()
}
