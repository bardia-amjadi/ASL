package dev.asl.app.runtime

enum class Verdict { PASS, FAIL, INFO }

/** Result of one experiment. Reported verbatim in the UI; nothing is inferred or faked. */
data class Finding(val title: String, val verdict: Verdict, val detail: String)
