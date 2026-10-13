package com.fromwau.kern.dirs

/**
 * Which convention lays out the roots on macOS, the one platform with two in common use. Pass it to the factory on
 * the JVM and on macOS native; every other platform has one convention and takes none.
 */
public enum class DirLayout {
    /** `~/Library/Application Support` and `~/Library/Caches`, as a macOS app expects. */
    Native,

    /**
     * `~/.config`, `~/.local/share`, `~/.local/state` and `~/.cache`, or the `XDG_*` variables when set, as many
     * command-line tools use on macOS so their files sit with the user's other dotfiles.
     */
    Xdg,
}
