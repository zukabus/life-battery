package dev.jenil.capt


private var fails = 0
private fun eq(name: String, got: Any?, want: Any?) {
    if (got == want) println("PASS  $name") else { println("FAIL  $name: got $got, want $want"); fails++ }
}
private fun bad(name: String, spec: String, pages: Int) {
    try { PageRange.parse(spec, pages); println("FAIL  $name: expected rejection"); fails++ }
    catch (e: IllegalArgumentException) { println("PASS  $name -> \"${e.message}\"") }
}

fun main() {
    eq("blank = all pages", PageRange.parse("", 3), listOf(0, 1, 2))
    eq("single page", PageRange.parse("2", 5), listOf(1))
    eq("simple range", PageRange.parse("1-3", 5), listOf(0, 1, 2))
    eq("mixed", PageRange.parse("1-2,4", 5), listOf(0, 1, 3))
    eq("spaces tolerated", PageRange.parse(" 1 - 2 , 4 ", 5), listOf(0, 1, 3))
    eq("open end", PageRange.parse("3-", 5), listOf(2, 3, 4))
    eq("open start", PageRange.parse("-2", 5), listOf(0, 1))
    eq("duplicates and order kept", PageRange.parse("3,1,3", 5), listOf(2, 0, 2))
    eq("whole doc explicitly", PageRange.parse("1-5", 5), listOf(0, 1, 2, 3, 4))
    eq("trailing comma ignored", PageRange.parse("1,", 5), listOf(0))

    bad("page zero", "0", 5)
    bad("past the end", "6", 5)
    bad("range past the end", "3-9", 5)
    bad("backwards range", "4-2", 5)
    bad("not a number", "abc", 5)
    bad("empty selection", ",", 5)

    println()
    println(if (fails == 0) "all checks passed" else "$fails check(s) failed")
    if (fails > 0) kotlin.system.exitProcess(1)
}
