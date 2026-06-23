package com.lspo

data class ActivityRecord(
    val id: Long = 0,
    val packageName: String,
    val activityClass: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toDisplayString(): String = "$packageName / ${activityClass.substringAfterLast('.')}"
}
