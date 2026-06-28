package com.infraspine.callsync.ui.recordings

enum class RecordingSortOrder(val label: String, val sql: String) {
    DATE_DESC("Date (Newest)", "callStartedAt DESC, lastModified DESC"),
    DATE_ASC("Date (Oldest)", "callStartedAt ASC, lastModified ASC"),
    DURATION_DESC("Duration (Longest)", "durationSeconds DESC"),
    DURATION_ASC("Duration (Shortest)", "durationSeconds ASC")
}
