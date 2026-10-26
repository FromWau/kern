package com.fromwau.kern.fuzzy

internal data class Case(
    val written: String,
    val expected: String?,
    val alsoRight: Set<String?> = emptySet(),
)

internal data class CandidateSet(
    val name: String,
    val candidates: List<String>,
    val cases: List<Case>,
)

private fun typo(
    written: String,
    expected: String,
) = Case(written, expected)

private fun none(written: String) = Case(written, null)

private fun either(
    written: String,
    vararg right: String?,
) = Case(written, right.first(), right.toSet())

/** Each case [didYouMean] gets wrong, as `'written' > got (want expected)`. */
internal fun misses(sets: List<CandidateSet>): List<String> = sets.flatMap { set ->
    set.cases.mapNotNull { case ->
        val got = didYouMean(case.written, set.candidates)
        if (got == case.expected || got in case.alsoRight) {
            null
        } else {
            "${set.name}: '${case.written}' > ${got ?: "none"} (want ${case.expected ?: "none"})"
        }
    }
}

internal val cliCorpus: List<CandidateSet> = listOf(
    CandidateSet(
        "commands",
        listOf(
            "status", "stash", "switch", "show", "checkout", "cherry-pick", "commit", "config", "clone", "clean",
            "add", "rm", "mv", "log", "diff", "push", "pull", "fetch", "init", "tag",
        ),
        listOf(
            typo("stauts", "status"), typo("statsu", "status"), typo("swich", "switch"), typo("chekcout", "checkout"),
            typo("checkuot", "checkout"), typo("comit", "commit"), typo("commmit", "commit"), typo("cofnig", "config"),
            typo("clnoe", "clone"), typo("pul", "pull"), typo("psuh", "push"), typo("fecth", "fetch"),
            typo("dif", "diff"), typo("lgo", "log"), typo("ad", "add"), typo("tga", "tag"), typo("STATUS", "status"),
            typo("Commit", "commit"), typo("cherrypick", "cherry-pick"), typo("cherry_pick", "cherry-pick"),
            typo("inti", "init"), typo("mr", "rm"),
            none("s"), none("st"), none("c"), none("co"), none("ch"), none("x"), none("foo"), none("list"),
            none("remove"), none("move"), none("a"), none("p"), none("xyz"), none("hello"), none(""), none("deploy"),
            either("stats", "status", null), either("clear", "clean", null), either("close", "clone", null),
            either("comet", "commit", null), either("tags", "tag", null), either("sta", null, "status", "stash"),
            either("che", null, "checkout", "cherry-pick"),
        ),
    ),
    CandidateSet(
        "long options",
        listOf(
            "--verbose", "--version", "--recursive", "--dry-run", "--force", "--format", "--color", "--quiet",
            "--help", "--all",
        ),
        listOf(
            typo("--verbos", "--verbose"), typo("--vresion", "--version"), typo("-verbose", "--verbose"),
            typo("--dryrun", "--dry-run"), typo("--dry_run", "--dry-run"), typo("--froce", "--force"),
            typo("--colour", "--color"), typo("--qiuet", "--quiet"), typo("--hlep", "--help"),
            typo("--recrusive", "--recursive"), typo("--al", "--all"), typo("--VERBOSE", "--verbose"),
            none("--v"), none("--x"), none("--foo"), none("--output"), none("--"), none("-q"),
            either("--ver", null, "--verbose", "--version"), either("--forma", "--format", null),
        ),
    ),
    CandidateSet(
        "color choices",
        listOf("auto", "always", "never"),
        listOf(
            typo("alway", "always"), typo("nevr", "never"), typo("atuo", "auto"), typo("Always", "always"),
            typo("allways", "always"),
            none("a"), none("yes"), none("no"), none("on"), none("off"), none("true"),
        ),
    ),
    CandidateSet(
        "shells",
        listOf("bash", "zsh", "fish", "powershell"),
        listOf(
            typo("bsh", "bash"), typo("zhs", "zsh"), typo("fsh", "fish"), typo("powershel", "powershell"),
            typo("powrshell", "powershell"), typo("BASH", "bash"),
            none("cmd"), none("tcsh"), none("x"), none("nu"),
            either("pwsh", "powershell", null), either("sh", null, "bash", "zsh", "fish"),
        ),
    ),
)

private val logLevels = listOf("trace", "debug", "info", "warn", "error", "off")

internal val heldOut1: List<CandidateSet> = listOf(
    CandidateSet(
        "docker",
        listOf(
            "run", "build", "pull", "push", "images", "ps", "exec", "logs", "stop", "start",
            "restart", "rm", "rmi", "inspect", "network", "volume", "compose", "login", "logout", "tag",
            "version", "info", "kill", "attach", "commit", "create", "cp", "diff", "export", "history",
            "import", "load", "save", "pause", "unpause", "port", "rename", "search", "stats", "top",
            "update", "wait", "system", "container", "image", "context", "plugin", "builder", "events", "secret",
        ),
        listOf(
            typo("biuld", "build"), typo("imgaes", "images"), typo("exce", "exec"), typo("lgos", "logs"),
            typo("stpo", "stop"), typo("restrat", "restart"), typo("inspcet", "inspect"), typo("netwrok", "network"),
            typo("volumn", "volume"), typo("compsoe", "compose"), typo("loign", "login"), typo("atach", "attach"),
            typo("histroy", "history"), typo("pasue", "pause"), typo("contianer", "container"),
            typo("Inspect", "inspect"), typo("searhc", "search"), typo("unpuase", "unpause"),
            none("deploy"), none("foo"), none("r"), none("i"), none("zz"), none("kubectl"), none("apply"), none("ls"),
            either("stat", "stats", "start", null), either("img", "image", "images", null),
        ),
    ),
    CandidateSet(
        "kubectl flags",
        listOf(
            "--namespace", "--selector", "--output", "--all-namespaces", "--dry-run", "--filename",
            "--kubeconfig", "--context", "--watch", "--show-labels", "--field-selector", "--sort-by",
        ),
        listOf(
            typo("--namspace", "--namespace"), typo("--selctor", "--selector"), typo("--outptu", "--output"),
            typo("--all-namespace", "--all-namespaces"), typo("--filname", "--filename"),
            typo("--kubconfig", "--kubeconfig"), typo("--wacth", "--watch"), typo("--show-label", "--show-labels"),
            typo("--sortby", "--sort-by"), typo("--Namespace", "--namespace"),
            typo("--n", "--namespace"), none("--verbose"), none("--force"), none("--yes"),
            either("--name", null, "--namespace"),
        ),
    ),
    CandidateSet(
        "npm scripts",
        listOf("build", "test", "lint", "start", "dev", "format", "typecheck", "preview", "clean", "release"),
        listOf(
            typo("tset", "test"), typo("lnit", "lint"), typo("strat", "start"), typo("dve", "dev"),
            typo("fromat", "format"), typo("typechek", "typecheck"), typo("preveiw", "preview"),
            typo("relase", "release"), typo("Build", "build"),
            none("deploy"), none("install"), none("t"), none("d"), none("x"), none("serve"), none("run"),
        ),
    ),
    CandidateSet(
        "log levels",
        logLevels,
        listOf(
            typo("debgu", "debug"), typo("eror", "error"), typo("wran", "warn"), typo("trcae", "trace"),
            typo("INFO", "info"), typo("warning", "warn"),
            none("fatal"), none("verbose"), none("all"), none("x"),
            either("err", "error", null), either("of", "off", null),
        ),
    ),
)

internal val heldOut2: List<CandidateSet> = listOf(
    CandidateSet(
        "docker",
        listOf(
            "run", "build", "pull", "push", "images", "ps", "exec", "logs", "stop", "start", "restart", "rm",
            "rmi", "network", "volume", "compose", "inspect", "tag", "login", "logout",
        ),
        listOf(
            typo("biuld", "build"), typo("imgaes", "images"), typo("exce", "exec"), typo("lgos", "logs"),
            typo("stpo", "stop"), typo("strat", "start"), typo("restat", "restart"), typo("netwrok", "network"),
            typo("volumn", "volume"), typo("compsoe", "compose"), typo("inpsect", "inspect"), typo("lgoin", "login"),
            typo("Run", "run"), typo("biuldd", "build"),
            none("deploy"), none("x"), none("r"), none("foo"), none("k"), none("st"), none("ls"), none("version"),
        ),
    ),
    CandidateSet(
        "kubectl",
        listOf(
            "get", "describe", "apply", "delete", "create", "logs", "exec", "edit", "scale", "rollout",
            "port-forward", "config", "cluster-info", "explain", "label", "annotate", "expose", "top", "drain",
            "cordon",
        ),
        listOf(
            typo("gte", "get"), typo("decsribe", "describe"), typo("aplly", "apply"), typo("delte", "delete"),
            typo("craete", "create"), typo("portforward", "port-forward"), typo("port_forwad", "port-forward"),
            typo("rolout", "rollout"), typo("anotate", "annotate"), typo("expalin", "explain"),
            typo("clusterinfo", "cluster-info"), typo("DESCRIBE", "describe"), typo("lable", "label"),
            none("g"), none("run"), none("patch"), none("wait"), none("auth"),
        ),
    ),
    CandidateSet(
        "npm scripts",
        listOf("build", "test", "lint", "start", "dev", "format", "typecheck", "preview"),
        listOf(
            typo("tset", "test"), typo("lnit", "lint"), typo("devv", "dev"), typo("fromat", "format"),
            typo("type-check", "typecheck"), typo("previwe", "preview"), typo("tets", "test"),
            none("deploy"), none("t"), none("serve"), none("prod"),
        ),
    ),
    CandidateSet(
        "kubectl flags",
        listOf(
            "--output", "--namespace", "--selector", "--all-namespaces", "--watch", "--dry-run", "--force",
            "--timeout", "--kubeconfig", "--context",
        ),
        listOf(
            typo("--ouput", "--output"), typo("--namspace", "--namespace"), typo("--selecter", "--selector"),
            typo("--all-namspaces", "--all-namespaces"), typo("--wacth", "--watch"), typo("--timout", "--timeout"),
            typo("--kubeconfg", "--kubeconfig"), typo("--contxet", "--context"), typo("--Namespace", "--namespace"),
            typo("--o", "--output"), typo("--name", "--namespace"), typo("--all", "--all-namespaces"),
            none("-n"), none("--wait"), none("--follow"),
        ),
    ),
    CandidateSet(
        "log levels",
        logLevels,
        listOf(
            typo("debgu", "debug"), typo("infoo", "info"), typo("wran", "warn"), typo("eror", "error"),
            typo("WARN", "warn"), typo("trcae", "trace"),
            none("fatal"), none("verbose"), none("all"),
            either("warning", "warn", null), either("of", "off", null), either("err", "error", null),
        ),
    ),
    CandidateSet(
        "output formats",
        listOf("json", "yaml", "wide", "name", "table"),
        listOf(
            typo("josn", "json"), typo("yml", "yaml"), typo("jsn", "json"), typo("tabel", "table"),
            none("xml"), none("csv"), none("text"),
        ),
    ),
)

internal val heldOut3: List<CandidateSet> = listOf(
    CandidateSet(
        "container commands",
        listOf(
            "build", "run", "exec", "ps", "images", "pull", "push", "logs", "stop", "start", "restart", "rm",
            "rmi", "inspect", "network", "volume", "compose", "login", "logout", "version",
        ),
        listOf(
            typo("biuld", "build"), typo("buidl", "build"), typo("exce", "exec"), typo("imgaes", "images"),
            typo("imagse", "images"), typo("imges", "images"), typo("lgos", "logs"), typo("stpo", "stop"),
            typo("strat", "start"), typo("restrat", "restart"), typo("inspcet", "inspect"), typo("netwrok", "network"),
            typo("volumn", "volume"), typo("compsoe", "compose"), typo("loign", "login"), typo("verison", "version"),
            typo("Build", "build"), typo("pul", "pull"), typo("pushh", "push"), typo("logi", "login"),
            none("deploy"), none("x"), none("ls"), none("r"), none("kill"), none("cp"), none("remove"),
            either("sp", "ps", null), either("re", "restart", null), either("stat", "start", null),
            either("rn", null, "rm", "run"),
        ),
    ),
    CandidateSet(
        "build flags",
        listOf(
            "--output", "--input", "--timeout", "--retries", "--no-cache", "--pull", "--tag", "--file", "--quiet",
            "--debug",
        ),
        listOf(
            typo("--ouput", "--output"), typo("--outptu", "--output"), typo("--timout", "--timeout"),
            typo("--time-out", "--timeout"), typo("--nocache", "--no-cache"), typo("--no_cache", "--no-cache"),
            typo("--DEBUG", "--debug"), typo("--debgu", "--debug"), typo("--fiel", "--file"), typo("--tga", "--tag"),
            none("--x"), none("--verbose"), none("-o"),
            either("--retry", "--retries", null), either("--in", "--input", null),
        ),
    ),
    CandidateSet(
        "log levels",
        logLevels,
        listOf(
            typo("inof", "info"), typo("wran", "warn"), typo("erorr", "error"), typo("eror", "error"),
            typo("WARN", "warn"), none("verbose"), none("fatal"), none("o"), none("e"),
            either("warning", "warn", null), either("of", "off", null), either("dbg", "debug", null),
        ),
    ),
    CandidateSet(
        "formats",
        listOf("json", "yaml", "toml", "csv", "table"),
        listOf(
            typo("jsno", "json"), typo("ymal", "yaml"), typo("yml", "yaml"), typo("JSON", "json"),
            typo("tomll", "toml"), typo("cvs", "csv"), typo("tabel", "table"), none("xml"), none("txt"), none("j"),
        ),
    ),
)

private val keywords = listOf("true", "false", "inf", "nan")

private val signedKeywords = listOf("inf", "nan")

internal val tomlKeywords: List<CandidateSet> = listOf(
    CandidateSet(
        "keywords",
        keywords,
        listOf(
            typo("in", "inf"), none("inf"), typo("in_f", "inf"), typo("ifn", "inf"), typo("na", "nan"),
            typo("nna", "nan"), typo("tru", "true"), typo("ture", "true"), typo("trye", "true"), typo("tdue", "true"),
            typo("fals", "false"), typo("fasle", "false"), typo("faalse", "false"), typo("rue", "true"),
            typo("yrue", "true"), typo("alse", "false"), typo("nf", "inf"), typo("an", "nan"), typo("tr", "true"),
            typo("fa", "false"),
            none("tomato"), none("foo"), none("t"), none("f"), none("trash"), none("fail"), none("index"), none("n"),
            none("in_fo"), none("value"), none("imf"), none("inn"), none("nam"), none("can"), none("man"),
            none("tan"), none("i don't belong"), none("no"),
        ),
    ),
    CandidateSet(
        "signed keywords",
        signedKeywords,
        listOf(
            typo("in", "inf"), typo("nf", "inf"), none("nil"), none("man"), none("true"), none("rue"), none("fals"),
        ),
    ),
)
